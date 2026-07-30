/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.verify;

import com.huawei.ascend.client.api.AgentClient;
import com.huawei.ascend.client.tool.spi.LocalTool;
import com.huawei.ascend.client.tool.spi.LocalToolDescriptor;
import com.huawei.ascend.client.tool.spi.ToolExecutionRecord;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 验证用的本地工具样例（<b>业务代码，非 SDK 交付</b>）。
 *
 * <p>演示三类工具：只读观察类（readPage/ping）与有副作用的动作类（submitOrder，需审批）。
 * 每个工具维护执行计数，供自校验断言"最多执行一次"。
 *
 */
final class DemoTools {
    static final String READ_PAGE = "readPage";
    static final String SUBMIT_ORDER = "submitOrder";
    static final String PING = "ping";

    final AtomicInteger readPageCount = new AtomicInteger();
    final AtomicInteger submitOrderCount = new AtomicInteger();
    final AtomicInteger pingCount = new AtomicInteger();

    /**
     * 清零全部执行计数，使各场景之间互不影响、可单独重跑。
     */
    void resetCounters() {
        readPageCount.set(0);
        submitOrderCount.set(0);
        pingCount.set(0);
    }

    /**
     * 把三个样例工具注册到客户端。
     *
     * @param client AgentClient 实例
     */
    void registerInto(AgentClient client) {
        registerReadPage(client);
        registerSubmitOrder(client);
        registerPing(client);
    }

    private void registerReadPage(AgentClient client) {
        client.tools().register(LocalTool.of(
                LocalToolDescriptor.builder(READ_PAGE)
                        .displayName("Read current page")
                        .description("Return the content of the page identified by pageId")
                        .sideEffect(LocalToolDescriptor.SideEffect.OBSERVATION)
                        .requiredArguments("pageId")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"pageId\":{\"type\":\"string\"}},"
                                + "\"required\":[\"pageId\"]}")
                        .build(),
                (invocation, ctx) -> {
                    readPageCount.incrementAndGet();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("title", "Mock Page");
                    result.put("pageId", invocation.arguments().get("pageId"));
                    return ToolExecutionRecord.ok(invocation.toolCallId(), result);
                }));
    }

    private void registerSubmitOrder(AgentClient client) {
        client.tools().register(LocalTool.of(
                LocalToolDescriptor.builder(SUBMIT_ORDER)
                        .displayName("Submit order")
                        .description("Submit an order; has side effects and requires approval")
                        .sideEffect(LocalToolDescriptor.SideEffect.ACTION)
                        .requiredArguments("orderId")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},"
                                + "\"required\":[\"orderId\"]}")
                        .build(),
                (invocation, ctx) -> {
                    submitOrderCount.incrementAndGet();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "submitted");
                    result.put("orderId", invocation.arguments().get("orderId"));
                    return ToolExecutionRecord.ok(invocation.toolCallId(), result);
                }));
    }

    private void registerPing(AgentClient client) {
        client.tools().register(LocalTool.of(
                LocalToolDescriptor.builder(PING)
                        .displayName("Ping")
                        .description("A trivial observation tool with no required arguments")
                        .sideEffect(LocalToolDescriptor.SideEffect.OBSERVATION)
                        .inputSchema("{\"type\":\"object\"}")
                        .build(),
                (invocation, ctx) -> {
                    pingCount.incrementAndGet();
                    return ToolExecutionRecord.ok(invocation.toolCallId(), Map.of("pong", true));
                }));
    }
}