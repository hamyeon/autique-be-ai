package com.vintic.backend.user.dto;

import com.vintic.backend.user.domain.User;

// #143: GET /api/me. nickname은 항상 값이 있다(DB NOT NULL - Kakao가 닉네임을 주지 않으면 가입 시
// "회원-xxxxxxxx"로 채운다). email은 null일 수 있다(Kakao 이메일 미동의·이메일 없는 계정).
public record MyProfileResponse(
        Long userId,
        String nickname,
        String email
) {
    public static MyProfileResponse from(User user) {
        return new MyProfileResponse(user.getId(), user.getNickname(), user.getEmail());
    }
}
