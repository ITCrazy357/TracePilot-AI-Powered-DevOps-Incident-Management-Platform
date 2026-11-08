package com.tracepilot.notification;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class NotificationStream {
    private static final int MAX_CONNECTIONS = 100;
    private static final int MAX_CONNECTIONS_PER_USER = 3;
    private final ConcurrentHashMap<UUID, Connection> connections = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(100), Thread.ofPlatform().daemon().name("notification-stream-", 0).factory());
    private final NotificationService notificationService;
    private final Clock clock;

    public NotificationStream(NotificationService notificationService, Clock clock) {
        this.notificationService = notificationService;
        this.clock = clock;
    }

    public synchronized SseEmitter open(UUID userId, UUID workspaceId, Instant expiresAt, Optional<UUID> lastEventId) {
        long cursor = notificationService.cursor(userId, workspaceId, lastEventId);
        if (expiresAt == null || !expiresAt.isAfter(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        long userConnections = connections.values().stream().filter(item -> item.userId.equals(userId)).count();
        if (connections.size() >= MAX_CONNECTIONS || userConnections >= MAX_CONNECTIONS_PER_USER) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many notification connections.");
        }
        long timeout = Math.min(Duration.ofMinutes(3).toMillis(),
            Duration.between(clock.instant(), expiresAt).toMillis());
        var emitter = new SseEmitter(Math.max(1, timeout));
        var id = UUID.randomUUID();
        var connection = new Connection(userId, workspaceId, expiresAt, cursor, emitter);
        connections.put(id, connection);
        emitter.onCompletion(() -> connections.remove(id));
        emitter.onTimeout(() -> close(id, connection));
        emitter.onError(error -> connections.remove(id));
        return emitter;
    }

    @Scheduled(fixedDelayString = "${tracepilot.notifications.poll-delay-ms:1000}")
    public void poll() {
        connections.forEach((id, connection) -> {
            if (!connection.busy.compareAndSet(false, true)) {
                return;
            }
            try {
                executor.execute(() -> send(id, connection));
            } catch (RejectedExecutionException exception) {
                close(id, connection);
            }
        });
    }

    private void send(UUID id, Connection connection) {
        try {
            if (!connection.expiresAt.isAfter(clock.instant())) {
                close(id, connection);
                return;
            }
            var deliveries = notificationService.after(connection.userId, connection.workspaceId, connection.cursor);
            for (var delivery : deliveries) {
                connection.emitter.send(SseEmitter.event().id(delivery.notification().id().toString())
                    .name("notification").data(delivery.notification()));
                connection.cursor = delivery.sequence();
            }
            connection.emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (IOException | IllegalStateException | ResponseStatusException exception) {
            close(id, connection);
        } finally {
            connection.busy.set(false);
        }
    }

    private void close(UUID id, Connection connection) {
        connections.remove(id, connection);
        connection.emitter.complete();
    }

    @PreDestroy
    public void stop() {
        connections.forEach(this::close);
        executor.shutdownNow();
    }

    private static final class Connection {
        private final UUID userId;
        private final UUID workspaceId;
        private final Instant expiresAt;
        private final SseEmitter emitter;
        private final AtomicBoolean busy = new AtomicBoolean();
        private volatile long cursor;

        private Connection(UUID userId, UUID workspaceId, Instant expiresAt, long cursor, SseEmitter emitter) {
            this.userId = userId;
            this.workspaceId = workspaceId;
            this.expiresAt = expiresAt;
            this.cursor = cursor;
            this.emitter = emitter;
        }
    }
}
