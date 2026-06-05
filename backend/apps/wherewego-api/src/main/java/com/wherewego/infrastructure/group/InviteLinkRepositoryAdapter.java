package com.wherewego.infrastructure.group;

import com.wherewego.domain.group.InviteLink;
import com.wherewego.domain.group.InviteLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class InviteLinkRepositoryAdapter implements InviteLinkRepository {

    private final InviteLinkJpaRepository inviteLinkJpaRepository;

    @Override
    public InviteLink save(InviteLink link) {
        // saveAndFlush로 통일하여 INSERT flush 시점을 동일 트랜잭션 내에서 명확히 한다 (PR #7 리뷰 반영).
        // IC-1: 정원 검사는 group 비관락 안에서 group_members count 로 직렬화하므로 INSERT 가 정원 정합 단위다.
        return inviteLinkJpaRepository.saveAndFlush(link);
    }

    @Override
    public Optional<InviteLink> findByToken(String token) {
        return inviteLinkJpaRepository.findByToken(token);
    }

    @Override
    public Optional<InviteLink> findActiveBySlug(String slug, Instant now) {
        return inviteLinkJpaRepository.findActiveBySlug(slug, now);
    }

    @Override
    public int expirePendingByGroupId(Long groupId, Instant now) {
        return inviteLinkJpaRepository.expirePendingByGroupId(groupId, now);
    }
}
