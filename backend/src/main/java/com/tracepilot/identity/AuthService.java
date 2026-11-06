package com.tracepilot.identity;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.tracepilot.identity.AuthDtos.LoginRequest;
import com.tracepilot.identity.AuthDtos.RegisterRequest;
import com.tracepilot.identity.AuthDtos.TokenResponse;
import com.tracepilot.identity.AuthDtos.UserResponse;

@Service
public class AuthService {
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder, TokenService tokenService, Clock clock) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        checkPasswordBytes(request.password());
        var email = normalizeEmail(request.email());
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered.");
        }
        var user = new User(email, passwordEncoder.encode(request.password()),
            request.displayName().strip(), clock.instant());
        return UserResponse.from(userRepository.saveAndFlush(user));
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        checkPasswordBytes(request.password());
        var user = userRepository.findByEmailIgnoreCase(normalizeEmail(request.email()));
        // Unknown accounts still perform BCrypt work, like accounts with a wrong password.
        var passwordHash = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(request.password(), passwordHash);
        if (user.isEmpty() || !matches) {
            throw unauthorized();
        }
        return issueTokens(user.orElseThrow());
    }

    @Transactional
    public TokenResponse refresh(String rawToken) {
        // The row lock makes simultaneous refresh requests single-use as well.
        var token = refreshTokenRepository.findForUpdate(TokenService.hash(rawToken))
            .orElseThrow(AuthService::unauthorized);
        var now = clock.instant();
        if (!token.isUsable(now)) {
            throw unauthorized();
        }
        token.revoke(now);
        var user = userRepository.findById(token.getUserId()).orElseThrow(AuthService::unauthorized);
        return issueTokens(user);
    }

    @Transactional
    public void logout(String rawToken) {
        refreshTokenRepository.findForUpdate(TokenService.hash(rawToken)).ifPresent(token -> {
            if (token.isUsable(clock.instant())) {
                token.revoke(clock.instant());
            }
        });
    }

    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return UserResponse.from(userRepository.findById(userId).orElseThrow(AuthService::unauthorized));
    }

    private TokenResponse issueTokens(User user) {
        var refresh = tokenService.newRefreshToken();
        var now = clock.instant();
        refreshTokenRepository.save(new RefreshToken(user.getId(), TokenService.hash(refresh),
            now, now.plus(TokenService.REFRESH_TTL)));
        return new TokenResponse(tokenService.accessToken(user.getId()), "Bearer",
            TokenService.ACCESS_TTL.toSeconds(), refresh, UserResponse.from(user));
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static void checkPasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds 72 UTF-8 bytes.");
        }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials or refresh token.");
    }
}
