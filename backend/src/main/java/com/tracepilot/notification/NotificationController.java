package com.tracepilot.notification;

import java.util.Optional;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/notifications")
public class NotificationController {
    private final NotificationService notificationService;
    private final NotificationStream notificationStream;

    public NotificationController(NotificationService notificationService, NotificationStream notificationStream) {
        this.notificationService = notificationService;
        this.notificationStream = notificationStream;
    }

    @GetMapping
    public NotificationDtos.NotificationPage list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                                  @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
                                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return notificationService.list(UUID.fromString(jwt.getSubject()), workspaceId, page, size);
    }

    @PatchMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId, @PathVariable UUID notificationId) {
        notificationService.markRead(UUID.fromString(jwt.getSubject()), workspaceId, notificationId);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID workspaceId,
                                             @RequestHeader(name = "Last-Event-ID") Optional<UUID> lastEventId) {
        var emitter = notificationStream.open(UUID.fromString(jwt.getSubject()), workspaceId,
            jwt.getExpiresAt(), lastEventId);
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Accel-Buffering", "no").body(emitter);
    }
}
