package com.wherewego.support.demo;

import com.wherewego.domain.user.OauthProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * P5 FR-25/BR-7/AC-21: 앱스토어 리뷰어 데모 계정 시드 설정. {@code wherewego.demo-seed.*} 바인딩.
 *
 * <p>{@code WherewegoApiApplication} 의 {@code @ConfigurationPropertiesScan} 으로 자동 등록된다.
 * ApnsProperties 와 동일하게 빈 값을 허용한다(검증 어노테이션 없음) — 운영/일반 환경에는
 * 데모 시드가 주입되지 않으므로 {@code enabled=false} 가 기본이며 {@link DemoSeedRunner} 가
 * {@code @ConditionalOnProperty} 로 비활성된다.</p>
 *
 * <p>BR-7/AC-21: 데모 계정 자격증명(특히 {@code refreshToken})은 소스코드에 하드코딩하지 않는다.
 * 환경변수({@code WHEREWEGO_DEMO_SEED_*})로 주입하며, 미주입 시 빈 문자열이 된다.</p>
 *
 * @param enabled      데모 시드 활성화 여부. {@code true} 일 때만 {@link DemoSeedRunner} 가 실행된다.
 * @param refreshToken 데모(primary) 계정의 평문 refresh token(env 주입). iOS 데모 로그인이 이 토큰으로
 *                     {@code /auth/refresh} 를 반복 호출한다(§10 데모 회전 예외). 미설정 시 빈 문자열.
 * @param user1        데모 커플의 첫 번째 사용자(primary). refreshToken 의 소유자.
 * @param user2        데모 커플의 두 번째 사용자(partner).
 * @param groupName    데모 커플 그룹 이름.
 */
@ConfigurationProperties(prefix = "wherewego.demo-seed")
public record DemoSeedProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String refreshToken,
        @DefaultValue DemoUser user1,
        @DefaultValue DemoUser user2,
        @DefaultValue("데모 커플") String groupName
) {

    /**
     * 데모 사용자 1명의 식별자/표시 정보.
     *
     * <p>{@code oauthProvider}+{@code oauthId} 는 {@link com.wherewego.domain.user.UserModel} 의
     * 식별자와 1:1 정합하며, {@link DemoSeedRunner} 의 멱등 조회 키이자
     * {@code AuthService} 의 데모 회전 예외 매칭 키로 재사용된다.</p>
     *
     * @param oauthProvider OAuth 공급자(KAKAO/APPLE). 데모 계정 기본 KAKAO.
     * @param oauthId       공급자별 식별자(데모 전용 더미 값). 비어 있으면 해당 사용자 시드를 건너뛴다.
     * @param nickname      표시용 닉네임.
     */
    public record DemoUser(
            @DefaultValue("KAKAO") OauthProvider oauthProvider,
            @DefaultValue("") String oauthId,
            @DefaultValue("데모 사용자") String nickname
    ) {

        /** 식별자가 채워져 있어 시드/매칭 대상으로 유효한지 여부. */
        public boolean isConfigured() {
            return oauthProvider != null && oauthId != null && !oauthId.isBlank();
        }

        /** 이 데모 사용자 식별자가 주어진 (provider, oauthId) 와 일치하는지 — 회전 예외 매칭용. */
        public boolean matches(OauthProvider provider, String id) {
            return isConfigured() && oauthProvider == provider && oauthId.equals(id);
        }
    }

    /**
     * 데모 시드/회전 예외에 필요한 식별값이 갖춰졌는지 판정한다.
     * 두 데모 사용자 식별자가 모두 구성되어야 커플 시드가 가능하다.
     */
    public boolean isConfigured() {
        return user1 != null && user1.isConfigured()
                && user2 != null && user2.isConfigured();
    }

    /**
     * 주어진 (provider, oauthId) 가 데모 계정(user1/user2) 중 하나와 일치하는지 — AuthService 회전 예외 매칭.
     *
     * <p>회전 예외는 데모 시드가 실제로 켜진 경우({@code enabled=true})에만 허용한다. 운영 환경에서
     * {@code enabled=false} 인데 데모 oauthId env 가 주입되더라도 회전 예외가 발동하지 않도록 게이트를 둔다.
     * 또한 {@code isConfigured()} 로 두 데모 식별자가 모두 채워진 경우에만 매칭을 시도하므로,
     * provider/oauthId 가 null 인 사용자에 대해서도 {@link DemoUser#matches} 의 null-safe 비교로 NPE 없이 false 를 반환한다.</p>
     */
    public boolean matchesDemoAccount(OauthProvider provider, String oauthId) {
        return enabled
                && isConfigured()
                && ((user1 != null && user1.matches(provider, oauthId))
                || (user2 != null && user2.matches(provider, oauthId)));
    }
}
