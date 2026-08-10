/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.a2a;

import com.openjiuwen.service.app.controller.a2a.A2aJsonRpcResponseSerializer;
import com.openjiuwen.service.bus.consumer.BusTaskProjectionCoordinator;
import com.openjiuwen.service.bus.consumer.model.Admission;
import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusTaskAdmissionStore;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Task;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wraps the upstream TaskStore and projects bus-owned Tasks without affecting Task persistence.
 *
 * @since 2026-07-22
 */
public final class TaskStoreProjectionPostProcessor implements BeanPostProcessor {
    private static final Logger LOG = Logger.getLogger(TaskStoreProjectionPostProcessor.class.getName());

    private final Supplier<BusTaskProjectionCoordinator> coordinator;
    private final Supplier<InMemoryBusTaskAdmissionStore> admissions;
    private final ConcurrentHashMap<String, AtomicLong> fallbackRevisions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> taskTenants = new ConcurrentHashMap<>();
    private volatile TaskStore taskStore;

    /**
     * Creates a new instance.
     *
     * @param coordinator
     *            the coordinator value
     * @param admissions
     *            the admissions value
     */
    public TaskStoreProjectionPostProcessor(BusTaskProjectionCoordinator coordinator,
            InMemoryBusTaskAdmissionStore admissions) {
        this(() -> coordinator, () -> admissions);
    }

    /**
     * Creates a new instance.
     *
     * @param coordinator
     *            the coordinator value
     * @param admissions
     *            the admissions value
     */
    public TaskStoreProjectionPostProcessor(Supplier<BusTaskProjectionCoordinator> coordinator,
            Supplier<InMemoryBusTaskAdmissionStore> admissions) {
        this.coordinator = coordinator;
        this.admissions = admissions;
    }

    /** {@inheritDoc} */
    @Override
    public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
        if (!(bean instanceof TaskStore store) || bean instanceof ProjectingTaskStore) {
            return bean;
        }
        ProjectingTaskStore projecting = new ProjectingTaskStore(store, this::observe);
        taskStore = projecting;
        return projecting;
    }

    /**
     * Projects the current persisted Task state.
     *
     * @param tenantId
     *            tenant identity
     * @param taskId
     *            Task identity
     */
    public void projectCurrent(String tenantId, String taskId) {
        TaskStore store = taskStore;
        if (store == null) {
            return;
        }
        taskTenants.put(taskId, tenantId);
        Task task = store.get(taskId);
        if (task != null) {
            observe(tenantId, task);
        }
    }

    /**
     * Repairs missed Task-state projections.
     *
     * @param tenantId
     *            tenant identity
     * @param limit
     *            maximum admission records to inspect
     *
     * @return number of admission records inspected
     */
    public int repair(String tenantId, int limit) {
        int repaired = 0;
        for (Admission admission : admissions.get().list(tenantId, limit)) {
            if (admission.state() == Admission.State.ADMITTED) {
                projectCurrent(tenantId, admission.taskId());
                repaired++;
            }
        }
        return repaired;
    }

    private void observe(Task task) {
        findAdmission(task).ifPresent(admission -> project(admission, task));
    }

    private void observe(String tenantId, Task task) {
        admissions.get().findByTaskId(tenantId, task.id()).ifPresent(admission -> project(admission, task));
    }

    private Optional<Admission> findAdmission(Task task) {
        Object tenant = task.metadata() == null ? null : task.metadata().get("tenantId");
        String tenantId = tenant == null ? taskTenants.get(task.id()) : tenant.toString();
        if (tenantId == null) {
            return Optional.empty();
        }
        return admissions.get().findByTaskId(tenantId, task.id());
    }

    private void project(Admission admission, Task task) {
        String state = task.status() == null ? "UNKNOWN" : task.status().state().name();
        String kind = switch (state) {
            case "TASK_STATE_INPUT_REQUIRED", "TASK_STATE_AUTH_REQUIRED" -> "INPUT_REQUIRED";
            case "TASK_STATE_COMPLETED", "TASK_STATE_FAILED", "TASK_STATE_CANCELED", "TASK_STATE_REJECTED",
                    "UNRECOGNIZED" ->
                "TERMINAL";
            default -> null;
        };
        if (kind == null) {
            return;
        }
        long revision = persistedRevision(task);
        String prefix = "A2A".equals(admission.sourceFamily()) ? "A2A_CALL_" : "INVOCATION_";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskState", state);
        data.put("revision", revision);
        try {
            data.put("a2aResponse", A2aJsonRpcResponseSerializer.streamingEvent(admission.requestId(), task));
            coordinator.get()
                    .project(new BusResponseProjection(eventId(admission.tenantId(), task.id(), kind, revision),
                            prefix + kind, admission.tenantId(), admission.correlationId(), task.id(), Instant.now(),
                            Map.copyOf(data), admission.traceId(), admission.targetServiceId(),
                            admission.sourceServiceId(), admission.routeHandle(), admission.idempotencyKey(), null,
                            kind, revision));
        } catch (org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException
                | IllegalArgumentException | IllegalStateException failure) {
            LOG.log(Level.WARNING, "Failed to project Task state " + task.id(), failure);
        }
    }

    private long persistedRevision(Task task) {
        if (task.status() != null && task.status().timestamp() != null) {
            return task.status().timestamp().toInstant().toEpochMilli();
        }
        Object value = task.metadata() == null ? null : task.metadata().get("revision");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            return parseRevision(task.id(), text);
        }
        return fallbackRevisions.computeIfAbsent(task.id(), ignored -> new AtomicLong()).incrementAndGet();
    }

    private long parseRevision(String taskId, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            LOG.log(Level.FINE, "Ignoring invalid persisted revision for Task " + taskId, failure);
            return fallbackRevisions.computeIfAbsent(taskId, ignored -> new AtomicLong()).incrementAndGet();
        }
    }

    private static String eventId(String tenantId, String taskId, String kind, long revision) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((tenantId + "\u0000" + taskId + "\u0000" + kind + "\u0000" + revision)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
