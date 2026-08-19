/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

/**
 * Static identity and behavior settings for one demo DeepAgent.
 *
 * @param id
 *            Agent identifier
 * @param name
 *            Agent display name
 * @param description
 *            Agent responsibility
 * @param systemPrompt
 *            DeepAgent system prompt
 * @param workspacePath
 *            local workspace path
 * @param taskPlanning
 *            whether DeepAgent planning is enabled
 *
 * @since 0.1.0
 */
public record BankAgentDefinition(String id, String name, String description, String systemPrompt, String workspacePath,
        boolean taskPlanning) {
}
