package com.tracepilot.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RateLimiter {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final RateLimitProperties properties;
    private final SecretKeySpec key;

    public RateLimiter(JdbcTemplate jdbc, Clock clock, RateLimitProperties properties, AuthProperties auth) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.key = new SecretKeySpec(auth.signingSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    // Rejected business/auth transactions must not refund attempts; the bucket is shared across app instances.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void require(String scope, String identity, int limit, int windowSeconds) {
        if (!properties.enabled()) {
            return;
        }
        var now = clock.instant();
        long start = Math.floorDiv(now.getEpochSecond(), windowSeconds) * windowSeconds;
        var end = java.time.Instant.ofEpochSecond(start + windowSeconds);
        var accepted = jdbc.queryForList("""
            INSERT INTO rate_limit_buckets (scope, key_hash, bucket_start, expires_at, request_count)
            VALUES (?, ?, ?, ?, 1)
            ON CONFLICT (scope, key_hash, bucket_start) DO UPDATE
                SET request_count = rate_limit_buckets.request_count + 1
                WHERE rate_limit_buckets.request_count < ?
            RETURNING request_count
            """, Integer.class, scope, hash(scope + ":" + identity),
            Timestamp.from(java.time.Instant.ofEpochSecond(start)), Timestamp.from(end), limit);
        if (accepted.isEmpty()) {
            throw new RateLimitExceeded(end.getEpochSecond() - now.getEpochSecond());
        }
    }

    @Scheduled(fixedDelayString = "${tracepilot.rate-limit.cleanup-delay-ms:60000}")
    public void cleanup() {
        if (properties.enabled()) {
            jdbc.update("""
                DELETE FROM rate_limit_buckets WHERE ctid IN
                    (SELECT ctid FROM rate_limit_buckets WHERE expires_at <= ?
                     ORDER BY expires_at LIMIT 1000)
                """, Timestamp.from(clock.instant()));
        }
    }

    private String hash(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Rate limit hashing is unavailable.");
        }
    }
}
