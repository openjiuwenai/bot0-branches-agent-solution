/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.studio.dsl.host;

import com.openjiuwen.studio.dsl.StudioDslModule;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.exec.WorkflowAssemblyBridge;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.spi.CodeLogic;
import com.openjiuwen.studio.dsl.spi.CodeLogicContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fake FEAT-031 host (期 0): no DSL loader, no HTTP, no edge scheduler.
 * Hand-assembles AssembledWorkflow and drives StudioDslModule like a real host would.
 *
 * <pre>
 * mvn -f ../../agent-core-ext-java/pom.xml -pl agent-core-ext-studio-dsl -am install -DskipTests
 * mvn -f pom.xml package exec:java
 * </pre>
 *
 * @since 2026-08-17
 */
public final class FakeHostMain {
    private static final Logger LOG = Logger.getLogger(FakeHostMain.class.getName());

    /**
     * main.
     *
     * @param args args
     */
    public static void main(String[] args) {
        int failed = 0;
        failed += run("S0-1 linear setVariable+message", FakeHostMain::scenarioLinear);
        failed += run("S0-2 branch signal + host route", FakeHostMain::scenarioBranchRoute);
        failed += run("S0-3 java code node", FakeHostMain::scenarioCode);
        failed += run("S0-4 unknown type fails loudly", FakeHostMain::scenarioUnknownType);
        if (failed > 0) {
            throw new IllegalStateException("FAIL: " + failed + " scenario(s)");
        }
        if (LOG.isLoggable(Level.INFO)) {
            LOG.info("OK: all fake-host scenarios passed");
        }
    }

    private static int run(String name, Scenario s) {
        try {
            s.run();
            LOG.log(Level.INFO, "[PASS] {0}", name);
            return 0;
        } catch (AssertionError | IllegalStateException | IllegalArgumentException | NullPointerException
                | ClassCastException e) {
            LOG.log(Level.SEVERE, e, () -> "[FAIL] " + name);
            return 1;
        }
    }

    /**
     * start → setVariable → message → end; variable scope closed by executeLinear.
     */
    private static void scenarioLinear() {
        StudioDslModule module = StudioDslModule.create();
        AssembledWorkflow wf = new AssembledWorkflow(
                "smoke-linear",
                List.of(
                        AssembledNode.of("s", "jiuwen.start", Map.of()),
                        AssembledNode.of(
                                "v",
                                "jiuwen.setVariable",
                                Map.of("variableMapping", Map.of("greeting", "hello-host"))),
                        AssembledNode.of("m", "jiuwen.message", Map.of("message", "say ${greeting}")),
                        AssembledNode.of("e", "jiuwen.end", Map.of())));

        NodeBuildContext ctx = module.newRootContext("smoke-linear", "tenant-smoke");
        WorkflowAssemblyBridge bridge = module.assemblyBridge();
        Map<String, Object> out = bridge.executeLinear(wf, ctx, Map.of("seed", 1), null, null);

        Map<String, Object> uf = asUserFields(out);
        require("hello-host".equals(uf.get("greeting")), "greeting missing: " + uf);
        require(Integer.valueOf(1).equals(uf.get("seed")) || Long.valueOf(1L).equals(uf.get("seed")), "seed lost");
        require(ctx.variableScope().isClosed(), "variable scope must close after executeLinear");
        LOG.log(Level.INFO, "    userFields={0}", uf);
    }

    /**
     * Host responsibility: read branchId from jiuwen.branch, then choose which linear tail to run.
     * (Real edge wiring stays OUT of FEAT-031 / with the DSL loader.)
     */
    private static void scenarioBranchRoute() {
        StudioDslModule module = StudioDslModule.create();
        NodeBuildContext ctx = module.newRootContext("smoke-branch", "tenant-smoke");
        WorkflowAssemblyBridge bridge = module.assemblyBridge();

        AssembledWorkflow decide = new AssembledWorkflow(
                "smoke-branch-decide",
                List.of(
                        AssembledNode.of("s", "jiuwen.start", Map.of()),
                        AssembledNode.of(
                                "b",
                                "jiuwen.branch",
                                Map.of(
                                        "branches",
                                        List.of(
                                                Map.of(
                                                        "branchId",
                                                        "vip",
                                                        "condition",
                                                        Map.of(
                                                                "operator",
                                                                "eq",
                                                                "left",
                                                                Map.of("value", "tier"),
                                                                "right",
                                                                Map.of("value", "gold"))),
                                                Map.of("branchId", "default", "isDefault", true))))));

        Map<String, Object> decided =
                bridge.executeLinear(decide, ctx, Map.of("tier", "gold"), null, null);
        Object branchId = decided.get("branchId");
        require("vip".equals(branchId), "expected vip, got " + branchId);

        // Fresh scope for the chosen path (host would do this per run / per arm).
        NodeBuildContext pathCtx = module.newRootContext("smoke-branch-vip", "tenant-smoke");
        AssembledWorkflow vipPath = new AssembledWorkflow(
                "smoke-branch-vip",
                List.of(
                        AssembledNode.of("s", "jiuwen.start", Map.of()),
                        AssembledNode.of(
                                "v",
                                "jiuwen.setVariable",
                                Map.of("variableMapping", Map.of("lane", "vip"))),
                        AssembledNode.of("e", "jiuwen.end", Map.of())));
        Map<String, Object> pathOut =
                bridge.executeLinear(vipPath, pathCtx, Map.of("tier", "gold"), null, null);
        Map<String, Object> uf = asUserFields(pathOut);
        require(uf != null && "vip".equals(uf.get("lane")), "vip lane not set: " + uf);
        LOG.log(Level.INFO, "    branchId={0}, lane={1}", new Object[] {branchId, uf.get("lane")});
    }

    /**
     * Host registers CodeLogic SPI, then runs jiuwen.code in a linear mini-graph.
     */
    private static void scenarioCode() {
        StudioDslModule module = StudioDslModule.create();
        module.codeLogicRegistry().register(new CodeLogic() {
            /**
             * name.
             *
             * @return result
             */
            @Override
            public String name() {
                return "double";
            }

            /**
             * execute.
             *
             * @param inputs inputs
             * @param ctx ctx
             * @return result
             */
            @Override
            public Map<String, Object> execute(Map<String, Object> inputs, CodeLogicContext ctx) {
                Object v = inputs.get("n");
                long n = v instanceof Number num ? num.longValue() : Long.parseLong(String.valueOf(v));
                Map<String, Object> out = new LinkedHashMap<>(inputs);
                out.put("n", n * 2);
                return out;
            }
        });

        AssembledWorkflow wf = new AssembledWorkflow(
                "smoke-code",
                List.of(
                        AssembledNode.of("s", "jiuwen.start", Map.of()),
                        AssembledNode.of("c", "jiuwen.code", Map.of("codeLogicRef", "double")),
                        AssembledNode.of("e", "jiuwen.end", Map.of())));
        NodeBuildContext ctx = module.newRootContext("smoke-code", "tenant-smoke");
        Map<String, Object> out =
                module.assemblyBridge().executeLinear(wf, ctx, Map.of("n", 21), null, null);
        Map<String, Object> uf = asUserFields(out);
        require(Long.valueOf(42L).equals(toLong(uf.get("n"))), "code out n!=42: " + uf);
        LOG.log(Level.INFO, "    n={0}", uf.get("n"));
    }

    /**
     * Unknown canonical type must surface a distinguishable failure (host can catch).
     */
    private static void scenarioUnknownType() {
        StudioDslModule module = StudioDslModule.create();
        AssembledWorkflow wf = new AssembledWorkflow(
                "smoke-unknown",
                List.of(AssembledNode.of("x", "jiuwen.notARealNode", Map.of())));
        NodeBuildContext ctx = module.newRootContext("smoke-unknown", "tenant-smoke");
        try {
            module.assemblyBridge().executeLinear(wf, ctx, Map.of(), null, null);
            throw new AssertionError("expected NodeExecutionException");
        } catch (NodeExecutionException e) {
            require(
                    e.causeCode() == NodeCauseCode.UNKNOWN_NODE_TYPE,
                    "unexpected causeCode=" + e.causeCode());
            LOG.log(Level.INFO, "    causeCode={0}", e.causeCode());
        }
    }

    private static Map<String, Object> asUserFields(Map<String, Object> out) {
        Object raw = out.get("userFields");
        if (!(raw instanceof Map<?, ?> m)) {
            throw new AssertionError("userFields missing: " + out);
        }
        Map<String, Object> uf = new LinkedHashMap<>();
        m.forEach((k, v) -> uf.put(String.valueOf(k), v));
        return uf;
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(v));
    }

    private static void require(boolean ok, String msg) {
        if (!ok) {
            throw new AssertionError(msg);
        }
    }

    /**
     * One fake-host scenario.
     */
    @FunctionalInterface
    private interface Scenario {
        /**
         * Run the scenario and throw on assertion failure.
         */
        void run();
    }
}
