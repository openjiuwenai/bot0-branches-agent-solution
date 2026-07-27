package com.huawei.ascend.client.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.client.tool.spi.ToolExecutionRecord;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 {@link ToolExecutionRecord} 渲染为服务端/智能体可消费的 observation 文本（FEAT-007 §L-08）。
 *
 * <p>渲染为紧凑 JSON 文本，作为续传消息的 {@code TextPart} 提交给服务端；
 * 成功携带结构化结果，失败携带标准化错误分类，保证"结构化错误可透明续传"。
 */
final class ObservationTextRenderer {

    private final ObjectMapper mapper;

    ObservationTextRenderer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String render(ToolExecutionRecord record) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolCallId", record.toolCallId());
        out.put("status", record.outcome().name().toLowerCase());
        switch (record.outcome()) {
            case OK -> {
                if (record.payload() != null) {
                    out.put("result", record.payload());
                }
                if (record.payloadRef() != null) {
                    out.put("resultRef", record.payloadRef());
                }
            }
            default -> {
                if (record.errorCode() != null) {
                    out.put("errorCode", record.errorCode());
                }
                if (record.message() != null) {
                    out.put("message", record.message());
                }
            }
        }
        try {
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            // 渲染兜底：绝不因序列化失败而阻断续传。
            return "{\"toolCallId\":\"" + record.toolCallId() + "\",\"status\":\""
                    + record.outcome().name().toLowerCase() + "\"}";
        }
    }
}
