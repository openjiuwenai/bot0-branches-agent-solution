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

    /**
     * KEY.
     *
     * @since 0.1.0
     */

    public static final String KEY = "questioner_state";

    /**
     * START.
     *
     * @since 0.1.0
     */

    public static final String START = "start";

    /**
     * USER_INTERACT.
     *
     * @since 0.1.0
     */

    public static final String USER_INTERACT = "user_interact";

    /**
     * END.
     *
     * @since 0.1.0
     */

    public static final String END = "end";

    private String status = START;
    private String question = "";
    private String userResponse = "";
    private int responseNum;
    private final Map<String, Object> extractedFields = new LinkedHashMap<>();
    private final Set<String> fieldsCheckFailed = new HashSet<>();
    private boolean needUserConfirm = true;
    private boolean userBreak;
    private final Map<String, Object> inputs = new LinkedHashMap<>();
    private final Map<String, Object> reflectionMap = new LinkedHashMap<>();

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
        Object fields = raw.get("extracted_key_fields");
        if (!(fields instanceof Map<?, ?>)) {
            fields = raw.get("extracted_fields");
        }
        if (fields instanceof Map<?, ?> m) {
            m.forEach((k, v) -> s.extractedFields.put(String.valueOf(k), v));
        }
        Object failed = raw.get("fields_check_failed");
        if (failed instanceof Iterable<?> it) {
            for (Object o : it) {
                s.fieldsCheckFailed.add(String.valueOf(o));
            }
        }
        Object reflection = raw.get("reflection_map");
        if (reflection instanceof Map<?, ?> rm) {
            rm.forEach((k, v) -> s.reflectionMap.put(String.valueOf(k), v));
        }
        Object ur = raw.get("user_response");
        if (ur != null) {
            s.userResponse = String.valueOf(ur);
        }
        Object nuc = raw.get("need_user_confirm");
        s.needUserConfirm = nuc instanceof Boolean b ? b : true;
        s.userBreak = raw.get("user_break") instanceof Boolean ub && ub;
        Object ins = raw.get("inputs");
        if (ins instanceof Map<?, ?> im) {
            im.forEach((k, v) -> s.inputs.put(String.valueOf(k), v));
        }
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
        m.put("extracted_key_fields", new LinkedHashMap<>(extractedFields));
        m.put("user_response", userResponse == null ? "" : userResponse);
        m.put("fields_check_failed", Set.copyOf(fieldsCheckFailed));
        m.put("need_user_confirm", needUserConfirm);
        m.put("user_break", userBreak);
        m.put("inputs", new LinkedHashMap<>(inputs));
        m.put("reflection_map", new LinkedHashMap<>(reflectionMap));
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

    /**
     * incrementResponseNum.
     *
     * @since 0.1.0
     *
     */

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

    /** @return userResponse (Python {@code user_response}) */
    public String userResponse() {
        return userResponse;
    }

    /**
     * setUserResponse.
     *
     * @param userResponse userResponse
     */

    public void setUserResponse(String userResponse) {
        this.userResponse = userResponse == null ? "" : userResponse;
    }

    /** @return reflectionMap (Python {@code reflection_map}) */
    public Map<String, Object> reflectionMap() {
        return reflectionMap;
    }

    /** @return inputs snapshot */
    public Map<String, Object> inputs() {
        return inputs;
    }

    /**
     * setInputs.
     *
     * @param raw raw inputs
     */

    public void setInputs(Map<String, Object> raw) {
        inputs.clear();
        if (raw != null) {
            inputs.putAll(raw);
        }
    }
}
