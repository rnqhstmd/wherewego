package com.wherewego.domain.group;

import com.wherewego.infrastructure.group.InviteLinkJpaRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * V011 마이그레이션 후 슬러그가 비어있는 미수락/미만료 초대 링크에 base56 슬러그를 채운다.
 *
 * <p>신규 발급은 {@link InviteLink#issue} 가 항상 slug 를 채우므로,
 * 이 Runner 는 V011 적용 직후 1회 실행되어 잔여 NULL 행만 처리한다.
 * 이미 모두 채워진 상태라면 즉시 종료한다.</p>
 *
 * <p>대용량 대응을 위해 ID 목록을 {@link #BATCH_SIZE} 단위 페이지로 끊어 읽고,
 * {@code WHERE slug IS NULL} 조건이 backfill 진행에 따라 자동으로 좁아지므로
 * 항상 offset=0 페이지를 반복 조회한다. 빈 페이지가 나오면 종료한다.</p>
 *
 * <p>Spring Data JPA 의 {@code @Modifying} 쿼리는 활성 트랜잭션을 요구한다.
 * {@link #run} 에 메서드 레벨 {@link Transactional} 을 부여하여 백필 전체가
 * 단일 트랜잭션 안에서 실행되도록 보장한다 (V011 직후 1회 실행이므로 안전).</p>
 */
@Component
@RequiredArgsConstructor
public class InviteLinkBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InviteLinkBackfillRunner.class);
    private static final int MAX_RETRIES_PER_ROW = 5;
    private static final int BATCH_SIZE = 500;

    private final InviteLinkJpaRepository inviteLinkJpaRepository;
    private final InviteLinkSlugGenerator slugGenerator;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Instant now = Instant.now();
        int totalFilled = 0;
        int totalSkipped = 0;
        int totalSeen = 0;

        while (true) {
            List<Long> batch = inviteLinkJpaRepository.findIdsWithoutSlug(now, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            totalSeen += batch.size();
            int batchFilled = 0;
            int batchSkipped = 0;
            for (Long id : batch) {
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
                    batchFilled++;
                } else {
                    batchSkipped++;
                    log.warn("slug 백필 실패 inviteLinkId={} (5회 충돌 또는 동시성)", id);
                }
            }
            totalFilled += batchFilled;
            totalSkipped += batchSkipped;
            // 안전망: 한 페이지가 전부 skip 된 채로 동일 페이지를 무한 반복하지 않도록 차단.
            if (batchFilled == 0) {
                log.warn("invite_links slug 백필 중단 — 페이지 {}건 전체 skip", batch.size());
                break;
            }
        }

        if (totalSeen > 0) {
            log.info("invite_links slug 백필 완료: target={} filled={} skipped={}",
                    totalSeen, totalFilled, totalSkipped);
        }
    }
}
