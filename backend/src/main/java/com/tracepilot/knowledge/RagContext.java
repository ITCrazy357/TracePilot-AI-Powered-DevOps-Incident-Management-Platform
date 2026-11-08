package com.tracepilot.knowledge;

import java.util.List;
import java.util.UUID;

public record RagContext(UUID workspaceId, String log, String input, List<KnowledgeDtos.Chunk> knowledge) {
}
