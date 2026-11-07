package com.techhouse.identity.config;

import com.techhouse.shared.exception.BusinessException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.UUID;

@Component
public class CurrentStoreResolver implements HandlerMethodArgumentResolver {

    private static final String STORE_HEADER = "X-Store-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentStore.class)
                && parameter.getParameterType().equals(UUID.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {

        String storeIdHeader = webRequest.getHeader(STORE_HEADER);
        if (storeIdHeader == null || storeIdHeader.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "STORE_HEADER_MISSING", "Missing required header: " + STORE_HEADER);
        }

        UUID storeId;
        try {
            storeId = UUID.fromString(storeIdHeader);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "STORE_HEADER_MISSING", "Invalid store ID format");
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getDetails() instanceof JwtAuthDetails details)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }

        if (!details.storeIds().contains(storeId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "STORE_ACCESS_DENIED", "Access denied to store: " + storeId);
        }

        return storeId;
    }
}
