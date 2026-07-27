/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the fixture index and serves document bodies from the classpath.
 *
 * <p>The fixture bundle layout is:
 * <pre>
 *   {fixturesPrefix}index.json
 *   {fixturesPrefix}docs/&lt;file&gt;.md
 * </pre>
 *
 * @since 2026-07-07
 */
public class DocumentFixtureStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX_FILENAME = "index.json";

    private final String prefix;
    private final Map<String, DocumentIndexEntry> byUri;
    private final List<DocumentIndexEntry> ordered;

    /**
     * Builds the store by parsing {@code {prefix}index.json} on the classpath.
     *
     * @param fixturesClasspath classpath prefix (must end with {@code /})
     */
    public DocumentFixtureStore(String fixturesClasspath) {
        this.prefix = fixturesClasspath;
        this.ordered = loadIndex(fixturesClasspath);
        Map<String, DocumentIndexEntry> index = new LinkedHashMap<>();
        for (DocumentIndexEntry entry : ordered) {
            index.put(entry.uri(), entry);
        }
        this.byUri = index;
    }

    /**
     * Lists all indexed documents in declaration order.
     *
     * @return an unmodifiable view of the fixture index
     */
    public List<DocumentIndexEntry> list() {
        return List.copyOf(ordered);
    }

    /**
     * Resolves an entry by URI.
     *
     * @param uri MCP resource URI
     * @return the entry, if known
     */
    public Optional<DocumentIndexEntry> findByUri(String uri) {
        return Optional.ofNullable(byUri.get(uri));
    }

    /**
     * Reads a document's raw markdown body from the classpath.
     *
     * @param entry the index entry
     * @return the UTF-8 body
     * @throws IOException if the fixture cannot be read
     */
    public String read(DocumentIndexEntry entry) throws IOException {
        Resource resource = new ClassPathResource(prefix + entry.path());
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Keyword-scores each index entry against the given query and returns the top matches.
     *
     * <p>Scoring is intentionally simple (substring hits on title/summary/vendors/dimensions)
     * so no external search engine is required. Deterministic, stable for tests.
     *
     * @param query natural-language query
     * @param topK maximum number of matches to return (0 or negative → all)
     * @return sorted matches, highest score first
     */
    public List<ScoredMatch> search(String query, int topK) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<ScoredMatch> scored = new ArrayList<>();
        for (DocumentIndexEntry entry : ordered) {
            int score = scoreEntry(entry, needle);
            if (score > 0 || needle.isBlank()) {
                scored.add(new ScoredMatch(entry, score));
            }
        }
        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        if (topK > 0 && scored.size() > topK) {
            return new ArrayList<>(scored.subList(0, topK));
        }
        return scored;
    }

    private static int scoreEntry(DocumentIndexEntry entry, String needle) {
        if (needle.isBlank()) {
            return 0;
        }
        int score = 0;
        if (entry.title().toLowerCase(Locale.ROOT).contains(needle)) {
            score += 5;
        }
        if (entry.summary().toLowerCase(Locale.ROOT).contains(needle)) {
            score += 3;
        }
        for (String vendor : entry.vendors()) {
            if (needle.contains(vendor.toLowerCase(Locale.ROOT))
                    || vendor.toLowerCase(Locale.ROOT).contains(needle)) {
                score += 4;
            }
        }
        for (String dimension : entry.dimensions()) {
            if (needle.contains(dimension.toLowerCase(Locale.ROOT))) {
                score += 2;
            }
        }
        return score;
    }

    private static List<DocumentIndexEntry> loadIndex(String fixturesClasspath) {
        Resource resource = new ClassPathResource(fixturesClasspath + INDEX_FILENAME);
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> root = MAPPER.readValue(in, new TypeReference<>() {
            });
            Object docs = root.get("documents");
            if (!(docs instanceof List<?> rawList)) {
                throw new IllegalStateException("Fixture index missing 'documents' array: " + resource);
            }
            List<DocumentIndexEntry> entries = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof Map<?, ?> raw) {
                    entries.add(toEntry(raw));
                }
            }
            return entries;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load fixture index from " + resource, e);
        }
    }

    private static DocumentIndexEntry toEntry(Map<?, ?> raw) {
        return new DocumentIndexEntry(
                stringField(raw, "uri"),
                stringField(raw, "path"),
                stringField(raw, "title"),
                stringField(raw, "summary"),
                stringList(raw.get("vendors")),
                stringList(raw.get("dimensions")),
                stringField(raw, "mime_type"));
    }

    private static String stringField(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    /**
     * Scored match returned by {@link #search(String, int)}.
     */
    public static final class ScoredMatch {
        private final DocumentIndexEntry entry;
        private final int score;

        /**
         * Builds a scored match.
         *
         * @param entry the matched index entry
         * @param score the numeric score (higher = better)
         */
        public ScoredMatch(DocumentIndexEntry entry, int score) {
            this.entry = entry;
            this.score = score;
        }

        /**
         * Gets the matched index entry.
         *
         * @return the entry
         */
        public DocumentIndexEntry entry() {
            return entry;
        }

        /**
         * Gets the numeric score.
         *
         * @return the score
         */
        public int score() {
            return score;
        }
    }
}
