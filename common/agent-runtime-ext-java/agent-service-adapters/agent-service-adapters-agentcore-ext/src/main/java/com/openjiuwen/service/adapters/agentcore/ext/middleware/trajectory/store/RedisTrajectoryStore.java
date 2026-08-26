/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store;

import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.exceptions.JedisException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * 执行树/审计记录的 Redis key 空间封装（FEAT-003 {@link RuntimeRedisClient} 纯 KV 门面，
 * 无 list/append 原语——采用"每记录一 key + scanIter 前缀索引"）。
 *
 * <p>编码纪律：key 中所有客户端可控段（conversationId/tenantId/taskId/runId 等）一律
 * Base64-url（无填充）编码后入 key，防止 glob 元字符破坏 scanIter 前缀查询；编码覆盖本批
 * 全部 key 空间（含审计与索引 key）。TTL 纪律：索引 key 的 TTL 不短于记录 key。
 *
 * <p>seq 预占等需要立即可见结果的操作走同步路径（{@link #allocateSeq}）；记录本体的大批量
 * 写入由 {@link AsyncTrajectoryWriter} 异步刷写（背压丢弃，不阻塞执行线程）。
 *
 * @since 2026-08-26
 */
public class RedisTrajectoryStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisTrajectoryStore.class);

    private static final String RUN_PREFIX = "runtime:run:";
    private static final String RUN_EDGE_PREFIX = "runtime:run-edge:";
    private static final String IDX_TRACE_PREFIX = "runtime:run-idx:trace:";
    private static final String IDX_PARENT_PREFIX = "runtime:run-idx:parent:";
    private static final String IDX_CONV_PREFIX = "runtime:run-idx:conv:";
    private static final String AUDIT_PREFIX = "runtime:audit:";
    private static final String AUDIT_IDX_PREFIX = "runtime:audit-idx:";
    private static final String AUDIT_DEC_PREFIX = "runtime:audit-dec:";

    private static final Base64.Encoder KEY_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RuntimeRedisClient client;
    private final long recordTtlSeconds;

    /**
     * Creates the store.
     *
     * @param client           FEAT-003 Redis facade
     * @param recordTtlSeconds TTL applied to record keys (index keys never shorter)
     */
    public RedisTrajectoryStore(RuntimeRedisClient client, long recordTtlSeconds) {
        this.client = client;
        this.recordTtlSeconds = recordTtlSeconds;
    }

    /**
     * Builds the node record key for a run id. The run id's task segment is
     * Base64-url encoded while the {@code #roundSeq} separator stays literal, so the
     * per-task scan prefix ({@link #runKeyPrefix}) really is a prefix of the key
     * (Base64 whole-string encoding is NOT prefix-preserving).
     *
     * @param runId run id ({taskId}#{roundSeq})
     * @return redis key
     */
    public static String runKey(String runId) {
        return RUN_PREFIX + encodeRunId(runId);
    }

    /**
     * Builds the scan prefix covering all round node keys of one task.
     *
     * @param taskId task id
     * @return glob-ready key prefix (append {@code *} for scans)
     */
    public static String runKeyPrefix(String taskId) {
        return RUN_PREFIX + encode(taskId) + "#";
    }

    /**
     * Builds the delegation edge key.
     *
     * @param parentRunId parent run id (full form)
     * @param runId       child run id
     * @return redis key
     */
    public static String runEdgeKey(String parentRunId, String runId) {
        return RUN_EDGE_PREFIX + encodeRunId(parentRunId) + ":" + encodeRunId(runId);
    }

    /**
     * Builds the audit snapshot key.
     *
     * @param tenantId       tenant id
     * @param conversationId conversation id
     * @param seq            round seq (zero-padded to 8 by caller via {@link #seq8})
     * @return redis key
     */
    public static String auditKey(String tenantId, String conversationId, String seq) {
        return AUDIT_PREFIX + encode(tenantId) + ":" + encode(conversationId) + ":" + seq;
    }

    /**
     * Builds the audit latest-index key.
     *
     * @param tenantId       tenant id
     * @param conversationId conversation id
     * @return redis key
     */
    public static String auditLatestKey(String tenantId, String conversationId) {
        return AUDIT_IDX_PREFIX + encode(tenantId) + ":" + encode(conversationId) + ":latest";
    }

    /**
     * Builds the audit decision-record key.
     *
     * @param tenantId       tenant id
     * @param conversationId conversation id
     * @param seq            round seq (seq8 form)
     * @param decisionSeq    decision seq within the round (seq4 form)
     * @return redis key
     */
    public static String auditDecisionKey(String tenantId, String conversationId, String seq, String decisionSeq) {
        return AUDIT_DEC_PREFIX + encode(tenantId) + ":" + encode(conversationId) + ":" + seq + ":" + decisionSeq;
    }

    /**
     * Zero-pads a round seq to 8 digits (key ordering discipline).
     *
     * @param seq round seq
     * @return zero-padded seq
     */
    public static String seq8(long seq) {
        return String.format(java.util.Locale.ROOT, "%08d", seq);
    }

    /**
     * Zero-pads a decision seq to 4 digits.
     *
     * @param seq decision seq
     * @return zero-padded seq
     */
    public static String seq4(long seq) {
        return String.format(java.util.Locale.ROOT, "%04d", seq);
    }

    /**
     * Writes a record body with the record TTL (async path via writer, or direct).
     *
     * @param key   record key
     * @param value record JSON
     */
    public void putRecord(String key, String value) {
        client.setex(key, recordTtlSeconds, value);
    }

    /**
     * Writes an index key (empty value) with TTL not shorter than the record TTL.
     *
     * @param indexKey index key
     */
    public void putIndex(String indexKey) {
        client.setex(indexKey, recordTtlSeconds, "");
    }

    /**
     * Builds a trace index key.
     *
     * @param traceId trace id
     * @param runId   run id
     * @return index key
     */
    public static String traceIndexKey(String traceId, String runId) {
        return IDX_TRACE_PREFIX + encode(traceId) + ":" + encodeRunId(runId);
    }

    /**
     * Builds the scan prefix covering all run index entries of one trace.
     *
     * @param traceId trace id
     * @return glob-ready key prefix
     */
    public static String traceIndexPrefix(String traceId) {
        return IDX_TRACE_PREFIX + encode(traceId) + ":";
    }

    /**
     * Builds the scan prefix covering all child index entries of one parent run.
     *
     * @param parentRunId parent run id (full form)
     * @return glob-ready key prefix
     */
    public static String parentIndexPrefix(String parentRunId) {
        return IDX_PARENT_PREFIX + encodeRunId(parentRunId) + ":";
    }

    /**
     * Decodes one encoded run-id key segment back to its raw form — the inverse of
     * the encodeRunId discipline (task segment Base64-url, {@code #seq} literal).
     *
     * @param segment encoded run-id segment
     * @return raw run id
     */
    public static String decodeRunId(String segment) {
        int hash = segment.lastIndexOf('#');
        if (hash < 0) {
            return new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);
        }
        return new String(Base64.getUrlDecoder().decode(segment.substring(0, hash)),
                StandardCharsets.UTF_8) + "#" + segment.substring(hash + 1);
    }

    /**
     * Builds a parent index key.
     *
     * @param parentRunId parent run id (full form)
     * @param runId       child run id
     * @return index key
     */
    public static String parentIndexKey(String parentRunId, String runId) {
        return IDX_PARENT_PREFIX + encodeRunId(parentRunId) + ":" + encodeRunId(runId);
    }

    /**
     * Builds a conversation reverse-index key.
     *
     * @param conversationId conversation id
     * @param runId          run id
     * @return index key
     */
    public static String convIndexKey(String conversationId, String runId) {
        return IDX_CONV_PREFIX + encode(conversationId) + ":" + encode(runId);
    }

    /**
     * Reads a record body.
     *
     * @param key record key
     * @return record JSON, or empty
     */
    public Optional<String> getRecord(String key) {
        // 同步读在 filter/save 线程上执行——Redis 故障只 WARN 降级，不拖垮主流程
        try {
            Object value = client.get(key);
            // 实现可能返回 String 或 byte[]（接口 javadoc 明示两种形态）——统一归一到 String
            if (value instanceof String str) {
                return Optional.of(str);
            }
            if (value instanceof byte[] bytes) {
                return Optional.of(new String(bytes, StandardCharsets.UTF_8));
            }
            return Optional.empty();
        } catch (JedisException | IllegalStateException e) {
            LOGGER.warn("trajectory store read failed ({}), degraded to miss", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Checks whether a key exists (e.g. round-1 node key for restart recovery).
     *
     * @param key redis key
     * @return true when present
     */
    public boolean exists(String key) {
        try {
            return client.exists(key);
        } catch (JedisException | IllegalStateException e) {
            LOGGER.warn("trajectory store exists failed ({}), degraded to false", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Allocates a seq synchronously via setnx + expire (two-step; RuntimeRedisClient has
     * no SET-NX-EX compound). Crash between the steps leaves a short-TTL hole key, which
     * the replay side tolerates as an explicit gap marker.
     *
     * @param seqKey     seq allocation key
     * @param seq        seq value to occupy
     * @param ttlSeconds hole-key TTL (short)
     * @return true when the seq was occupied by this call
     */
    public boolean allocateSeq(String seqKey, String seq, long ttlSeconds) {
        if (client.setnx(seqKey, seq) != 1L) {
            return false;
        }
        client.expire(seqKey, ttlSeconds);
        return true;
    }

    /**
     * Overwrites a latest index (index maintenance, not an audit record body; append-only
     * does not apply). Two-step set clears TTL; the gap is tolerated per design.
     *
     * @param latestKey latest index key
     * @param seq       new latest seq
     */
    public void advanceLatest(String latestKey, String seq) {
        client.set(latestKey, seq);
        client.expire(latestKey, recordTtlSeconds);
    }

    /**
     * Lists keys matching a glob pattern (replay / rebuild queries).
     *
     * @param pattern redis glob pattern
     * @return matching keys
     */
    public List<String> scan(String pattern) {
        try {
            return client.scanIter(pattern);
        } catch (JedisException | IllegalStateException e) {
            LOGGER.warn("trajectory store scan failed ({}), degraded to empty", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * Lists all round node keys of one task (round-seq recovery).
     *
     * @param taskId task id
     * @return matching node keys
     */
    public List<String> scanRoundKeys(String taskId) {
        return scan(runKeyPrefix(taskId) + "*");
    }

    /**
     * Deletes keys (session reset cleanup).
     *
     * @param keys redis keys
     */
    public void delete(String... keys) {
        if (keys.length > 0) {
            client.del(keys);
        }
    }

    private static String encode(String segment) {
        return KEY_ENCODER.encodeToString(segment.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeRunId(String runId) {
        int hash = runId.lastIndexOf('#');
        if (hash < 0) {
            return encode(runId);
        }
        return encode(runId.substring(0, hash)) + "#" + runId.substring(hash + 1);
    }
}
