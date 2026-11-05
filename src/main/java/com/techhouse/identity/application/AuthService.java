package com.techhouse.identity.application;

import com.techhouse.identity.domain.StoreMember;
import com.techhouse.identity.domain.User;
import com.techhouse.identity.persistence.StoreMemberRepository;
import com.techhouse.identity.persistence.UserRepository;
import com.techhouse.identity.web.dto.AuthResponse;
import com.techhouse.identity.web.dto.StoreDto;
import com.techhouse.identity.web.dto.UserDto;
import com.techhouse.shared.exception.BusinessException;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final StoreMemberRepository storeMemberRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public AuthResponse login(String email, String password) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("Invalid email or password"));

        if (!user.isActive()) {
            throw new BusinessException("Account is disabled");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException("Invalid email or password");
        }

        return buildAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse refreshToken(String refreshToken) {
        Claims claims = tokenService.validateToken(refreshToken);

        String type = claims.get("type", String.class);
        if (!"refresh".equals(type)) {
            throw new BusinessException("Invalid token type");
        }

        UUID userId = UUID.fromString(claims.getSubject());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found"));

        if (!user.isActive()) {
            throw new BusinessException("Account is disabled");
        }

        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        List<StoreMember> memberships = storeMemberRepository.findByUserId(user.getId());

        List<UUID> storeIds = memberships.stream()
                .map(m -> m.getStore().getId())
                .toList();

        List<StoreDto> stores = memberships.stream()
                .map(m -> new StoreDto(
                        m.getStore().getId(),
                        m.getStore().getName(),
                        m.getStore().getCountryCode(),
                        m.getStore().getLocalCurrency()))
                .toList();

        String accessToken = tokenService.generateAccessToken(user, storeIds);
        String newRefreshToken = tokenService.generateRefreshToken(user);

        UserDto userDto = new UserDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole());

        return new AuthResponse(accessToken, newRefreshToken, userDto, stores);
    }
}



