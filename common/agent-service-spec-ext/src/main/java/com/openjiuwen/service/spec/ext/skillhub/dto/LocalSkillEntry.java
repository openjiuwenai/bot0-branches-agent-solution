/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.ext.skillhub.dto;

import java.nio.file.Path;

/**
 * Local skill entry: skill id + local path after download &amp; verify.
 *
 * <p>First-version contract only carries {@code skillId} and {@code localPath}.
 * The required/optional flag is decided by the Provider implementation per
 * deployment config; {@code SkillHubManager} reads it from the Provider.
 *
 * <h2>Current status (FEAT-005 v1)</h2>
 * <p><b>Not used by the v1 implementation.</b> The first slice simplifies the
 * data flow to plain {@link Path} objects:
 * {@link com.openjiuwen.service.adapters.agentcore.ext.middleware.skillhub.SkillHubManager}
 * keeps a {@code List<Path>} of downloaded-and-verified skill directories, and
 * {@link com.openjiuwen.service.adapters.agentcore.ext.middleware.skillhub.SkillHubInstaller#install}
 * accepts {@code List<Path>} directly. The {@code skillId} carried by this
 * record has no consumer yet because agent-core's
 * {@code BaseAgent.registerSkill(path)} derives the skill name from the
 * directory name, so an explicit id mapping is not needed in v1.
 *
 * <p><b>Planned usage (subsequent versions).</b> When the FEAT-005 design
 * lands the Agent-side skill selection config
 * ({@code openjiuwen.service.agent.skills.selections: [{id, version, required}]}),
 * the Manager will need to match downloaded skill directories back to the
 * declared skill ids (a zip package's directory name may differ from the
 * configured {@code id}). At that point {@code verifiedSkillPaths} /
 * {@code processedForAgent} will switch from {@code List<Path>} to
 * {@code List<LocalSkillEntry>}, and {@code SkillHubInstaller.install} will
 * accept {@code List<LocalSkillEntry>}. A {@code required} field may also be
 * folded into this record (currently documented in §2.1 / §5.1 of the design
 * doc as "decided by the Provider").
 *
 * @since 2026-07-15
 */
public record LocalSkillEntry(
        String skillId,
        Path localPath
) { }
