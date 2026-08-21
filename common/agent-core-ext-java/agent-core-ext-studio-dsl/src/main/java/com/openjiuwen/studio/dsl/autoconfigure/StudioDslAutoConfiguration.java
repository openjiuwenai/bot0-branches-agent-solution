package com.openjiuwen.studio.dsl.autoconfigure;

import com.openjiuwen.studio.dsl.StudioDslModule;
import com.openjiuwen.studio.dsl.config.StudioDslNodeProperties;
import com.openjiuwen.studio.dsl.config.StudioDslProperties;
import com.openjiuwen.studio.dsl.exec.WorkflowAssemblyBridge;
import com.openjiuwen.studio.dsl.python.SubprocessPythonCodeExecutor;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.spi.PythonCodeExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for agent-core-ext-studio-dsl (L2 §4.7 / §6.3).
 * Enable with {@code studio-dsl.enabled=true} (default true when autoconfig is on classpath).
 */
@AutoConfiguration
@EnableConfigurationProperties
@ConditionalOnProperty(prefix = "studio-dsl", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StudioDslAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "studio-dsl")
    @ConditionalOnMissingBean
    public StudioDslProperties studioDslProperties() {
        return new StudioDslProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    public StudioDslNodeProperties studioDslNodeProperties(StudioDslProperties properties) {
        return properties.toNodeProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    public StudioDslModule studioDslModule(StudioDslNodeProperties nodeProperties) {
        return StudioDslModule.create(nodeProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public NodeTypeRegistry nodeTypeRegistry(StudioDslModule module) {
        return module.registry();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowAssemblyBridge workflowAssemblyBridge(StudioDslModule module) {
        return module.assemblyBridge();
    }

    @Bean
    @ConditionalOnMissingBean
    public PythonCodeExecutor pythonCodeExecutor(StudioDslModule module) {
        PythonCodeExecutor existing = module.pythonExecutor();
        return existing != null ? existing : new SubprocessPythonCodeExecutor();
    }
}
