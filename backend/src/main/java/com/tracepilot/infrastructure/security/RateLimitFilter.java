package com.tracepilot.infrastructure.security;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimiter limiter;
    private final RateLimitProperties properties;
    private final ObjectMapper mapper;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties properties, ObjectMapper mapper) {
        this.limiter = limiter;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        try {
            limit(request);
        } catch (RateLimitExceeded failure) {
            response.setHeader("Retry-After", Long.toString(failure.retryAfterSeconds()));
            reject(request, response, 429, "Too many requests. Retry after the indicated delay.");
            return;
        } catch (DataAccessException | TransactionException failure) {
            reject(request, response, 503, "Rate limiting is temporarily unavailable.");
            return;
        }
        chain.doFilter(request, response);
    }

    private void limit(HttpServletRequest request) {
        if (!properties.enabled()) {
            return;
        }
        var path = request.getRequestURI();
        if ("POST".equals(request.getMethod()) && path.startsWith("/api/v1/auth/")) {
            var session = path.equals("/api/v1/auth/refresh") || path.equals("/api/v1/auth/logout");
            // Forwarded headers are untrusted. A BFF shares its peer-IP bucket; account limits remain separate.
            limiter.require(session ? "AUTH_SESSION" : "AUTH_ENTRY", request.getRemoteAddr(),
                session ? properties.sessionPerMinute() : properties.authPerMinute(), 60);
            return;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!path.startsWith("/api/v1/") || authentication == null
            || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return;
        }
        boolean read = "GET".equals(request.getMethod());
        limiter.require(read ? "API_READ" : "API_WRITE", jwt.getSubject(),
            read ? properties.readsPerMinute() : properties.writesPerMinute(), 60);
        if ("POST".equals(request.getMethod()) && (path.endsWith("/analyses")
            || path.endsWith("/search") || path.endsWith("/approve") || path.endsWith("/sync")
            || path.endsWith("/retry"))) {
            limiter.require("API_EXPENSIVE", jwt.getSubject(), properties.expensivePerMinute(), 60);
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, int status, String detail)
        throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), Map.of("status", status, "detail", detail,
            "requestId", String.valueOf(request.getAttribute("requestId"))));
    }
}
