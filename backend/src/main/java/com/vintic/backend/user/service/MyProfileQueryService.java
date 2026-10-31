package com.vintic.backend.user.service;

import com.vintic.backend.common.exception.UserNotFoundException;
import com.vintic.backend.user.dto.MyProfileResponse;
import com.vintic.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// #143: GET /api/me. userId는 컨트롤러의 currentUserId(인증 정보)에서만 온다.
@Service
public class MyProfileQueryService {

    private final UserRepository userRepository;

    public MyProfileQueryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // 인증 시점에 존재가 확인된 사용자다. 그 사이 사라진 경우는 기존 규칙대로 UserNotFoundException(40101).
    @Transactional(readOnly = true)
    public MyProfileResponse getMyProfile(Long userId) {
        return userRepository.findById(userId)
                .map(MyProfileResponse::from)
                .orElseThrow(() -> new UserNotFoundException("존재하지 않는 사용자입니다. userId: " + userId));
    }
}
