package com.tracepilot.analysis;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AnalysisRecovery {
    private final AnalysisRepository analysisRepository;
    private final AnalysisStore analysisStore;

    public AnalysisRecovery(AnalysisRepository analysisRepository, AnalysisStore analysisStore) {
        this.analysisRepository = analysisRepository;
        this.analysisStore = analysisStore;
    }

    @Scheduled(fixedDelayString = "${tracepilot.ai.recovery-delay-ms:1000}")
    public void recover() {
        for (var analysisId : analysisRepository.unfinished()) {
            analysisStore.reconcileFailed(analysisId);
        }
    }
}
