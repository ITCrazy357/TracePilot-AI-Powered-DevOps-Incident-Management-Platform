package com.tracepilot.identity;

import java.util.UUID;
import java.util.Locale;
import com.tracepilot.infrastructure.security.RateLimiter;
import com.tracepilot.infrastructure.security.RateLimitProperties;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tracepilot.identity.AuthDtos.LoginRequest;
import com.tracepilot.identity.AuthDtos.RefreshRequest;
import com.tracepilot.identity.AuthDtos.RegisterRequest;
import com.tracepilot.identity.AuthDtos.TokenResponse;
import com.tracepilot.identity.AuthDtos.UserResponse;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimits;

    public AuthController(AuthService authService, RateLimiter rateLimiter, RateLimitProperties rateLimits) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.rateLimits = rateLimits;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        rateLimiter.require("LOGIN_ACCOUNT", request.email().strip().toLowerCase(Locale.ROOT),
            rateLimits.loginPerAccount(), 300);
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return authService.me(UUID.fromString(jwt.getSubject()));
    }
}
