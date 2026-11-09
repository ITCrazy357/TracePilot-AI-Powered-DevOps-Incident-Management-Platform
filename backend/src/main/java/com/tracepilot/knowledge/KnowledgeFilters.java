package com.tracepilot.knowledge;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record KnowledgeFilters(@Size(max = 200) String q,
                               @Pattern(regexp = "DRAFT|APPROVED|ARCHIVED") String status) {
}
