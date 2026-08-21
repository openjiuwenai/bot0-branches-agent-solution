/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Tests the strict Bus-only A2A JSON-RPC request boundary. */
class BusA2AJsonRpcSupportTest {
    private final BusA2AJsonRpcSupport support = new BusA2AJsonRpcSupport();

    @Test
    void normalizesMissingRoleAndInjectsTrustedTenant() {
        BusA2AJsonRpcSupport.ParsedA2ARequest parsed = support.parseRequest(sendRequest("\"request-1\"", ""),
                "tenant-a");

        assertThat(parsed.originalId()).isEqualTo("request-1");
        assertThat(parsed.params()).isInstanceOfSatisfying(MessageSendParams.class, params -> {
            assertThat(params.message().role()).isEqualTo(Message.Role.ROLE_USER);
            assertThat(params.tenant()).isEqualTo("tenant-a");
        });
    }

    @Test
    void normalizesNullAndBlankRolesToUser() {
        String missingRole = sendRequest("\"request-1\"", "");
        String nullRole = missingRole.replace("\"messageId\":\"message-1\"",
                "\"messageId\":\"message-1\",\"role\":null");
        String blankRole = missingRole.replace("\"messageId\":\"message-1\"",
                "\"messageId\":\"message-1\",\"role\":\"  \"");

        assertThat(messageParams(nullRole).message().role()).isEqualTo(Message.Role.ROLE_USER);
        assertThat(messageParams(blankRole).message().role()).isEqualTo(Message.Role.ROLE_USER);
    }

    @Test
    void preservesFractionalAndLargeNumericIds() {
        BusA2AJsonRpcSupport.ParsedA2ARequest fractional = support.parseRequest(sendRequest("1.5", ""), null);
        BusA2AJsonRpcSupport.ParsedA2ARequest large = support.parseRequest(
                sendRequest("4294967297", ""), null);

        assertThat(fractional.originalId()).isEqualTo(new BigDecimal("1.5"));
        assertThat(large.originalId()).isEqualTo(new BigInteger("4294967297"));
    }

    @Test
    void rejectsDuplicateEnvelopeFieldsAsParseError() {
        assertThatThrownBy(() -> support.parseRequest("""
                {"jsonrpc":"2.0","jsonrpc":"2.0","id":"request-1","method":"GetTask",
                 "params":{"id":"task-1"}}
                """, null)).isInstanceOfSatisfying(BusA2AJsonRpcSupport.RequestException.class, failure -> {
                    assertThat(failure.getError().getCode()).isEqualTo(-32700);
                    assertThat(failure.getRequestId()).isNull();
                });
    }

    @Test
    void rejectsUnknownParamsFieldAsInvalidParamsButStillExtractsId() {
        String request = sendRequest("\"request-1\"", ",\"unknownField\":true");

        assertThatThrownBy(() -> support.parseRequest(request, null))
                .isInstanceOfSatisfying(BusA2AJsonRpcSupport.RequestException.class, failure -> {
                    assertThat(failure.getRequestId()).isEqualTo("request-1");
                    assertThat(failure.getError().getCode()).isEqualTo(-32602);
                });
        assertThat(support.parseRequestId(request)).isEqualTo("request-1");
    }

    @Test
    void rejectsInvalidExplicitRoleAndTenantMismatch() {
        String invalidRole = sendRequest("\"request-1\"", "")
                .replace("\"messageId\":\"message-1\"", "\"messageId\":\"message-1\",\"role\":\"ADMIN\"");
        String wrongTenant = sendRequest("\"request-2\"", ",\"tenant\":\"tenant-b\"");

        assertThatThrownBy(() -> support.parseRequest(invalidRole, null))
                .isInstanceOfSatisfying(BusA2AJsonRpcSupport.RequestException.class,
                        failure -> assertThat(failure.getError().getCode()).isEqualTo(-32602));
        assertThatThrownBy(() -> support.parseRequest(wrongTenant, "tenant-a"))
                .isInstanceOfSatisfying(BusA2AJsonRpcSupport.RequestException.class, failure -> {
                    assertThat(failure.getRequestId()).isEqualTo("request-2");
                    assertThat(failure.getError().getCode()).isEqualTo(-32602);
                });
    }

    @Test
    void mapsUnsupportedMethodAndExtractsIdFromInvalidEnvelope() {
        assertThatThrownBy(() -> support.parseRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":\"request-1\",\"method\":\"CancelTask\",\"params\":{}}",
                null)).isInstanceOfSatisfying(BusA2AJsonRpcSupport.RequestException.class,
                        failure -> assertThat(failure.getError().getCode()).isEqualTo(-32601));

        assertThat(support.parseRequestId(
                "{\"jsonrpc\":\"2.0\",\"id\":4294967297,\"method\":\"SendMessage\",\"params\":[]}"))
                .isEqualTo(new BigInteger("4294967297"));
    }

    private static String sendRequest(String id, String extraParams) {
        return """
                {"jsonrpc":"2.0","id":%s,"method":"SendMessage","params":{
                  "message":{"messageId":"message-1","parts":[{"kind":"text","text":"hello"}]}
                  %s
                }}
                """.formatted(id, extraParams);
    }

    private MessageSendParams messageParams(String request) {
        Object params = support.parseRequest(request, null).params();
        if (params instanceof MessageSendParams messageSendParams) {
            return messageSendParams;
        }
        throw new AssertionError("Expected MessageSendParams but got " + params.getClass().getName());
    }
}
