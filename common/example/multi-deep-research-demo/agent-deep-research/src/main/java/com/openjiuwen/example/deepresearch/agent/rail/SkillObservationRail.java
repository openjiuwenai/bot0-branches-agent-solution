/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.agent.rail;

import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.singleagent.skills.Skill;
import com.openjiuwen.core.singleagent.skills.SkillManager;
import com.openjiuwen.core.singleagent.skills.SkillUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FEAT-005 Layer-2 观察 rail：验证 SkillHub 注册的 skill 是否被 ReAct 循环真正看到、
 * LLM 是否在某一 iter 主动挑选调用它。
 *
 * <p>纯观察 —— 只往应用日志里写行，不改 SkillManager、不拦截 tool 调用、不注册工具。
 * 因此把它挂在任何 agent 上都是安全的，关掉只需把 logger 级别压到 WARN。
 *
 * <p>输出（INFO 级）：
 * <ul>
 *   <li>{@code beforeInvoke}: {@code skills_available count=N names=[...]}
 *       —— 每次请求进入 ReAct 前，快照 {@link SkillManager} 里当前所有 skill 名称；
 *       名称集合相比上次变化时，先打一行 WARN {@code skills_delta previous=... current=...}
 *       并把每个 skill 的 {@code name + description 摘要} 展开一行，方便观察热加载生效。</li>
 *   <li>{@code beforeToolCall}: {@code tool_call iter=N tool=<name> hit_skill=<bool>}
 *       —— LLM 每次决定调用工具都打一行；{@code hit_skill=true} 表示该工具名命中当前
 *       skill 快照中的某个 skill（即 SkillHub 灌入的能力被 LLM 动态挑到）。</li>
 *   <li>{@code afterInvoke}: {@code invoke_summary tool_calls=N skill_hits=M}
 *       —— 单次请求汇总。</li>
 * </ul>
 *
 * <p>合规：description 只截首行（最多 {@value #DESC_MAX_CHARS} 字符），避免整段 SKILL.md
 * 内容落到日志里；hotel demo 的 skillhub-remote profile 用同样的思路把 tool/llm 日志
 * 压到 WARN，参考 {@code common/example/multi-react-travel-demo/agent-hotel/src/main/resources/application.yml}。
 *
 * <p>并发：多请求同 rail 实例共享计数器，仅用于粗粒度 demo 观察，不保证严格单调；
 * 生产不建议依赖它做审计。
 *
 * @since 2026-07-26
 */
public class SkillObservationRail extends AgentRail {
    private static final Logger LOG = LoggerFactory.getLogger(SkillObservationRail.class);
    private static final int DESC_MAX_CHARS = 120;
    private static final int DEFAULT_PRIORITY = 90;

    private final AtomicInteger toolCallsThisInvoke = new AtomicInteger();
    private final AtomicInteger skillHitsThisInvoke = new AtomicInteger();

    /**
     * Snapshot 上次 beforeInvoke 时看到的 skill 名称集合，用于检测集合变化并打 delta 日志。
     * volatile 足够：只在 beforeInvoke 里读写，同一 invoke 内 beforeToolCall 只读。
     */
    private volatile Set<String> lastSkillNames = Set.of();

    /**
     * 默认构造。priority = {@value #DEFAULT_PRIORITY}，让本 rail 在业务 rail 之后跑，
     * 观察到的是"业务 rail 处理完之后"的 tool 决策，避免顺序歧义。
     */
    public SkillObservationRail() {
        setPriority(DEFAULT_PRIORITY);
    }

    @Override
    public void beforeInvoke(AgentCallbackContext ctx) {
        toolCallsThisInvoke.set(0);
        skillHitsThisInvoke.set(0);
        Set<String> current = snapshotSkillNames(ctx);
        if (!current.equals(lastSkillNames)) {
            LOG.warn("skills_delta previous={} current={}", lastSkillNames, current);
            lastSkillNames = current;
            logSkillRoster(ctx);
        } else {
            LOG.info("skills_available count={} names={}", current.size(), current);
        }
    }

    @Override
    public void beforeToolCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ToolCallInputs tci)) {
            return;
        }
        String toolName = tci.getToolName();
        if (toolName == null || toolName.isEmpty()) {
            return;
        }
        int count = toolCallsThisInvoke.incrementAndGet();
        boolean isSkill = lastSkillNames.contains(toolName);
        if (isSkill) {
            skillHitsThisInvoke.incrementAndGet();
        }
        LOG.info("tool_call iter={} tool={} hit_skill={}", count, toolName, isSkill);
    }

    @Override
    public void afterInvoke(AgentCallbackContext ctx) {
        LOG.info("invoke_summary tool_calls={} skill_hits={}",
                toolCallsThisInvoke.get(), skillHitsThisInvoke.get());
    }

    private static Set<String> snapshotSkillNames(AgentCallbackContext ctx) {
        return resolveSkillManager(ctx)
                .map(SkillManager::getAll)
                .map(SkillObservationRail::toNameSet)
                .orElseGet(Set::of);
    }

    private static Set<String> toNameSet(List<Skill> all) {
        if (all == null || all.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (Skill skill : all) {
            if (skill != null && skill.getName() != null) {
                names.add(skill.getName());
            }
        }
        return names;
    }

    private static void logSkillRoster(AgentCallbackContext ctx) {
        resolveSkillManager(ctx).ifPresent(mgr -> {
            List<Skill> all = mgr.getAll();
            if (all == null) {
                return;
            }
            for (Skill skill : all) {
                if (skill == null) {
                    continue;
                }
                LOG.info("skill_roster name={} desc_head={}",
                        skill.getName(), summariseDescription(skill.getDescription()));
            }
        });
    }

    private static Optional<SkillManager> resolveSkillManager(AgentCallbackContext ctx) {
        if (ctx == null || !(ctx.getAgent() instanceof BaseAgent baseAgent)) {
            return Optional.empty();
        }
        SkillUtil skillUtil = baseAgent.getSkillUtil();
        if (skillUtil == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(skillUtil.getSkillManager());
    }

    private static String summariseDescription(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "(none)";
        }
        int newlineIdx = raw.indexOf('\n');
        String firstLine = newlineIdx < 0 ? raw : raw.substring(0, newlineIdx);
        if (firstLine.length() > DESC_MAX_CHARS) {
            return firstLine.substring(0, DESC_MAX_CHARS) + "...";
        }
        return firstLine;
    }
}
