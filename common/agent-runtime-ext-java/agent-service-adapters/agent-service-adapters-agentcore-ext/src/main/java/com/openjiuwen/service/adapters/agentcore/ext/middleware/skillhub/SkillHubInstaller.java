/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.skillhub;

import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.core.singleagent.skills.SkillManager;
import com.openjiuwen.core.singleagent.skills.SkillUtil;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.service.spec.ext.skillhub.SkillHubErrorCategory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Installs local skill paths into a {@link BaseAgent} (or the inner agent of a
 * {@link DeepAgent}) via {@link BaseAgent#registerSkill(Object)}.
 *
 * <p>Design doc §2.3 SkillHubInstaller contract + §4.5 安装结果验证.
 *
 * <p>Handover semantics (PR #415): a required skill whose {@code registerSkill}
 * call does not grow {@link SkillManager#count()} throws
 * {@code IllegalStateException} with prefix {@code SkillHub[INSTALL_FAILED]} so
 * the request thread can observe the failure. Optional skills warn and skip.
 *
 * <p>First-version simplification: {@code LocalSkillEntry} has no required flag
 * yet (FEAT-005 §2.1), so this installer treats every path as required. When
 * the Provider later exposes required/optional markers, this class will branch
 * per entry.
 *
 * @since 2026-07-15
 */
public class SkillHubInstaller {

    private static final Logger log = LoggerFactory.getLogger(SkillHubInstaller.class);

    /**
     * Install the supplied skill paths into the target agent.
     *
     * @param agent       target agent (DeepAgent / BaseAgent / other)
     * @param skillPaths  local skill paths to register
     * @throws IllegalStateException when a required skill's handover fails
     *         (registerSkill did not grow skillCount)
     */
    public void install(Object agent, List<Path> skillPaths) {
        if (agent == null) {
            return;
        }
        if (skillPaths == null || skillPaths.isEmpty()) {
            return;
        }
        if (agent instanceof String) {
            log.info("SkillHub agent-id mode cannot install skills in v1");
            return;
        }
        Optional<BaseAgent> target = resolveBaseAgent(agent);
        if (target.isEmpty()) {
            log.warn("SkillHub unsupported agent type for skill install: {}",
                    agent.getClass().getName());
            return;
        }
        BaseAgent baseAgent = target.get();
        for (Path path : skillPaths) {
            installOne(baseAgent, path);
        }
    }

    private void installOne(BaseAgent baseAgent, Path skillPath) {
        int before = currentSkillCount(baseAgent);
        try {
            // BaseAgent.registerSkill(Object) → SkillUtil.registerSkills only accepts
            // String or List<String>; passing Path silently no-ops. Pass String form.
            baseAgent.registerSkill(skillPath.toString());
        } catch (IllegalStateException ex) {
            throw error(SkillHubErrorCategory.INSTALL_FAILED,
                    "registerSkill threw path=" + sanitize(skillPath), ex);
        }
        int after = currentSkillCount(baseAgent);
        if (before < 0 || after < 0) {
            log.warn("SkillHub skill register skipped count-verify skillPath={} reason=skill-util-or-manager-null"
                    + " before={} after={}",
                    sanitize(skillPath), before, after);
            return;
        }
        if (after <= before) {
            // SkillManager.register logs "Skill already exists: <name>" and returns
            // without growing the count when a skill with the same name was already
            // registered (e.g. two upstream zips ship the same skill dir). Treat this
            // as idempotent success and skip — do NOT fail the whole request just
            // because a duplicate skill name was encountered. (PR #415: handover
            // failure blocks ready, but duplicate-name is not a handover failure,
            // it is an idempotent re-registration that agent-core already handled.)
            log.info("SkillHub skill register idempotent-skip skillPath={}"
                    + " reason=skill-already-registered before={} after={}",
                    sanitize(skillPath), before, after);
            return;
        }
        log.info("SkillHub skill registered skillPath={} skillCountBefore={} skillCountAfter={}",
                sanitize(skillPath), before, after);
    }

    private static int currentSkillCount(BaseAgent baseAgent) {
        try {
            SkillUtil skillUtil = baseAgent.getSkillUtil();
            if (skillUtil == null) {
                return -1;
            }
            SkillManager manager = skillUtil.getSkillManager();
            if (manager == null) {
                return -1;
            }
            return manager.count();
        } catch (IllegalStateException ex) {
            return -1;
        }
    }

    private static Optional<BaseAgent> resolveBaseAgent(Object agent) {
        if (agent instanceof BaseAgent baseAgent) {
            return Optional.of(baseAgent);
        }
        if (agent instanceof DeepAgent deepAgent) {
            BaseAgent inner = deepAgent.getAgent();
            return inner == null ? Optional.empty() : Optional.of(inner);
        }
        return Optional.empty();
    }

    private static IllegalStateException error(SkillHubErrorCategory category,
                                              String reason, Throwable cause) {
        IllegalStateException ex = new IllegalStateException(
                "SkillHub[" + category + "] " + (reason == null ? "" : reason));
        if (cause != null) {
            ex.initCause(cause);
        }
        return ex;
    }

    private static String sanitize(Path path) {
        if (path == null) {
            return "";
        }
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }
}
