package com.tracepilot.analysis;

public class AnalysisFailure extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public AnalysisFailure(String code, boolean retryable) {
        super(code);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
