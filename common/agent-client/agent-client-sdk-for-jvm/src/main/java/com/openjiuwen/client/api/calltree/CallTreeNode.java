/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api.calltree;

import java.util.List;
import java.util.Objects;

/** 调用树节点。 */
public record CallTreeNode(NodeKey key, String delegationIntent, String state,
        List<ArtifactSnapshot> artifacts, List<CallTreeNode> children) {
    public CallTreeNode {
        Objects.requireNonNull(key, "key");
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        children = children == null ? List.of() : List.copyOf(children);
    }
}
