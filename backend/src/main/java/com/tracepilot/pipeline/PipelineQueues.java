package com.tracepilot.pipeline;

public final class PipelineQueues {
    public static final String EXCHANGE = "tracepilot.pipeline";
    public static final String SYNC_QUEUE = "tracepilot.pipeline.sync";
    public static final String DEAD_QUEUE = "tracepilot.pipeline.sync.dead";
    public static final String SYNC_KEY = "workflow.sync";
    public static final String LOGS_QUEUE = "tracepilot.incident.logs";
    public static final String LOGS_KEY = "incident.logs";
    public static final String AI_QUEUE = "tracepilot.analysis";
    public static final String AI_KEY = "incident.analysis";
    public static final String DEAD_KEY = "workflow.sync.dead";

    private PipelineQueues() {
    }
}
