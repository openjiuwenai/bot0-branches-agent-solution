/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.internal;

import com.huawei.ascend.client.tool.spi.LocalTool;
import com.huawei.ascend.client.tool.spi.LocalToolDescriptor;
import com.huawei.ascend.client.tool.spi.LocalToolRegistry;
import com.huawei.ascend.client.tool.spi.ToolExposurePolicy;
import com.huawei.ascend.client.tool.spi.ToolView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link LocalToolRegistry} 的默认实现，以 {@code toolId} 为唯一键。线程安全。
 *
 * @since 2026-07-27
 */
public final class DefaultToolRegistry implements LocalToolRegistry {
    private final ConcurrentMap<String, LocalTool.Registered> byId = new ConcurrentHashMap<>();

    @Override
    public Registration register(LocalTool.Registered registered) {
        Objects.requireNonNull(registered, "registered");
        String toolId = registered.descriptor().toolId();
        byId.put(toolId, registered);
        return new Registration() {
            @Override
            public String toolId() {
                return toolId;
            }

            @Override
            public void close() {
                unregister(toolId);
            }
        };
    }

    @Override
    public void unregister(String toolId) {
        byId.remove(toolId);
    }

    @Override
    public Optional<LocalTool.Registered> find(String toolId) {
        return Optional.ofNullable(byId.get(toolId));
    }

    @Override
    public List<LocalToolDescriptor> descriptors() {
        List<LocalToolDescriptor> out = new ArrayList<>();
        for (LocalTool.Registered r : byId.values()) {
            out.add(r.descriptor());
        }
        return List.copyOf(out);
    }

    @Override
    public ToolView toolView(ToolExposurePolicy effectivePolicy) {
        ToolExposurePolicy policy = (effectivePolicy != null) ? effectivePolicy : ToolExposurePolicy.none();
        List<LocalToolDescriptor> visible = new ArrayList<>();
        for (LocalTool.Registered r : byId.values()) {
            if (policy.isExposed(r.descriptor().toolId())) {
                visible.add(r.descriptor());
            }
        }
        return new ToolView(visible);
    }
}
