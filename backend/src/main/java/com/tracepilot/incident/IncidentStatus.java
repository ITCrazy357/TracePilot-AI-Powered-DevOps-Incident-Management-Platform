package com.tracepilot.incident;

public enum IncidentStatus {
    OPEN, INVESTIGATING, RESOLVED, IGNORED;

    public boolean canChangeTo(IncidentStatus next) {
        if (this == next) {
            return true;
        }
        return switch (this) {
            case OPEN -> next == INVESTIGATING || next == IGNORED;
            case INVESTIGATING -> next == OPEN || next == RESOLVED || next == IGNORED;
            case RESOLVED -> next == INVESTIGATING;
            case IGNORED -> next == OPEN;
        };
    }
}
