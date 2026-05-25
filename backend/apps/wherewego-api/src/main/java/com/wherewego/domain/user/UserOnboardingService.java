package com.wherewego.domain.user;

import com.wherewego.domain.bot.BotUserMappingRepository;
import com.wherewego.domain.group.GroupMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 온보딩 상태 조회 (Phase 11 PR-B).
 *
 * <p>지도/마이페이지/위저드에서 공통으로 사용하는 진입 상태 컴포지트.
 * 사용자별 Caffeine 60s 캐시로 회원가입 직후 빠른 폴링 부담을 경감한다.</p>
 *
 * <p>그룹 변경(가입/탈퇴) 시 별도 invalidate 는 적용하지 않는다 — 60초 TTL 후 자동 재계산.
 * 챗봇 코드 발급 후에도 동일.</p>
 */
@Service
@RequiredArgsConstructor
public class UserOnboardingService {

    public static final String CACHE_NAME = "onboardingStatus";

    private final GroupMemberRepository groupMemberRepository;
    private final BotUserMappingRepository botUserMappingRepository;

    @Cacheable(value = CACHE_NAME, key = "#userId")
    @Transactional(readOnly = true)
    public OnboardingStatus getStatus(Long userId) {
        boolean hasActiveGroup = groupMemberRepository.existsActiveByUserId(userId);
        long memberCount = 0L;
        if (hasActiveGroup) {
            memberCount = groupMemberRepository.findLatestActiveGroupIdByUserId(userId)
                    .map(groupMemberRepository::countActiveByGroupId)
                    .orElse(0L);
        }
        boolean hasBotMapping = botUserMappingRepository.findByUserId(userId).isPresent();
        return new OnboardingStatus(hasActiveGroup, memberCount, hasBotMapping);
    }
}
