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
        // findLatestActiveGroupIdByUserId 만으로 존재 여부 + 그룹 ID 를 동시에 얻는다.
        // (이전엔 existsActiveByUserId 와 findLatestActiveGroupIdByUserId 를 모두 호출하여 DB 왕복이 1회 더 발생했음.)
        // GM-1: 다중 활성 그룹 환경에서도 hasActiveGroup=존재여부, memberCount=최신(id DESC) 활성 그룹의 멤버수로
        //       웹 호환을 유지한다(BR-6). 다중 선택은 GM-2 로 이관.
        boolean hasBotMapping = botUserMappingRepository.findByUserId(userId).isPresent();
        return groupMemberRepository.findLatestActiveGroupIdByUserId(userId)
                .map(groupId -> new OnboardingStatus(
                        true,
                        groupMemberRepository.countActiveByGroupId(groupId),
                        hasBotMapping))
                .orElseGet(() -> new OnboardingStatus(false, 0L, hasBotMapping));
    }
}
