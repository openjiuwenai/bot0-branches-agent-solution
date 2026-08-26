/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RedisTrajectoryStore 的单元测试：key 空间构建（Base64-url 编码纪律）、记录/索引读写、
 * seq 预占两步、latest 推进与前缀扫描。
 */
class RedisTrajectoryStoreTest {
    private final FakeRedisClient client = new FakeRedisClient();
    private final RedisTrajectoryStore store = new RedisTrajectoryStore(client, 3600L);

    @Test
    void runKeyEncodesClientControlledSegments() {
        // runId 含 '#':（task#1），必须编码后入 key，不得出现原始分隔符
        String key = RedisTrajectoryStore.runKey("task*1#1");
        assertThat(key).startsWith("runtime:run:");
        assertThat(key).doesNotContain("*").doesNotContain("#").doesNotContain("?");
    }

    @Test
    void auditKeysFollowKeySpace() {
        assertThat(RedisTrajectoryStore.auditKey("t1", "c1", "00000003")).startsWith("runtime:audit:");
        assertThat(RedisTrajectoryStore.auditLatestKey("t1", "c1")).endsWith(":latest");
        assertThat(RedisTrajectoryStore.auditDecisionKey("t1", "c1", "00000003", "0007"))
                .startsWith("runtime:audit-dec:");
        assertThat(RedisTrajectoryStore.seq8(3)).isEqualTo("00000003");
        assertThat(RedisTrajectoryStore.seq4(7)).isEqualTo("0007");
    }

    @Test
    void putAndGetRecordRoundTrips() {
        String key = RedisTrajectoryStore.runKey("task-1#1");
        store.putRecord(key, "{\"kind\":\"local\"}");
        assertThat(store.getRecord(key)).contains("{\"kind\":\"local\"}");
        assertThat(client.lastTtl).isEqualTo(3600L);
    }

    @Test
    void allocateSeqOnlyOnceAndSetsExpire() {
        assertThat(store.allocateSeq("seq:k", "00000001", 60L)).isTrue();
        assertThat(store.allocateSeq("seq:k", "00000002", 60L)).isFalse();
        assertThat(client.expireCalls).containsKey("seq:k");
    }

    @Test
    void advanceLatestOverwritesAndRefreshesTtl() {
        store.advanceLatest("latest:k", "00000007");
        assertThat(client.stringValues).containsEntry("latest:k", "00000007");
        assertThat(client.expireCalls).containsEntry("latest:k", 3600L);
    }

    @Test
    void existsAndScanDelegateToClient() {
        store.putRecord(RedisTrajectoryStore.runKey("task-1#1"), "v");
        assertThat(store.exists(RedisTrajectoryStore.runKey("task-1#1"))).isTrue();
        assertThat(store.scan("runtime:run:*")).hasSize(1);
    }

    /** 内存版 RuntimeRedisClient（仅测试用）：HashMap 承载，TTL 记录不生效。 */
    private static final class FakeRedisClient implements RuntimeRedisClient {
        final Map<String, String> stringValues = new HashMap<>();
        final Map<String, Long> expireCalls = new HashMap<>();
        long lastTtl;

        @Override
        public Object get(String key) {
            return stringValues.get(key);
        }

        @Override
        public byte[] get(byte[] key) {
            String value = stringValues.get(new String(key, StandardCharsets.UTF_8));
            return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String set(String key, String value) {
            stringValues.put(key, value);
            return "OK";
        }

        @Override
        public String set(String key, byte[] value) {
            stringValues.put(key, new String(value, StandardCharsets.UTF_8));
            return "OK";
        }

        @Override
        public String set(byte[] key, byte[] value) {
            stringValues.put(new String(key, StandardCharsets.UTF_8), new String(value, StandardCharsets.UTF_8));
            return "OK";
        }

        @Override
        public String setex(String key, long seconds, String value) {
            lastTtl = seconds;
            stringValues.put(key, value);
            return "OK";
        }

        @Override
        public String setex(byte[] key, long seconds, byte[] value) {
            lastTtl = seconds;
            stringValues.put(new String(key, StandardCharsets.UTF_8), new String(value, StandardCharsets.UTF_8));
            return "OK";
        }

        @Override
        public long setnx(String key, String value) {
            return stringValues.putIfAbsent(key, value) == null ? 1L : 0L;
        }

        @Override
        public long setnx(byte[] key, byte[] value) {
            return setnx(new String(key, StandardCharsets.UTF_8), new String(value, StandardCharsets.UTF_8));
        }

        @Override
        public long del(String... keys) {
            long removed = 0;
            for (String key : keys) {
                if (stringValues.remove(key) != null) {
                    removed++;
                }
            }
            return removed;
        }

        @Override
        public long del(byte[]... keys) {
            long removed = 0;
            for (byte[] key : keys) {
                if (stringValues.remove(new String(key, StandardCharsets.UTF_8)) != null) {
                    removed++;
                }
            }
            return removed;
        }

        @Override
        public boolean exists(String key) {
            return stringValues.containsKey(key);
        }

        @Override
        public boolean exists(byte[] key) {
            return stringValues.containsKey(new String(key, StandardCharsets.UTF_8));
        }

        @Override
        public long expire(String key, long seconds) {
            expireCalls.put(key, seconds);
            return stringValues.containsKey(key) ? 1L : 0L;
        }

        @Override
        public long expire(byte[] key, long seconds) {
            return expire(new String(key, StandardCharsets.UTF_8), seconds);
        }

        @Override
        public List<Object> mget(String... keys) {
            List<Object> values = new ArrayList<>();
            for (String key : keys) {
                values.add(stringValues.get(key));
            }
            return values;
        }

        @Override
        public List<String> scanIter(String pattern) {
            String prefix = pattern.replace("*", "");
            List<String> keys = new ArrayList<>();
            for (String key : stringValues.keySet()) {
                if (key.startsWith(prefix)) {
                    keys.add(key);
                }
            }
            return keys;
        }
    }
}
