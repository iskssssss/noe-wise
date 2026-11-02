package com.skillforge.sandbox.api;

public class SandboxConflictException extends RuntimeException {

    private final String code;

    public SandboxConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
