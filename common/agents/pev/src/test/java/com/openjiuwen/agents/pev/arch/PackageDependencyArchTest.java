/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * PEV 包依赖方向承重测试——ArchUnit 把"文件夹约定"升为 CI 强制契约（呼应铁律⑰）。
 *
 * <p>四条规则钉死 L1 logical.md §7.1 / development.md §3 声明的依赖方向：
 * <ul>
 *   <li>kernel 不得依赖 agent（kernel 纯度，可独立复用）</li>
 *   <li>kernel 不得依赖 observability（单向：observability → kernel）</li>
 *   <li>observability 不得依赖 agent（xuefanfan gap 治本：Planned 投影后 GREEN）</li>
 *   <li>rail 不得依赖 agent（rail 只读 kernel 类型）</li>
 * </ul>
 *
 * <p>mutation-RED：在 observability 加 {@code import ...agent.PevComponents}
 * → 规则 3 RED（observabilityMustNotDependOnAgent）；
 * 在 kernel 加 {@code import ...agent.PEVAgent} → 规则 1 RED。
 *
 * @since 2026-08
 */
class PackageDependencyArchTest {
    private static JavaClasses pevClasses;

    @BeforeAll
    static void importClasses() {
        pevClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.openjiuwen.agents.pev");
    }

    /**
     * kernel 不得依赖 agent——kernel 是决策核心，零编排层耦合。
     */
    @Test
    void kernelMustNotDependOnAgent() {
        ArchRule rule = noClasses().that().resideInAPackage("..pev.kernel..")
                .should().dependOnClassesThat().resideInAPackage("..pev.agent..")
                .because("kernel 是决策核心，不得依赖 agent 编排层（可独立复用保证）");
        rule.check(pevClasses);
    }

    /**
     * kernel 不得依赖 observability——单向：observability → kernel。
     */
    @Test
    void kernelMustNotDependOnObservability() {
        ArchRule rule = noClasses().that().resideInAPackage("..pev.kernel..")
                .should().dependOnClassesThat().resideInAPackage("..pev.observability..")
                .because("kernel 不得依赖 observability（单向：observability → kernel）");
        rule.check(pevClasses);
    }

    /**
     * observability 不得依赖 agent——xuefanfan gap 治本规则。
     *
     * <p>PevTrace.Planned 从包装 PevComponents.Plan（agent 包）改为
     * String goal + List&lt;NodeSnapshot&gt;（observability 本地投影）后，此规则 GREEN。
     */
    @Test
    void observabilityMustNotDependOnAgent() {
        ArchRule rule = noClasses().that().resideInAPackage("..pev.observability..")
                .should().dependOnClassesThat().resideInAPackage("..pev.agent..")
                .because("L1/L2 承诺 observability 只依赖 kernel；"
                        + "xuefanfan gap 已用 NodeSnapshot 投影修复，此规则防止回退");
        rule.check(pevClasses);
    }

    /**
     * rail 不得依赖 agent——rail 只观测 kernel 类型，不改控制流本体。
     */
    @Test
    void railMustNotDependOnAgent() {
        ArchRule rule = noClasses().that().resideInAPackage("..pev.rail..")
                .should().dependOnClassesThat().resideInAPackage("..pev.agent..")
                .because("rail 只观测 kernel 类型，不依赖 agent 编排层");
        rule.check(pevClasses);
    }
}
