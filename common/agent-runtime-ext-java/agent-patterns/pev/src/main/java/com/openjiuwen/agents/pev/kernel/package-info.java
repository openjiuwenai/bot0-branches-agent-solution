/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * PEV decision core — sealed types + pure-function dispatch (Plan→Execute→Verify→Diagnose→Dispatch).
 *
 * <h2>Drift debt ledger — deliberate non-deduplication vs the spring-ai-ascend twin</h2>
 *
 * <p>This package is the <b>canonical</b> PEV kernel. A structurally-isomorphic twin lives in
 * the {@code spring-ai-ascend} repository:
 * {@code com.openjiuwen.core.alpha.verifier.{NodeResult,RootCause,ReplanAction,VerifyResult}}
 * plus {@code com.openjiuwen.runtime.beta.selfheal.{RootCauseDiagnoser,RootCauseDispatcher}}.
 *
 * <p><b>Decision: keep both, do not merge.</b> Rationale (code-level verification):
 * <ul>
 *   <li><b>Reachability is asymmetric.</b> This module depends only on the public
 *       {@code agent-core-java:0.1.12-jdk17} jar (which contains {@code singleagent.rail.*}
 *       but NO {@code alpha/} or {@code beta/} packages — confirmed via {@code jar tf}). The
 *       ascend twin's {@code com.openjiuwen.core.alpha.verifier.*} sources live <i>inside</i>
 *       the ascend repo (not in the public jar), so the two cannot share a type without one
 *       side taking a hard dependency on the other's source tree. Forcing a merge would
 *       either (a) make this template depend on ascend (inverts the dependency direction —
 *       a sibling pattern must not depend on an engine adapter), or (b) publish new types to
 *       agent-core-java (out of scope for this module).</li>
 *   <li><b>The forceFinish gate is unreachable in PEV.</b> The ascend twin's rails gate on
 *       {@code AgentCallbackContext.requestForceFinish} consumed at ReActAgent offsets
 *       225/700/878 — offsets that do not exist in {@link com.openjiuwen.agents.pev.agent.PEVAgent},
 *       which runs its own self-contained verify loop inside {@code invoke}. So the ascend
 *       twin is reachable <i>only</i> on the ReActAgent host, never on the PEV host. The two
 *       copies serve genuinely disjoint hosts; neither can call the other.</li>
 * </ul>
 *
 * <h3>Known drift points (re-audit on every change to either side)</h3>
 *
 * <p>When editing any of the five sealed types or the two dispatch functions below, mirror the
 * change here and verify the twin — or record an intentional divergence in this table.
 *
 * <table border="1">
 *   <caption>Drift ledger</caption>
 *   <tr><th>Concept</th><th>Here (canonical PEV)</th><th>spring-ai-ascend twin</th><th>Drift</th></tr>
 *   <tr><td>Node result</td>
 *       <td>{@link NodeResult} (permits Success/DeviceFailure/VerifierFailure)</td>
 *       <td>{@code com.openjiuwen.core.alpha.verifier.NodeResult}</td>
 *       <td>Identical shape (record components match: Success(Object),
 *           DeviceFailure(String,String,boolean), VerifierFailure(String,String))</td></tr>
 *   <tr><td>Root cause</td>
 *       <td>{@link RootCause} (3 permitted records)</td>
 *       <td>{@code ...alpha.verifier.RootCause}</td>
 *       <td>Identical 3 states; this side adds {@code Set.copyOf} in compact ctor (defensive)</td></tr>
 *   <tr><td>Replan action</td>
 *       <td>{@link ReplanAction} (LocalReplan/GlobalReplan/AcceptPartial)</td>
 *       <td>{@code ...alpha.verifier.ReplanAction}</td>
 *       <td>Identical 3 records; field names align</td></tr>
 *   <tr><td>Diagnose fn</td>
 *       <td>{@link PevKernel#diagnoseRootCause}</td>
 *       <td>{@code RootCauseDiagnoser.diagnose(boolean,Set,Set)}</td>
 *       <td><b>Signature drift</b>: PEV takes a {@link PevKernel.VerifyResult} (parsed
 *           verdict) + the node map; ascend takes raw {@code (verifyThrew, failedToolNodes,
 *           verifyFailedNodes)} booleans/sets. Same priority logic, different input shape.</td></tr>
 *   <tr><td>Dispatch fn</td>
 *       <td>{@link PevKernel#toReplanAction}</td>
 *       <td>{@code RootCauseDiagnoser.toReplanAction}</td>
 *       <td>Logic identical (≤2 LocalReplan / &gt;2 or empty GlobalReplan). PEV returns
 *           English reasons; ascend returns English reasons — aligned.</td></tr>
 *   <tr><td>VerifyResult</td>
 *       <td>{@link PevKernel.VerifyResult} (nested record)</td>
 *       <td>{@code ...alpha.verifier.VerifyResult}</td>
 *       <td><b>Shape drift</b>: PEV side has 5 fields (passed, failedNodes, feedback,
 *           parseFailure, threw) — {@code threw} distinguishes "verify raised" from
 *           "verify returned non-JSON", which {@link PevKernel#diagnoseRootCause} reads
 *           to set {@code PerceptionUnreliable.verifierThrew}. Ascend twin carries the
 *           same {@code verifyThrew} signal as a separate RootCauseDiagnoser argument
 *           (not on VerifyResult) and additionally exposes {@code nodeResults} /
 *           {@code criteriaResults} fields PEV does not. PEV nests it in PevKernel;
 *           ascend promotes to its own file.</td></tr>
 * </table>
 *
 * <h3>When to revisit (deferred)</h3>
 *
 * <ol>
 *   <li>If agent-core-java ever publishes these types under a neutral package
 *       ({@code com.openjiuwen.core.kernel.*}), both sides should delete their local copies
 *       and depend on the jar — this ledger is the merge checklist.</li>
 *   <li>If the ReAct agent lands a native rails rewrite, the ascend twin may be
 *       retired in favor of this kernel + a thin rail adapter — re-audit then.</li>
 *   <li>If divergence appears (a 4th {@code RootCause} state added on one side only), the
 *       sealed-switch compile guard on each side will catch it locally, but cross-side drift
 *       is silent — this table is the only cross-side guard. Update it on every edit.</li>
 * </ol>
 *
 * <p><b>This ledger is the deliverable.</b> Zero code merged, zero new coupling; the
 * isomorphism is acknowledged and made auditable instead of hidden.
 */
package com.openjiuwen.agents.pev.kernel;
