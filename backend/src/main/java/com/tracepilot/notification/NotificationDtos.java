package com.tracepilot.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NotificationDtos {
    private NotificationDtos() {
    }

    public record NotificationResponse(UUID id, UUID incidentId, String type, String title,
                                       Instant readAt, Instant createdAt) {
    }

    public record NotificationPage(List<NotificationResponse> items, int page, int size, long total) {
    }

    public record Delivery(long sequence, NotificationResponse notification) {
    }
}
