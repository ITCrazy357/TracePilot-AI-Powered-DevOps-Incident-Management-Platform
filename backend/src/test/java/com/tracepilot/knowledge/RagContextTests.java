package com.tracepilot.knowledge;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.tracepilot.analysis.AiProperties;
import com.tracepilot.analysis.AnalysisDtos;
import com.tracepilot.analysis.AnalysisInput;
import com.tracepilot.analysis.AnalysisRepository;
import com.tracepilot.incident.LogRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagContextTests {
    @Test
    void aSmallPromptBudgetFallsBackToLogsInsteadOfCuttingOutsideTheInput() {
        var analysisId = UUID.randomUUID();
        var workspaceId = UUID.randomUUID();
        var ai = new AiProperties(true, "fixture-key", "fixture-model", URI.create("https://api.openai.com/v1"),
            Duration.ofSeconds(1), 100, 2048, 50, null, null);
        var repository = mock(AnalysisRepository.class);
        var search = mock(KnowledgeSearch.class);
        var work = mock(AnalysisDtos.AnalysisWork.class);
        when(work.workspaceId()).thenReturn(workspaceId);
        when(repository.find(analysisId)).thenReturn(Optional.of(work));
        when(search.retrieve(any(), any(), anyInt(), any(), any(), anyInt())).thenReturn(List.of(
            new KnowledgeDtos.Chunk(UUID.randomUUID(), UUID.randomUUID(), 0, "A reviewed fix", "a".repeat(64), 1)));
        var text = new KnowledgeText(new LogRedactor(new MockEnvironment()), ai);
        var service = new RagService(repository, search, mock(KnowledgeRepository.class), text,
            new RagProperties(true, RagProperties.MODEL, 1536, 3, 0.35, null), ai);
        var prepared = new AnalysisInput.PreparedInput("Useful log content", "b".repeat(64));
        var context = service.prepare(analysisId, 1, prepared);
        assertThat(context.input()).isEqualTo(prepared.content()).hasSizeLessThanOrEqualTo(100);
        assertThat(context.knowledge()).isEmpty();
    }
}
