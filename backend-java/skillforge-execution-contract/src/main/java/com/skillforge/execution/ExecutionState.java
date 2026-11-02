package com.skillforge.execution;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonCreator;

public enum ExecutionState {
    QUEUED("queued", false),
    RUNNING("running", false),
    COMPLETED("completed", true),
    FAILED("failed", true),
    CANCELLED("cancelled", true),
    TIMED_OUT("timed_out", true),
    UNKNOWN("unknown", true);

    private final String value;
    private final boolean stopped;

    ExecutionState(String value, boolean stopped) {
        this.value = value;
        this.stopped = stopped;
    }

    @JsonCreator
    public static ExecutionState fromValue(String value) {
        for(var state:values())if(state.value.equals(value))return state;
        throw new IllegalArgumentException("execution_state_invalid");
    }

    @JsonValue
    public String value() {
        return value;
    }

    public boolean stopped() {
        return stopped;
    }
}
