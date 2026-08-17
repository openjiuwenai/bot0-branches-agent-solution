/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api.calltree;

/**
 * 调用树解析/归并的非致命诊断；不代表业务调用失败。
 *
 * @since 2026-07-27
 */
public record CallTreeDiagnostic(String code, String message, String artifactId) {
    /** 无效的代理事件。 */
    public static final String AGENT_EVENT_INVALID = "AGENT_EVENT_INVALID";

    /** 工件ID冲突。 */
    public static final String ARTIFACT_ID_CONFLICT = "ARTIFACT_ID_CONFLICT";

    /** 非法循环。 */
    public static final String ILLEGAL_CYCLE = "ILLEGAL_CYCLE";

    /** 多父节点。 */
    public static final String MULTIPLE_PARENTS = "MULTIPLE_PARENTS";

    /** 资源限制。 */
    public static final String RESOURCE_LIMIT = "CALL_TREE_RESOURCE_LIMIT";

    /** 未解决的孤儿节点。 */
    public static final String UNRESOLVED_ORPHAN = "UNRESOLVED_ORPHAN";
}
