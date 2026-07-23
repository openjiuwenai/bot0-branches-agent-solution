/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test against recorded Versatile SSE response samples (golden files).
 *
 * <p>Per L2 §5.5.5 row 5: loads {@code .sse} golden files from
 * {@code classpath:versatile-sse/}, replays each through
 * {@link VersatileResponseExtractor}, and asserts the extractor still produces
 * a non-error result (three-field answer envelope or explicit interrupt). When
 * the production Versatile is upgraded, re-running this test against
 * pre-recorded samples detects adapter-level incompatibilities before staging.
 *
 * <p><b>Golden file discipline (L2 §5.5.6):</b>
 * <ul>
 *   <li>Every {@code .sse} file MUST start with a {@code : provenance=...}
 *       comment line declaring its source ({@code synthetic} or
 *       {@code production-masked}). The test enforces this — files without
 *       the marker fail.</li>
 *   <li>Production recordings MUST be desensitized: strip
 *       {@code messages[].content}, user IDs, and business data from
 *       {@code response_content}. Synthetic files use {@code __SYNTHETIC_*}
 *       placeholders.</li>
 *   <li>Synthetic files (provenance=synthetic) are skeletons only — they keep
 *       the test compilable and document the expected shape, but do NOT
 *       detect real compatibility drift. Replace with production-masked
 *       recordings before relying on this test for upgrade detection.</li>
 * </ul>
 *
 * @since 2026-06-30
 */
class VersatileSseContractTest {
    private static final String GOLDEN_DIR = "classpath:versatile-sse/*.sse";

    @Test
    void goldenSamplesExtractWithoutError() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(GOLDEN_DIR);
        assertThat(resources)
                .as("versatile-sse/ must contain at least one .sse golden file")
                .isNotEmpty();

        for (Resource resource : resources) {
            String name = Objects.requireNonNull(resource.getFilename());
            List<String> lines = readLines(resource);
            assertProvenanceDeclared(name, lines);

            VersatileProperties props = threeFieldProperties();
            VersatileResponseExtractor extractor =
                    new VersatileResponseExtractor(props, new IntentAgentResolver(props));

            List<QueryChunk> chunks = new ArrayList<>();
            for (String line : lines) {
                chunks.addAll(extractor.consumeLine(line));
            }
            chunks.addAll(extractor.finish());

            assertThat(chunks)
                    .as("golden file %s must yield at least one chunk", name)
                    .isNotEmpty();
            assertThat(chunks)
                    .as("golden file %s must not yield TYPE_ERROR (extractor incompatible with sample)", name)
                    .extracting(QueryChunk::getType)
                    .doesNotContain(QueryChunk.TYPE_ERROR);
        }
    }

    private static void assertProvenanceDeclared(String name, List<String> lines) {
        assertThat(lines)
                .as("golden file %s must not be empty", name)
                .isNotEmpty();
        String first = lines.get(0).trim();
        assertThat(first)
                .as("golden file %s first line must be ': provenance=synthetic|production-masked'", name)
                .startsWith(": provenance=");
    }

    private static List<String> readLines(Resource resource) throws IOException {
        String content = resource.getContentAsString(StandardCharsets.UTF_8);
        return List.of(content.split("\n"));
    }

    private static VersatileProperties threeFieldProperties() {
        VersatileProperties p = new VersatileProperties();
        p.setResultNodeName("AnswerNode");
        addExtraction(p, "response_content", "/custom_rsp_data/data/response_content");
        addExtraction(p, "intent_id", "/custom_rsp_data/data/intent_id");
        addExtraction(p, "agent_id", "/custom_rsp_data/data/agent_id");
        VersatileProperties.MappingCandidate candidate = new VersatileProperties.MappingCandidate();
        candidate.setAgentCard("agent_card_L2_hotel");
        p.getIntentAgentMapping().put("intent_L1_hotel", List.of(candidate));
        p.getInterrupt().setSignalMatch("need_user_input");
        p.getInterrupt().setPromptGet("/data/question");
        p.getInterrupt().setInputRequirementGet("/data/input_schema");
        p.getInterrupt().setResumeTokenGet("/data/resume_token");
        return p;
    }

    private static void addExtraction(VersatileProperties p, String match, String get) {
        VersatileProperties.ResultExtraction e = new VersatileProperties.ResultExtraction();
        e.setMatch(match);
        e.setGet(get);
        p.getResultExtractions().add(e);
    }
}
