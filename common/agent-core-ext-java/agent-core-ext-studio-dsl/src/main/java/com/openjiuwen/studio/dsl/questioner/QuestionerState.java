/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.questioner;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Persisted questioner state (Python {@code QuestionerState} subset).
 *
 * @since 2026-08-25
 */
public final class QuestionerState {
    public static final String KEY = "questioner_state";
    public static final String START = "start";
    public static final String USER_INTERACT = "user_interact";
    public static final String END = "end";

    private String status = START;
    private String question = "";
    private int responseNum;
    private final Map<String, Object> extractedFields = new LinkedHashMap<>();
    private final Set<String> fieldsCheckFailed = new HashSet<>();
    private boolean needUserConfirm;
    private boolean userBreak;

    /**
     * fromMap.
     *
     * @param raw raw
     * @return result
     */
    @SuppressWarnings("unchecked")
    public static QuestionerState fromMap(Map<String, Object> raw) {
        QuestionerState s = new QuestionerState();
        if (raw == null) {
            return s;
        }
        Object st = raw.get("status");
        if (st != null) {
            s.status = String.valueOf(st);
        }
        Object q = raw.get("question");
        if (q != null) {
            s.question = String.valueOf(q);
        }
        Object rn = raw.get("response_num");
        if (rn instanceof Number n) {
            s.responseNum = n.intValue();
        }
        Object fields = raw.get("extracted_fields");
        if (fields instanceof Map<?, ?> m) {
            m.forEach((k, v) -> s.extractedFields.put(String.valueOf(k), v));
        }
        Object failed = raw.get("fields_check_failed");
        if (failed instanceof Iterable<?> it) {
            for (Object o : it) {
                s.fieldsCheckFailed.add(String.valueOf(o));
            }
        }
        s.needUserConfirm = raw.get("need_user_confirm") instanceof Boolean b && b;
        s.userBreak = raw.get("user_break") instanceof Boolean ub && ub;
        return s;
    }

    /**
     * toMap.
     *
     * @return result
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("question", question);
        m.put("response_num", responseNum);
        m.put("extracted_fields", new LinkedHashMap<>(extractedFields));
        m.put("fields_check_failed", Set.copyOf(fieldsCheckFailed));
        m.put("need_user_confirm", needUserConfirm);
        m.put("user_break", userBreak);
        return m;
    }

    /** @return undergoing interaction */
    public boolean isUndergoingInteraction() {
        return USER_INTERACT.equals(status);
    }

    /** @return status */
    public String status() {
        return status;
    }

    /**
     * setStatus.
     *
     * @param status status
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /** @return question */
    public String question() {
        return question;
    }

    /**
     * setQuestion.
     *
     * @param question question
     */
    public void setQuestion(String question) {
        this.question = question == null ? "" : question;
    }

    /** @return responseNum */
    public int responseNum() {
        return responseNum;
    }

    /** incrementResponseNum. */
    public void incrementResponseNum() {
        responseNum++;
    }

    /** @return extractedFields */
    public Map<String, Object> extractedFields() {
        return extractedFields;
    }

    /** @return fieldsCheckFailed */
    public Set<String> fieldsCheckFailed() {
        return fieldsCheckFailed;
    }

    /** @return needUserConfirm */
    public boolean needUserConfirm() {
        return needUserConfirm;
    }

    /**
     * setNeedUserConfirm.
     *
     * @param needUserConfirm needUserConfirm
     */
    public void setNeedUserConfirm(boolean needUserConfirm) {
        this.needUserConfirm = needUserConfirm;
    }

    /** @return userBreak */
    public boolean userBreak() {
        return userBreak;
    }

    /**
     * setUserBreak.
     *
     * @param userBreak userBreak
     */
    public void setUserBreak(boolean userBreak) {
        this.userBreak = userBreak;
    }
}
