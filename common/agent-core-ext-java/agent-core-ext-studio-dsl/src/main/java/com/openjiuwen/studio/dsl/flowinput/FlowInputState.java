/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowinput;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Python {@code FlowInputState} / status machine.
 *
 * @since 2026-08-26
 */
public final class FlowInputState {
    public static final String START = "start";
    public static final String USER_INTERACT = "user_interact";
    public static final String END = "end";

    private String status;
    private String question;

    public FlowInputState() {
        this(START, "");
    }

    public FlowInputState(String status, String question) {
        this.status = status == null ? START : status;
        this.question = question == null ? "" : question;
    }

    public static FlowInputState deserialize(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return new FlowInputState();
        }
        String status = String.valueOf(raw.getOrDefault("status", START));
        String question = String.valueOf(raw.getOrDefault("question", ""));
        return new FlowInputState(status, question);
    }

    public Map<String, Object> serialize() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("question", question);
        return out;
    }

    public boolean isUndergoingInteraction() {
        return USER_INTERACT.equals(status);
    }

    public boolean isFreshState() {
        return START.equals(status) && (question == null || question.isEmpty());
    }

    public String status() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String question() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question == null ? "" : question;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FlowInputState that)) {
            return false;
        }
        return Objects.equals(status, that.status) && Objects.equals(question, that.question);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, question);
    }
}
