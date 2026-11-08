package com.tracepilot.quality;

public enum FeedbackReason {
    CORRECT_DIAGNOSIS,
    ACTIONABLE_FIX,
    USEFUL_EVIDENCE,
    INCORRECT_DIAGNOSIS,
    INSUFFICIENT_EVIDENCE,
    UNSAFE_SUGGESTION,
    NOT_A_REAL_INCIDENT,
    OTHER;

    public boolean supports(boolean helpful) {
        return switch (this) {
            case CORRECT_DIAGNOSIS, ACTIONABLE_FIX, USEFUL_EVIDENCE -> helpful;
            case INCORRECT_DIAGNOSIS, INSUFFICIENT_EVIDENCE, UNSAFE_SUGGESTION, NOT_A_REAL_INCIDENT -> !helpful;
            case OTHER -> true;
        };
    }
}
