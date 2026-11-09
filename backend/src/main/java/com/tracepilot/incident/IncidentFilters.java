package com.tracepilot.incident;

import java.util.UUID;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record IncidentFilters(@Size(max = 200) String q, IncidentStatus status, UUID repositoryId,
                              UUID assignedTo, @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String severity) {
}
