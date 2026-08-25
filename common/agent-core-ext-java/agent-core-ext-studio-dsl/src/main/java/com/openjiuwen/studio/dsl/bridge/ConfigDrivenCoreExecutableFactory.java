/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.bridge;

import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.retrieval.common.EmbeddingConfig;
import com.openjiuwen.core.retrieval.common.KnowledgeBaseConfig;
import com.openjiuwen.core.retrieval.common.RetrievalConfig;
import com.openjiuwen.core.retrieval.common.VectorStoreConfig;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.core.workflow.component.llm.IntentDetectionCompConfig;
import com.openjiuwen.core.workflow.component.llm.IntentDetectionExecutable;
import com.openjiuwen.core.workflow.component.llm.LLMCompConfig;
import com.openjiuwen.core.workflow.component.llm.LLMExecutable;
import com.openjiuwen.core.workflow.component.llm.QuestionerConfig;
import com.openjiuwen.core.workflow.component.llm.QuestionerExecutable;
import com.openjiuwen.core.workflow.component.resource.KnowledgeRetrievalCompConfig;
import com.openjiuwen.core.workflow.component.resource.KnowledgeRetrievalExecutable;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.contract.CoreExecutableFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds agent-core-java executables from Studio DSL configs when enough model/KB fields exist.
 * Returns null when configs are incomplete so handlers can fall back / mock.
 *
 * @since 2026-08-17
 */
public final class ConfigDrivenCoreExecutableFactory implements CoreExecutableFactory {
    /**
     * createLlm.
     *
     * @param node node
     * @return result
     */
    @Override
    public Optional<ComponentExecutable> createLlm(AssembledNode node) {
        Map<String, Object> c = node.configs();
        if (!hasModelWiring(c)) {
            return Optional.empty();
        }
        LLMCompConfig cfg = new LLMCompConfig();
        applyModel(cfg, c);
        Object sys = c.get("systemPrompt");
        if (sys != null) {
            cfg.setSystemPromptTemplate(new SystemMessage(String.valueOf(sys)));
        }
        Object user = c.getOrDefault("userPrompt", c.get("prompt"));
        if (user != null) {
            cfg.setUserPromptTemplate(new UserMessage(String.valueOf(user)));
        }
        Object templateContent = c.get("templateContent");
        if (templateContent instanceof List<?> list) {
            List<Map<String, Object>> parts = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    parts.add(cast(m));
                }
            }
            if (!parts.isEmpty()) {
                cfg.setTemplateContent(parts);
            }
        }
        return Optional.of(new LLMExecutable(cfg));
    }

    /**
     * createIntentDetection.
     *
     * @param node node
     * @return result
     */
    @Override
    public Optional<ComponentExecutable> createIntentDetection(AssembledNode node) {
        Map<String, Object> c = node.configs();
        if (!hasModelWiring(c)) {
            return Optional.empty();
        }
        IntentDetectionCompConfig cfg = new IntentDetectionCompConfig();
        applyModelToIntent(cfg, c);
        List<String> categories = categoryNames(c);
        if (!categories.isEmpty()) {
            cfg.setCategoryNameList(categories);
        }
        Object prompt = c.getOrDefault("userPrompt", c.get("prompt"));
        if (prompt != null) {
            cfg.setUserPrompt(String.valueOf(prompt));
        }
        return Optional.of(new IntentDetectionExecutable(cfg));
    }

    /**
     * createExtractor.
     *
     * @param node node
     * @return result
     */
    @Override
    public Optional<ComponentExecutable> createExtractor(AssembledNode node) {
        Map<String, Object> c = node.configs();
        if (!hasModelWiring(c)) {
            return Optional.empty();
        }
        // agent-core has no dedicated ExtractorExecutable — LLM with extraction prompt.
        LLMCompConfig cfg = new LLMCompConfig();
        applyModel(cfg, c);
        cfg.setSystemPromptTemplate(new SystemMessage(
                "Extract structured fields as a JSON object. Reply with JSON only."));
        String userPrompt = extractionUserPrompt(c);
        cfg.setUserPromptTemplate(new UserMessage(userPrompt));
        return Optional.of(new LLMExecutable(cfg));
    }

    /**
     * createKnowledgeRetrieval.
     *
     * @param node node
     * @return result
     */
    @Override
    public Optional<ComponentExecutable> createKnowledgeRetrieval(AssembledNode node) {
        Map<String, Object> c = node.configs();
        List<KnowledgeBaseConfig> kbs = buildKbConfigs(c);
        if (kbs.isEmpty()) {
            return Optional.empty();
        }
        KnowledgeRetrievalCompConfig cfg = new KnowledgeRetrievalCompConfig();
        cfg.setKbConfigs(kbs);
        if (hasModelWiring(c)) {
            applyModelToKnowledge(cfg, c);
        }
        applyRetrieval(cfg, c);
        applyEmbed(cfg, c);
        applyVectorStore(cfg, c);
        return Optional.of(new KnowledgeRetrievalExecutable(cfg));
    }

    /**
     * createQuestioner.
     *
     * @param node node
     * @return result
     */
    @Override
    public Optional<ComponentExecutable> createQuestioner(AssembledNode node) {
        Map<String, Object> c = node.configs();
        if (!hasModelWiring(c) && c.get("question") == null && c.get("questionContent") == null) {
            return Optional.empty();
        }
        if (!hasModelWiring(c)) {
            return Optional.empty();
        }
        QuestionerConfig cfg = new QuestionerConfig();
        applyModelToQuestioner(cfg, c);
        Object q = c.getOrDefault("questionContent", c.get("question"));
        if (q != null) {
            cfg.setQuestionContent(String.valueOf(q));
        }
        return Optional.of(new QuestionerExecutable(cfg));
    }

    private static List<String> categoryNames(Map<String, Object> c) {
        List<String> out = new ArrayList<>();
        Object intents = c.getOrDefault("intents", c.get("intentList"));
        if (intents instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    first(m, "intentId", "id", "name", "category").ifPresent(id -> out.add(String.valueOf(id)));
                } else if (item != null) {
                    out.add(String.valueOf(item));
                } else {
                    continue;
                }
            }
        }
        Object cats = c.get("categoryNameList");
        if (cats instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    private static String extractionUserPrompt(Map<String, Object> c) {
        Object explicit = c.getOrDefault("userPrompt", c.get("prompt"));
        if (explicit != null) {
            return String.valueOf(explicit);
        }
        Object fields = c.getOrDefault("fields", c.getOrDefault("extractFields", c.get("schema")));
        return "Extract these fields from the input text: "
                + (fields == null ? "all salient entities" : fields)
                + "\nInput:\n{{query}}{{text}}";
    }

    private static List<KnowledgeBaseConfig> buildKbConfigs(Map<String, Object> c) {
        List<KnowledgeBaseConfig> out = new ArrayList<>();
        Object raw = c.get("kbConfigs");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    first(m, "kbId", "knowledgeBaseId", "id")
                            .ifPresent(id -> out.add(new KnowledgeBaseConfig(String.valueOf(id))));
                } else if (item != null) {
                    out.add(new KnowledgeBaseConfig(String.valueOf(item)));
                } else {
                    continue;
                }
            }
        }
        Object single = c.getOrDefault("knowledgeBaseId", c.get("kbId"));
        if (single != null) {
            out.add(new KnowledgeBaseConfig(String.valueOf(single)));
        }
        return out;
    }

    private static void applyRetrieval(KnowledgeRetrievalCompConfig cfg, Map<String, Object> c) {
        Object raw = c.get("retrievalConfig");
        RetrievalConfig rc = new RetrievalConfig();
        Map<String, Object> src = raw instanceof Map<?, ?> m ? cast(m) : c;
        Object topK = src.getOrDefault("topK", c.get("topK"));
        if (topK instanceof Number n) {
            rc.setTopK(n.intValue());
            cfg.setRetrievalConfig(rc);
        } else if (raw instanceof Map<?, ?>) {
            cfg.setRetrievalConfig(rc);
        } else {
            return;
        }
        Object threshold = src.get("scoreThreshold");
        if (threshold instanceof Number n) {
            rc.setScoreThreshold(n.doubleValue());
            cfg.setRetrievalConfig(rc);
        }
    }

    private static void applyEmbed(KnowledgeRetrievalCompConfig cfg, Map<String, Object> c) {
        Object raw = c.get("embedConfig");
        Map<String, Object> src = raw instanceof Map<?, ?> m ? cast(m) : c;
        Object model = src.getOrDefault("modelName", src.getOrDefault("embeddingModel", c.get("model")));
        Object base = src.getOrDefault("baseUrl", src.getOrDefault("apiBase", c.get("apiBase")));
        Object key = src.getOrDefault("apiKey", c.get("apiKey"));
        if (model != null || base != null || key != null) {
            EmbeddingConfig ec = new EmbeddingConfig(
                    model == null ? "text-embedding" : String.valueOf(model),
                    base == null ? "" : String.valueOf(base),
                    key == null ? "" : String.valueOf(key));
            cfg.setEmbedConfig(ec);
        }
    }

    private static void applyVectorStore(KnowledgeRetrievalCompConfig cfg, Map<String, Object> c) {
        Object raw = c.get("vectorStoreConfig");
        if (!(raw instanceof Map<?, ?> m)) {
            return;
        }
        Map<String, Object> src = cast(m);
        String provider = String.valueOf(src.getOrDefault("storeProvider", src.getOrDefault("provider", "default")));
        String db = String.valueOf(src.getOrDefault("databaseName", src.getOrDefault("database", "")));
        VectorStoreConfig vsc = new VectorStoreConfig(provider, db);
        Object coll = src.get("collectionName");
        if (coll != null) {
            vsc.setCollectionName(String.valueOf(coll));
        }
        cfg.setVectorStoreConfig(vsc);
        Object additional = c.get("vectorStoreAdditionalConfig");
        if (additional instanceof Map<?, ?> am) {
            cfg.setVectorStoreAdditionalConfig(cast(am));
        }
    }

    private static boolean hasModelWiring(Map<String, Object> c) {
        if (c.containsKey("modelClientConfig") || c.containsKey("modelConfig")) {
            return true;
        }
        Object model = c.getOrDefault("model", c.get("modelId"));
        Object apiKey = c.get("apiKey");
        Object apiBase = c.getOrDefault("apiBase", c.get("baseUrl"));
        return model != null && (apiKey != null || apiBase != null || c.containsKey("clientProvider"));
    }

    private static void applyModel(LLMCompConfig cfg, Map<String, Object> c) {
        Object modelId = c.getOrDefault("modelId", c.get("model"));
        if (modelId != null) {
            cfg.setModelId(String.valueOf(modelId));
        }
        cfg.setModelClientConfig(buildClient(c));
        cfg.setModelConfig(buildRequest(c));
        applyResponseAndOutputs(cfg, c);
    }

    /**
     * Core {@code OutputFormatter} requires responseFormat.type + a single-field outputConfig for text.
     * Without defaults, a live model call succeeds then NPEs on format (empty type).
     *
     * @param cfg cfg
     * @param c c
     */
    @SuppressWarnings("unchecked")
    private static void applyResponseAndOutputs(LLMCompConfig cfg, Map<String, Object> c) {
        Object rf = c.getOrDefault("responseFormat", c.get("response_format"));
        if (rf instanceof Map<?, ?> m && !m.isEmpty()) {
            cfg.setResponseFormat(cast(m));
        } else {
            if (cfg.getResponseFormat() == null || cfg.getResponseFormat().isEmpty()) {
                Map<String, Object> def = new LinkedHashMap<>();
                def.put("type", "text");
                cfg.setResponseFormat(def);
            }
        }
        Object oc = c.getOrDefault("outputConfig", c.getOrDefault("outputs", c.get("outputs_config")));
        if (oc instanceof Map<?, ?> m && !m.isEmpty()) {
            cfg.setOutputConfig(cast(m));
        } else {
            if (cfg.getOutputConfig() == null || cfg.getOutputConfig().isEmpty()) {
                Map<String, Object> def = new LinkedHashMap<>();
                def.put("text", Map.of("type", "string"));
                cfg.setOutputConfig(def);
            }
        }
    }

    private static void applyModelToIntent(IntentDetectionCompConfig cfg, Map<String, Object> c) {
        Object modelId = c.getOrDefault("modelId", c.get("model"));
        if (modelId != null) {
            cfg.setModelId(String.valueOf(modelId));
        }
        cfg.setModelClientConfig(buildClient(c));
        cfg.setModelConfig(buildRequest(c));
    }

    private static void applyModelToKnowledge(KnowledgeRetrievalCompConfig cfg, Map<String, Object> c) {
        Object modelId = c.getOrDefault("modelId", c.get("model"));
        if (modelId != null) {
            cfg.setModelId(String.valueOf(modelId));
        }
        cfg.setModelClientConfig(buildClient(c));
        cfg.setModelConfig(buildRequest(c));
    }

    private static void applyModelToQuestioner(QuestionerConfig cfg, Map<String, Object> c) {
        Object modelId = c.getOrDefault("modelId", c.get("model"));
        if (modelId != null) {
            cfg.setModelId(String.valueOf(modelId));
        }
        cfg.setModelClientConfig(buildClient(c));
        cfg.setModelConfig(buildRequest(c));
    }

    private static ModelClientConfig buildClient(Map<String, Object> c) {
        Object nested = c.get("modelClientConfig");
        ModelClientConfig.Builder b = ModelClientConfig.builder();
        if (nested instanceof Map<?, ?> m) {
            putClient(b, cast(m));
        } else {
            putClient(b, c);
        }
        return b.build();
    }

    private static void putClient(ModelClientConfig.Builder b, Map<String, Object> c) {
        if (c.get("apiKey") != null) {
            b.apiKey(String.valueOf(c.get("apiKey")));
        }
        Object base = c.getOrDefault("apiBase", c.get("baseUrl"));
        if (base != null) {
            b.apiBase(String.valueOf(base));
        }
        Object provider = c.getOrDefault("clientProvider", c.get("provider"));
        b.clientProvider(provider == null ? "OpenAI" : String.valueOf(provider));
        Object clientId = c.get("clientId");
        if (clientId != null) {
            b.clientId(String.valueOf(clientId));
        } else if (c.get("model") != null || c.get("modelId") != null) {
            b.clientId(String.valueOf(c.getOrDefault("modelId", c.get("model"))));
        } else {
            b.clientId("studio-dsl");
        }
    }

    private static ModelRequestConfig buildRequest(Map<String, Object> c) {
        Object nested = c.get("modelConfig");
        Map<String, Object> src = nested instanceof Map<?, ?> m ? cast(m) : c;
        ModelRequestConfig.ModelRequestConfigBuilder b = ModelRequestConfig.builder();
        Object name = src.getOrDefault("modelName", src.getOrDefault("model", c.get("model")));
        if (name != null) {
            b.modelName(String.valueOf(name));
        }
        Object temp = src.get("temperature");
        if (temp instanceof Number n) {
            b.temperature(n.doubleValue());
        }
        Object max = src.get("maxTokens");
        if (max instanceof Number n) {
            b.maxTokens(n.intValue());
        }
        return b.build();
    }

    private static Optional<Object> first(Map<?, ?> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    private static Map<String, Object> cast(Map<?, ?> m) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
