package com.wherewego.domain.auth;

import com.wherewego.domain.user.OauthProvider;
import com.wherewego.domain.user.UserModel;

/**
 * P1: 네이티브 로그인 find-or-create 입력 캐리어.
 * 공급자별 정적 팩토리({@link #kakao}, {@link #apple})로 생성하고,
 * 신규 계정 생성 시 {@link #toNewUser()} 로 {@link UserModel} 을 만든다.
 */
public record NativeLoginCommand(
        OauthProvider provider,
        String oauthId,
        String nickname,
        String profileImageUrl,
        String email
) {
    public static NativeLoginCommand kakao(Long kakaoUserId, String nickname, String profileImageUrl) {
        return new NativeLoginCommand(OauthProvider.KAKAO, String.valueOf(kakaoUserId), nickname, profileImageUrl, null);
    }

    public static NativeLoginCommand apple(String sub, String nickname, String email) {
        return new NativeLoginCommand(OauthProvider.APPLE, sub, nickname, null, email);
    }

    /**
     * 신규 계정 생성. provider 분기:
     * KAKAO → {@link UserModel#create} (kakao_user_id 채움), APPLE → {@link UserModel#createOauth}.
     */
    public UserModel toNewUser() {
        if (provider == OauthProvider.KAKAO) {
            return UserModel.create(Long.valueOf(oauthId), nickname, profileImageUrl);
        }
        return UserModel.createOauth(provider, oauthId, nickname, profileImageUrl, email);
    }
}
