/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.complexintent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * State — 1:1 with Python {@code ComplexIntentState} / {@code ExecutionStatus}.
 *
 * @since 2026-08-26
 */
public final class ComplexIntentState {
    public static final String START = "START";
    public static final String END = "END";
    public static final String USER_INTERACT = "USER_INTERACT";
    public static final String STATE_KEY = "complex_intent_state";

    private String status = START;
    private Map<String, Object> intentResult = new LinkedHashMap<>();
    private String workflowId;
    private String branchId;
    private String resumeQuery;
    private String interruptChildNodeId;
    private double matchedScore;
    private String matchedLayer;

    public String status() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status == null ? START : status;
    }

    public Map<String, Object> intentResult() {
        return intentResult;
    }

    public void setIntentResult(Map<String, Object> intentResult) {
        this.intentResult = intentResult == null ? new LinkedHashMap<>() : new LinkedHashMap<>(intentResult);
    }

    public String workflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public String branchId() {
        return branchId;
    }

    public void setBranchId(String branchId) {
        this.branchId = branchId;
    }

    public String resumeQuery() {
        return resumeQuery;
    }

    public void setResumeQuery(String resumeQuery) {
        this.resumeQuery = resumeQuery;
    }

    public String interruptChildNodeId() {
        return interruptChildNodeId;
    }

    public void setInterruptChildNodeId(String interruptChildNodeId) {
        this.interruptChildNodeId = interruptChildNodeId;
    }

    public double matchedScore() {
        return matchedScore;
    }

    public void setMatchedScore(double matchedScore) {
        this.matchedScore = matchedScore;
    }

    public String matchedLayer() {
        return matchedLayer;
    }

    public void setMatchedLayer(String matchedLayer) {
        this.matchedLayer = matchedLayer;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("intent_result", intentResult);
        m.put("workflow_id", workflowId);
        m.put("branch_id", branchId);
        m.put("resume_query", resumeQuery);
        m.put("interrupt_child_node_id", interruptChildNodeId);
        m.put("matched_score", matchedScore);
        m.put("matched_layer", matchedLayer);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static ComplexIntentState fromMap(Map<String, Object> raw) {
        ComplexIntentState s = new ComplexIntentState();
        if (raw == null) {
            return s;
        }
        s.setStatus(String.valueOf(raw.getOrDefault("status", START)));
        Object ir = raw.get("intent_result");
        if (ir instanceof Map<?, ?> m) {
            Map<String, Object> intent = new LinkedHashMap<>();
            m.forEach((k, v) -> intent.put(String.valueOf(k), v));
            s.setIntentResult(intent);
        }
        Object wf = raw.get("workflow_id");
        if (wf != null) {
            s.setWorkflowId(String.valueOf(wf));
        }
        Object bid = raw.get("branch_id");
        if (bid != null) {
            s.setBranchId(String.valueOf(bid));
        }
        Object rq = raw.get("resume_query");
        if (rq != null) {
            s.setResumeQuery(String.valueOf(rq));
        }
        Object ic = raw.get("interrupt_child_node_id");
        if (ic != null) {
            s.setInterruptChildNodeId(String.valueOf(ic));
        }
        Object ms = raw.get("matched_score");
        if (ms instanceof Number n) {
            s.setMatchedScore(n.doubleValue());
        }
        Object ml = raw.get("matched_layer");
        if (ml != null) {
            s.setMatchedLayer(String.valueOf(ml));
        }
        return s;
    }

    public ComplexIntentState copy() {
        return fromMap(toMap());
    }
}
