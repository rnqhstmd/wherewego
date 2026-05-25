package com.wherewego.domain.group;

import com.wherewego.infrastructure.group.InviteLinkJpaRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * V011 마이그레이션 후 슬러그가 비어있는 미수락/미만료 초대 링크에 base56 슬러그를 채운다.
 *
 * <p>신규 발급은 {@link InviteLink#issue} 가 항상 slug 를 채우므로,
 * 이 Runner 는 V011 적용 직후 1회 실행되어 잔여 NULL 행만 처리한다.
 * 이미 모두 채워진 상태라면 즉시 종료한다.</p>
 */
@Component
@RequiredArgsConstructor
public class InviteLinkBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InviteLinkBackfillRunner.class);
    private static final int MAX_RETRIES_PER_ROW = 5;

    private final InviteLinkJpaRepository inviteLinkJpaRepository;
    private final InviteLinkSlugGenerator slugGenerator;

    @Override
    public void run(ApplicationArguments args) {
        Instant now = Instant.now();
        List<Long> targets = inviteLinkJpaRepository.findIdsWithoutSlug(now);
        if (targets.isEmpty()) {
            return;
        }
        int filled = 0;
        int skipped = 0;
        for (Long id : targets) {
            boolean ok = false;
            for (int attempt = 0; attempt < MAX_RETRIES_PER_ROW; attempt++) {
                try {
                    int updated = inviteLinkJpaRepository.updateSlugIfNull(id, slugGenerator.generate());
                    if (updated > 0) {
                        ok = true;
                        break;
                    }
                } catch (DataIntegrityViolationException e) {
                    log.warn("slug 충돌 재시도 inviteLinkId={} attempt={}", id, attempt + 1);
                }
            }
            if (ok) {
                filled++;
            } else {
                skipped++;
                log.warn("slug 백필 실패 inviteLinkId={} (5회 충돌 또는 동시성)", id);
            }
        }
        log.info("invite_links slug 백필 완료: target={} filled={} skipped={}",
                targets.size(), filled, skipped);
    }
}
