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
        return inviteLinkJpaRepository.save(link);
    }

    @Override
    public Optional<InviteLink> findByToken(String token) {
        return inviteLinkJpaRepository.findByToken(token);
    }

    @Override
    public int expirePendingByGroupId(Long groupId, Instant now) {
        return inviteLinkJpaRepository.expirePendingByGroupId(groupId, now);
    }
}
