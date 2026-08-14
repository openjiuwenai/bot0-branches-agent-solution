/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api.calltree;

/** 调用树解析/归并的非致命诊断；不代表业务调用失败。 */
public record CallTreeDiagnostic(String code, String message, String artifactId) {
    public static final String AGENT_EVENT_INVALID = "AGENT_EVENT_INVALID";
    public static final String ARTIFACT_ID_CONFLICT = "ARTIFACT_ID_CONFLICT";
    public static final String ILLEGAL_CYCLE = "ILLEGAL_CYCLE";
    public static final String MULTIPLE_PARENTS = "MULTIPLE_PARENTS";
    public static final String RESOURCE_LIMIT = "CALL_TREE_RESOURCE_LIMIT";
    public static final String UNRESOLVED_ORPHAN = "UNRESOLVED_ORPHAN";
}
