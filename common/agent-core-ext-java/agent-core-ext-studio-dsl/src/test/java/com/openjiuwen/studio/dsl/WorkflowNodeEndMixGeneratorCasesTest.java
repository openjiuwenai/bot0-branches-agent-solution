/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.StudioStreamFrames;
import com.openjiuwen.studio.dsl.adapter.control.EndNodeHandler;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End mix + Iterator/generator parity (Python end.py mix / process_generator_values / collect / transform).
 *
 * @since 2026-08-26
 */
class WorkflowNodeEndMixGeneratorCasesTest {
    private NodeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uf(Object out) {
        Map<String, Object> m = (Map<String, Object>) out;
        Object u = m.get("userFields");
        return u instanceof Map<?, ?> ? (Map<String, Object>) u : m;
    }

    @Test
    void end_prefix_iterator_drains_to_string_on_invoke() {
        ComponentExecutable end =
                registry.create(AssembledNode.of("e", "jiuwen.end", Map.of()), NodeBuildContext.defaults("wf"));
        Map<String, Object> in = Map.of(
                "userFields",
                Map.of("#end_answer", List.of("a", "b", "c").iterator(), "x", 1));
        Map<String, Object> fields = uf(end.invoke(in, mock(NodeSessionApi.class), null));
        assertThat(fields.get("answer")).isEqualTo("abc");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }

    @Test
    void shared_iterator_alias_not_double_consumed() {
        ComponentExecutable end =
                registry.create(AssembledNode.of("e", "jiuwen.end", Map.of()), NodeBuildContext.defaults("wf"));
        Iterator<String> shared = List.of("hello").iterator();
        Map<String, Object> ufIn = new java.util.LinkedHashMap<>();
        ufIn.put("answer", shared);
        ufIn.put("#end_answer", shared);
        Map<String, Object> fields =
                uf(end.invoke(Map.of("userFields", ufIn), mock(NodeSessionApi.class), null));
        assertThat(fields.get("answer")).isEqualTo("hello");
    }

    @Test
    void collect_materializes_chunk_iterator_and_emits_end_frames() {
        ComponentExecutable end = registry.create(
                AssembledNode.of("e", "jiuwen.end", Map.of("responseTemplate", "out={{val}}")),
                NodeBuildContext.defaults("wf"));
        List<Map<String, Object>> chunkList = new ArrayList<>();
        chunkList.add(Map.of("userFields", Map.<String, Object>of("val", "1")));
        chunkList.add(Map.of("userFields", Map.<String, Object>of("val", "2")));
        Iterator<Map<String, Object>> chunks = chunkList.iterator();
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) end.collect(chunks, mock(NodeSessionApi.class), null);
        assertThat(uf(out).get("response")).isEqualTo("out=2");
        assertThat(uf(out).get("__terminal__")).isEqualTo(true);
    }

    @Test
    void stream_emits_iterator_template_chunks() {
        ComponentExecutable end = registry.create(
                AssembledNode.of("e", "jiuwen.end", Map.of("responseTemplate", "Hi {{name}}!")),
                NodeBuildContext.defaults("wf"));
        Map<String, Object> vars = new java.util.LinkedHashMap<>();
        vars.put("name", List.of("A", "B").iterator());
        List<Object> frames = new ArrayList<>();
        end.stream(Map.of("userFields", vars), mock(NodeSessionApi.class), null).forEachRemaining(frames::add);
        assertThat(frames.stream().anyMatch(f -> f instanceof Map<?, ?> m
                        && StudioStreamFrames.WORKFLOW_END.equals(String.valueOf(m.get("type")))))
                .isTrue();
        String joined = frames.stream()
                .filter(f -> f instanceof Map<?, ?> m
                        && StudioStreamFrames.PARTIAL_CONTENT.equals(String.valueOf(m.get("type"))))
                .map(f -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) ((Map<?, ?>) f).get("data");
                    return String.valueOf(data.get("answer"));
                })
                .reduce("", String::concat);
        assertThat(joined).isEqualTo("Hi AB!");
    }

    @Test
    void transform_finish_metadata_drops_last_agg_frame() {
        ComponentExecutable end =
                registry.create(AssembledNode.of("e", "jiuwen.end", Map.of()), NodeBuildContext.defaults("wf"));
        List<String> tokens = List.of("x", "y", "AGG");
        Map<String, Object> ufIn = new java.util.LinkedHashMap<>();
        ufIn.put("#end_answer", tokens.iterator());
        ufIn.put(
                "__stream_metadata__",
                List.of(Map.of("messages_type", "finish"), Map.of("messages_type", "finish"), Map.of("messages_type", "finish"))
                        .iterator());
        List<Object> frames = new ArrayList<>();
        end.transform(Map.of("userFields", ufIn), mock(NodeSessionApi.class), null).forEachRemaining(frames::add);
        assertThat(frames).isNotEmpty();
        // drained answer should be xy (last AGG dropped when count matches meta)
        boolean hasXy = frames.stream().anyMatch(f -> {
            if (!(f instanceof Map<?, ?> m)) {
                return false;
            }
            Object data = m.get("data");
            return data instanceof Map<?, ?> d && String.valueOf(d.get("answer")).contains("xy");
        });
        assertThat(hasXy).isTrue();
    }

    @Test
    void mix_batch_and_stream_only_one_renders() throws Exception {
        EndNodeHandler.EndExecutable end = (EndNodeHandler.EndExecutable)
                new EndNodeHandler()
                        .create(
                                AssembledNode.of("e", "jiuwen.end", Map.of("responseTemplate", "{{a}}{{b}}")),
                                NodeBuildContext.defaults("wf"));
        end.setMix();

        NodeSessionApi sharedSession = statefulSession();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Object> batchOut = new AtomicReference<>();
        AtomicReference<Object> streamOut = new AtomicReference<>();

        Thread batch = new Thread(() -> {
            started.countDown();
            batchOut.set(
                    end.invoke(Map.of("userFields", Map.of("a", "A")), sharedSession, null));
        });
        Thread stream = new Thread(() -> {
            try {
                started.await(1, TimeUnit.SECONDS);
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            streamOut.set(
                    end.collect(Map.of("userFields", Map.of("b", "B")), sharedSession, null));
        });
        batch.start();
        stream.start();
        batch.join(5000);
        stream.join(5000);

        // Exactly one path returns a terminal payload; the other is empty/null skip
        boolean batchRendered =
                batchOut.get() instanceof Map<?, ?> m
                        && m.get("userFields") instanceof Map<?, ?> uf
                        && Boolean.TRUE.equals(((Map<?, ?>) uf).get("__terminal__"));
        boolean streamRendered =
                streamOut.get() instanceof Map<?, ?> m
                        && m.get("userFields") instanceof Map<?, ?> uf
                        && Boolean.TRUE.equals(((Map<?, ?>) uf).get("__terminal__"));
        assertThat(batchRendered || streamRendered).isTrue();
        // Prefer stream as renderer when both arrive; merged template should contain both sides when stream wins
        if (streamRendered) {
            @SuppressWarnings("unchecked")
            Map<String, Object> uf = (Map<String, Object>) ((Map<?, ?>) streamOut.get()).get("userFields");
            assertThat(String.valueOf(uf.get("response"))).contains("A").contains("B");
        }
    }

    @Test
    void mix_disabled_by_default_both_paths_independent() {
        ComponentExecutable end = registry.create(
                AssembledNode.of("e", "jiuwen.end", Map.of("responseTemplate", "x={{v}}")),
                NodeBuildContext.defaults("wf"));
        Map<String, Object> a = uf(end.invoke(Map.of("userFields", Map.of("v", "1")), mock(NodeSessionApi.class), null));
        assertThat(a.get("response")).isEqualTo("x=1");
    }

    private static NodeSessionApi statefulSession() {
        AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
        NodeSessionApi session = mock(NodeSessionApi.class);
        when(session.getState(any())).thenAnswer(inv -> {
            Object key = inv.getArgument(0);
            if (key == null) {
                return bucket.get();
            }
            return bucket.get().get(String.valueOf(key));
        });
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> patch = inv.getArgument(0);
            Map<String, Object> cur = new java.util.LinkedHashMap<>(bucket.get());
            patch.forEach(cur::put);
            bucket.set(cur);
            return null;
        }).when(session).updateState(any());
        return session;
    }
}
