/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;

import java.util.Optional;

/**
 * Maps a RemoteCallOutcome terminal category to the current execution's terminal
 * action. COMPLETED is expressed by observer.onComplete(), never a QueryChunk;
 * failures carry the target agent and remoteFailure diagnostics (spec 2.4/5.4).
 *
 * @since 2026-08-19
 */
public class DownstreamEventMapper {

    public enum TerminalAction {
        COMPLETE, INPUT_REQUIRED, ERROR
    }

    public record MappedTerminal(TerminalAction action, String errorCode, String detail) {
        static MappedTerminal complete() {
            return new MappedTerminal(TerminalAction.COMPLETE, null, null);
        }

        static MappedTerminal inputRequired() {
            return new MappedTerminal(TerminalAction.INPUT_REQUIRED, null, null);
        }

        static MappedTerminal error(String errorCode, String detail) {
            return new MappedTerminal(TerminalAction.ERROR, errorCode, detail);
        }
    }

    public MappedTerminal fromOutcome(RemoteCallOutcome outcome, String targetAgentId) {
        String category = outcome.resultCategory();
        if (category == null || category.isBlank()) {
            return MappedTerminal.error("VERSATILE_HANDOFF_RESULT_INVALID",
                    "empty resultCategory from target=" + targetAgentId);
        }
        return switch (category) {
            case "COMPLETED" -> MappedTerminal.complete();
            case "INPUT_REQUIRED" -> MappedTerminal.inputRequired();
            case "REMOTE_BUSINESS_FAILURE" -> MappedTerminal.error("VERSATILE_HANDOFF_REMOTE_BUSINESS_FAILURE",
                    detail(outcome, targetAgentId));
            case "REMOTE_REJECTED" -> MappedTerminal.error("VERSATILE_HANDOFF_REMOTE_REJECTED",
                    detail(outcome, targetAgentId));
            default -> MappedTerminal.error("VERSATILE_HANDOFF_RESULT_INVALID",
                    "unrecognized resultCategory=" + category + " target=" + targetAgentId);
        };
    }

    private static String detail(RemoteCallOutcome outcome, String targetAgentId) {
        StringBuilder sb = new StringBuilder("target=").append(targetAgentId);
        if (outcome.result() != null && !outcome.result().isBlank()) {
            sb.append(" result=").append(outcome.result());
        }
        Optional.ofNullable(outcome.remoteFailure())
                .ifPresent(f -> sb.append(" remoteFailure=").append(f.toString()));
        return sb.toString();
    }
}
