package com.wherewego.domain.group;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GroupMemberService {

    private final GroupMemberRepository groupMemberRepository;

    /**
     * 사용자의 최근 활성 그룹 ID 를 반환한다. 활성 그룹이 없으면 {@link Optional#empty()}.
     * <p>핸들러 측에서 empty 분기를 SimpleText 안내로 변환한다 (FR-GRP-2).</p>
     */
    @Transactional(readOnly = true)
    public Optional<Long> findLatestActiveGroupIdByUserId(Long userId) {
        return groupMemberRepository.findLatestActiveGroupIdByUserId(userId);
    }
}
