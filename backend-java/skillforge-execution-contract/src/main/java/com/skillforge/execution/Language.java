package com.skillforge.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Language {
    PYTHON("python"),
    NODE("node"),
    SHELL("shell");

    private final String value;

    Language(String value) {
        this.value = value;
    }

    @JsonCreator
    public static Language fromValue(String value) {
        for (Language language : values()) {
            if (language.value.equals(value)) {
                return language;
            }
        }
        throw new IllegalArgumentException("不支持的执行语言: " + value);
    }

    @JsonValue
    public String value() {
        return value;
    }
}
