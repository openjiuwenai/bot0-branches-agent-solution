/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.travel.hotel;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.sysop.OperationMode;
import com.openjiuwen.core.sysop.SysOperationCard;
import com.openjiuwen.core.sysop.config.LocalWorkConfig;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.example.travel.hotel.mock.MockHotelInventory;
import com.openjiuwen.example.travel.hotel.prompt.SystemPromptBuilder;
import com.openjiuwen.example.travel.hotel.tool.HotelDetailTool;
import com.openjiuwen.example.travel.hotel.tool.HotelSearchTool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.List;
import java.util.Map;

/**
 * Travel hotel leaf agent — local ReActAgent wired with the real hotel_search / hotel_detail
 * tools backed by {@link MockHotelInventory}, and a server-side system prompt built by
 * {@link SystemPromptBuilder} ({today} injected in Asia/Shanghai).
 * scanBasePackages picks up agent-service-app controllers/autoconfig (REST + A2A endpoints).
 *
 * @since 2026-07-09
 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
@EnableConfigurationProperties(TravelHotelLlmProperties.class)
public class TravelHotelApplication {
    private static final Logger log = LoggerFactory.getLogger(TravelHotelApplication.class);
    private static final String AGENT_ID = "travel-hotel";
    private static final String SKILLHUB_REMOTE_PROFILE = "skillhub-remote";
    private static final String SKILLHUB_LOCAL_DIR_PROPERTY =
            "openjiuwen.service.middleware.skillhub.local-dir";
    private static final String SKILL_SYS_OPERATION_ID = "travel_hotel_skillhub_local_sysop";

    public static void main(String[] args) {
        SpringApplication.run(TravelHotelApplication.class, args);
    }

    @Bean
    AgentHandler travelHotelHandler(TravelHotelLlmProperties props, Environment environment) {
        if (!props.isConfigured()) {
            log.warn("LLM not configured (openjiuwen.travel.hotel.llm.*); agent boots but cannot serve real queries");
        }
        boolean skillHubRemote = environment.acceptsProfiles(Profiles.of(SKILLHUB_REMOTE_PROFILE));
        String skillHubLocalDir = environment.getProperty(SKILLHUB_LOCAL_DIR_PROPERTY);
        ReActAgent agent = buildHotelAgent(props, skillHubRemote, skillHubLocalDir);
        if (skillHubRemote) {
            log.info("Using AgentCore extension handler for remote Skill Hub");
            return new JiuwenCoreAgentExtHandler(agent);
        }
        return new JiuwenCoreAgentHandler(agent);
    }

    static ReActAgent buildHotelAgent(TravelHotelLlmProperties props, boolean skillHubRemote,
            String skillHubLocalDir) {
        // Inventory loads mock/hotels.json from the classpath at construction; throws at boot
        // if the resource is missing.
        MockHotelInventory inventory = new MockHotelInventory();
        log.info("Loaded hotel inventory: {} hotels across {} cities",
                inventory.totalHotels(), inventory.totalCities());

        HotelSearchTool search = new HotelSearchTool(inventory);
        HotelDetailTool detail = new HotelDetailTool(inventory);

        AgentCard card = AgentCard.builder()
                .id(AGENT_ID).name(AGENT_ID)
                .description("差旅酒店规划智能体（叶子，本地 mock 工具）")
                .build();
        ReActAgent agent = new ReActAgent(card);
        ReActAgentConfig config = ReActAgentConfig.builder()
                .maxIterations(props.getMaxIterations())
                .promptTemplate(List.of(Map.of("role", "system", "content", SystemPromptBuilder.build())))
                .build()
                .configureModelClient(props.getProvider(), props.getApiKey(), props.getApiBase(),
                        props.getModelName(), props.isSslVerify());
        if (skillHubRemote) {
            configureSkillRuntime(config, skillHubLocalDir);
        }
        agent.configure(config);
        registerTool(agent, search);
        registerTool(agent, detail);
        if (skillHubRemote) {
            addSkillReadFileTool(agent);
        }
        return agent;
    }

    private static void configureSkillRuntime(ReActAgentConfig config, String skillHubLocalDir) {
        String workDir = skillHubLocalDir == null || skillHubLocalDir.isBlank() ? null : skillHubLocalDir;
        SysOperationCard sysOperation = SysOperationCard.builder()
                .id(SKILL_SYS_OPERATION_ID)
                .mode(OperationMode.LOCAL)
                .workConfig(LocalWorkConfig.builder().workDir(workDir).build())
                .build();
        var result = Runner.resourceMgr().addSysOperation(sysOperation, null);
        if (result == null || !result.isOk()) {
            throw new IllegalStateException("Failed to register LOCAL sysop for remote Skill Hub: " + result);
        }
        config.setSysOperationId(sysOperation.getId());
    }

    private static void addSkillReadFileTool(ReActAgent agent) {
        Object readFile = Runner.resourceMgr().getSysOpToolCards(SKILL_SYS_OPERATION_ID, "fs", "readFile");
        if (readFile == null) {
            throw new IllegalStateException("LOCAL sysop did not provide the readFile tool");
        }
        agent.getAbilityManager().add(readFile);
    }

    /**
     * Register a real {@link Tool} so it is both executable (ResourceMgr) and visible to the
     * LLM (AbilityManager card). AbilityManager.add(card) alone only stores the schema; the
     * executor must also be registered via Runner.resourceMgr().addTool(...), or execution
     * throws "Tool instance not found in resource_mgr". Mirrors the ascend helper: defensive
     * removeTool first (clears any leftover instance), then addTool + card add.
     *
     * @param agent the ReAct agent whose ResourceMgr and AbilityManager the tool is registered into
     * @param tool  the tool to register (executor + card)
     */
    private static void registerTool(ReActAgent agent, Tool tool) {
        String agentId = agent.getCard().getId();
        Runner.resourceMgr().removeTool(tool.getCard().getId(), agentId, TagMatchStrategy.ALL, true);
        Runner.resourceMgr().addTool(tool, agentId);
        agent.getAbilityManager().add(tool.getCard());
    }
}
