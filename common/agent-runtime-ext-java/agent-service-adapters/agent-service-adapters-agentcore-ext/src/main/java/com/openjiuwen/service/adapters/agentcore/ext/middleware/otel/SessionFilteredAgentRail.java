/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.InvokeInputs;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.extensions.tracerotel.OtelRail;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.egress.EgressContextStash;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.audit.AuditEventBridge;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Session-filtered delegating rail. The engine fires callbacks on every rail registered on
 * the shared agent instance regardless of session, so this rail only delegates to its
 * private {@link OtelRail} when the callback's session matches the bound conversation id.
 * It also carries the http root span: {@code beforeInvoke} makes it current (so the chain
 * root joins the same trace) and {@code afterInvoke} closes the bridge scope after the
 * delegate finished the chain span. Every hook is fully guarded and never throws.
 *
 * @since 2026-08-07
 */
public class SessionFilteredAgentRail extends AgentRail {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionFilteredAgentRail.class);
    private static final Set<String> WARNED_SESSIONS = ConcurrentHashMap.newKeySet();

    private final String conversationId;
    private final Pattern derivedSuffix;
    private final String subPrefix;
    private final OtelRail delegate;
    private final Span httpSpan;
    private Scope bridgeScope;
    private volatile String pendingQuery;

    public SessionFilteredAgentRail(String conversationId, OtelRail delegate, Span httpSpan) {
        this.conversationId = conversationId;
        this.delegate = delegate;
        this.httpSpan = httpSpan;
        this.derivedSuffix = Pattern.compile("^" + Pattern.quote(conversationId) + "_[0-9]+$");
        this.subPrefix = conversationId + "_sub_";
        setPriority(0);
    }

    @Override
    public void beforeInvoke(AgentCallbackContext ctx) {
        safe(ctx, () -> {
            if (httpSpan != null) {
                bridgeScope = httpSpan.storeInContext(Context.root()).makeCurrent();
            }
            if (ctx.getInputs() instanceof InvokeInputs inputs) {
                pendingQuery = inputs.getQuery();
            }
            delegate.beforeInvoke(ctx);
        });
    }

    @Override
    public void afterInvoke(AgentCallbackContext ctx) {
        safe(ctx, () -> {
            delegate.afterInvoke(ctx);
            if (bridgeScope != null) {
                bridgeScope.close();
                bridgeScope = null;
            }
            auditExchange(ctx);
        });
        // 注意：不在此清理 EgressContextStash——中断型委托（a2a_delegate）的出站发生在
        // 本次 invoke 结束之后（orchestrator/coordinator 驱动），此时清理会丢失父上下文。
        // 清理点在 http 请求收尾（HttpRequestSpanFilter 移除桥接条目处）。
    }

    @Override
    public void beforeModelCall(AgentCallbackContext ctx) {
        safe(ctx, () -> delegate.beforeModelCall(ctx));
    }

    @Override
    public void afterModelCall(AgentCallbackContext ctx) {
        safe(ctx, () -> delegate.afterModelCall(ctx));
    }

    @Override
    public void onModelException(AgentCallbackContext ctx) {
        safe(ctx, () -> delegate.onModelException(ctx));
    }

    @Override
    public void beforeToolCall(AgentCallbackContext ctx) {
        safe(ctx, () -> {
            // 委托调用随后在 coordinator 线程出站（无线程上下文），暂存 chain 上下文
            // 供 OtelRemoteAgentCallerDecorator 以会话 id 取回，使 dispatch span 同 trace
            EgressContextStash.put(conversationId, Context.current());
            delegate.beforeToolCall(ctx);
        });
    }

    @Override
    public void afterToolCall(AgentCallbackContext ctx) {
        safe(ctx, () -> delegate.afterToolCall(ctx));
        auditTool(ctx, "finish");
    }

    @Override
    public void onToolException(AgentCallbackContext ctx) {
        safe(ctx, () -> delegate.onToolException(ctx));
        auditTool(ctx, "error");
    }

    // 审计问答摘要：bridge 未注册（trajectory 未启用）时 no-op；rail 按请求绑定，实例字段安全
    private void auditExchange(AgentCallbackContext ctx) {
        try {
            Session session = ctx.getSession();
            if (session == null || session.getSessionId() == null
                    || !(ctx.getInputs() instanceof InvokeInputs inputs)) {
                return;
            }
            Object result = inputs.getResult();
            AuditEventBridge.recordExchange(session.getSessionId(), pendingQuery,
                    result != null ? String.valueOf(result) : "");
        } catch (IllegalStateException | NullPointerException | ClassCastException e) {
            LOGGER.warn("audit exchange event failed: {}", e.getClass().getSimpleName());
        }
    }

    // 审计工具事件：rail 无耗时来源，elapsedMs 取 0（不虚报）；未启用 trajectory 时桥为 no-op
    private void auditTool(AgentCallbackContext ctx, String status) {
        try {
            // 与委托一致先判会话匹配——共享 agent 上每个 rail 都会收到全部会话的回调，
            // 不匹配直接跳过（否则一次工具调用被 N 个绑定 rail 重复记录）
            if (!matches(ctx)) {
                return;
            }
            Session session = ctx.getSession();
            if (session == null || session.getSessionId() == null
                    || !(ctx.getInputs() instanceof ToolCallInputs inputs)) {
                return;
            }
            AuditEventBridge.recordToolCall(session.getSessionId(), inputs.getToolName(), status, 0L);
            AuditEventBridge.recordToolDecision(session.getSessionId(), inputs.getToolName(), status, 0L,
                    summarize(inputs.getToolArgs()));
        } catch (IllegalStateException | NullPointerException | ClassCastException e) {
            LOGGER.warn("audit tool event failed: {}", e.getClass().getSimpleName());
        }
    }

    private static String summarize(Object args) {
        if (args == null) {
            return "";
        }
        String text = String.valueOf(args);
        return text.length() <= 200 ? text : text.substring(0, 200);
    }

    private void safe(AgentCallbackContext ctx, Runnable action) {
        try {
            if (!matches(ctx)) {
                return;
            }
            action.run();
        } catch (IllegalStateException | IllegalArgumentException | NullPointerException
                | ClassCastException | UnsupportedOperationException e) {
            // 故障隔离：覆盖 OTel/Jackson 常见运行时异常，rail 失效不阻断 agent 执行
            LOGGER.warn("otel rail delegation failed: {}", e.getClass().getSimpleName());
        }
    }

    private boolean matches(AgentCallbackContext ctx) {
        Session session = ctx.getSession();
        if (session == null || session.getSessionId() == null) {
            return false;
        }
        String sessionId = session.getSessionId();
        boolean matched = conversationId.equals(sessionId)
                || sessionId.startsWith(subPrefix)
                || derivedSuffix.matcher(sessionId).matches();
        if (!matched && WARNED_SESSIONS.add(conversationId + " <- " + sessionId)) {
            LOGGER.warn("otel rail session mismatch: bound={} callback={}", conversationId, sessionId);
        }
        return matched;
    }
}
