package com.techhouse.identity.web.dto;

import java.util.List;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        UserDto user,
        List<StoreDto> stores
) {}
