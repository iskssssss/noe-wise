package com.skillforge.sandbox.api;

public class SandboxRequestException extends RuntimeException {

    private final String code;

    public SandboxRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
