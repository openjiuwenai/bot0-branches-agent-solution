/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * react-rails intra-module package-direction bearing test (mirrors PEV's PackageDependencyArchTest).
 * ArchUnit elevates the package-info layering declarations into CI-enforced contracts — the
 * structural guard that would have caught a PEV-style observability→agent intra-module leak.
 *
 * <p>Two rules pin the layering declared in {@code observability/package-info.java} +
 * {@code enforcing/package-info.java}:
 * <ul>
 *   <li>observability is an intra-module leaf — must not depend on rail/enforcing packages.</li>
 *   <li>enforcing must not depend on rail-LOGIC packages (verification/selfheal). replan is
 *       excluded — enforcing legitimately imports the {@code replan.ReplanTool.TOOL_NAME}
 *       compile-time constant (issue #16), which javac inlines and ArchUnit cannot see.</li>
 * </ul>
 *
 * <p>mutation-RED（非常量引用，非 bare import）：在 observability/enforcing 类加对 verification
 * 类的非常量引用（字段/方法调用）→ RED。注意：bare {@code import} 不 RED（javac 擦除未用 import）；
 * 编译期常量（{@code static final String}）也不 RED（javac 内联，字节码不可见）——这正是 replan 被排除的原因。
 *
 * @since 2026-08
 */
class PackageDependencyArchTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.openjiuwen.agents.reactrails");
    }

    /**
     * observability is an intra-module leaf: agent-core + JDK only, no intra-module rail/enforcing
     * dependencies. Prevents a future PevTrace→PevComponents-style upward leak.
     */
    @Test
    void observabilityIsIntraModuleLeaf() {
        ArchRule rule = noClasses().that().resideInAPackage("..reactrails.observability..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..reactrails.verification..", "..reactrails.replan..", "..reactrails.selfheal..",
                        "..reactrails.enforcing..", "..reactrails.state..", "..reactrails.types..")
                .because("observability is an intra-module leaf (agent-core + JDK only); "
                        + "rails/enforcing depend on it, not the reverse");
        rule.check(classes);
    }

    /**
     * enforcing must not depend on rail-LOGIC packages (verification/selfheal) — it is a
     * model-layer channel consuming agent-core + observability, not a rail. replan is
     * intentionally EXCLUDED: enforcing imports {@code replan.ReplanTool.TOOL_NAME} for the
     * {@code ${replan_tool}} single-source (issue #16), a compile-time constant javac inlines,
     * so the source-level edge is invisible to ArchUnit's bytecode analysis — a rule prohibiting
     * replan would pass despite the real dependency (a false bearing).
     */
    @Test
    void enforcingMustNotDependOnRailLogic() {
        ArchRule rule = noClasses().that().resideInAPackage("..reactrails.enforcing..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..reactrails.verification..", "..reactrails.selfheal..")
                .because("enforcing is a model-layer channel (agent-core + observability + the "
                        + "ReplanTool constant); it must not reach rail-LOGIC packages. replan is "
                        + "excluded — ReplanTool.TOOL_NAME is a compile-time constant ArchUnit can't see");
        rule.check(classes);
    }
}
