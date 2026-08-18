/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.fe016;

import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.controller.InstanceRouteController;
import com.openjiuwen.rdc.controller.RegistryApiExceptionHandler;
import com.openjiuwen.rdc.model.AgentCardDto;
import com.openjiuwen.rdc.model.EntryNotFoundException;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.model.MalformedRouteHandleException;
import com.openjiuwen.rdc.model.RegistryFailureException;
import com.openjiuwen.rdc.model.RouteResolution;
import com.openjiuwen.rdc.model.TenantIsolationViolationException;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import com.openjiuwen.rdc.repository.AgentRegistryRepository.EndpointEntry;
import com.openjiuwen.rdc.repository.AgentRegistryRepository.RegistryRow;
import com.openjiuwen.rdc.repository.AgentRegistryRepository.ResolveRow;
import com.openjiuwen.rdc.security.CallerAuthorizationPolicy;
import com.openjiuwen.rdc.service.AgentDiscoveryService;
import com.openjiuwen.rdc.service.PgMvpDiscoveryServiceImpl;
import com.openjiuwen.rdc.tenant.ThreadLocalTenantContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import org.springframework.http.ResponseEntity;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FEAT-016 运行时实例路由查询 — AgentDemo 测试入口。
 *
 * <p>测试目标：验证 {@code registry-discovery-center} 中 FEAT-016 相关的
 * 路由句柄解析、实例查询、错误码映射与不透明性，发现并输出 Bug。
 *
 * <p>测试策略：
 * <ul>
 *   <li>异常处理器：直接构造 {@link RegistryApiExceptionHandler}，验证
 *       {@code RegistryFailureException} 子类到 HTTP 状态码的映射。</li>
 *   <li>服务层：用 Mockito mock {@link AgentRegistryRepository}，构造
 *       {@link PgMvpDiscoveryServiceImpl}，验证 resolve / search 流程。</li>
 *   <li>控制器层：直接构造 {@link InstanceRouteController}，验证端到端路由查询。</li>
 *   <li>不透明性：用反射验证 {@link AgentCardDto} 不暴露物理路由字段。</li>
 * </ul>
 *
 * <p>所有测试均通过（green），发现的 Bug 通过 {@code System.out} 输出 [BUG] 标记。
 *
 * @since 0.1.0 (2026)
 */
@Tag("fe016")
@DisplayName("FEAT-016 运行时实例路由查询 — Bug发现测试")
class InstanceRouteQueryDemoTest {

    private static final String TENANT_A = "tenant-A";
    private static final String TENANT_B = "tenant-B";
    private static final String AGENT_1 = "agent-001";
    private static final String SERVICE_1 = "svc-001";
    private static final String INSTANCE_1 = "10.0.0.1:8090";
    private static final String ROUTE_KEY = "/v1/query";
    private static final String CONTRACT_VER = "1.0.0";
    private static final String CAPABILITY_VER = "1.2.0";
    private static final String ENDPOINT_URL = "http://10.0.0.1:8090";

    private static String buildRouteHandle(String tenantId, String agentId, String serviceId,
                                           String instanceId, String routeKey, String contractVersion) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("tenantId", tenantId);
            node.put("agentId", agentId);
            node.put("serviceId", serviceId);
            node.put("instanceId", instanceId);
            node.put("routeKey", routeKey);
            node.put("contractVersion", contractVersion);
            byte[] json = mapper.writeValueAsBytes(node);
            return "v2:" + Base64.getEncoder().encodeToString(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static AgentDiscoveryService buildDiscovery(AgentRegistryRepository repo) {
        return new PgMvpDiscoveryServiceImpl(
                repo,
                new ThreadLocalTenantContext(),
                new RegistryObservabilityConfig(new SimpleMeterRegistry()),
                null);
    }

    private static RegistryRow buildOnlineRow() {
        return new RegistryRow(
                SERVICE_1, INSTANCE_1, AGENT_1, "demo-agent",
                FrameworkType.JIUWEN, ROUTE_KEY, CONTRACT_VER, CAPABILITY_VER,
                100, "cn-east", 10, "ONLINE", List.of());
    }

    private static ResolveRow buildActiveResolveRow() {
        return new ResolveRow(
                ENDPOINT_URL, ROUTE_KEY, CONTRACT_VER, CAPABILITY_VER,
                "ACTIVE", Instant.now().plusSeconds(3600));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokeHandler(RegistryFailureException ex) {
        RegistryApiExceptionHandler handler = new RegistryApiExceptionHandler();
        ResponseEntity<Map<String, Object>> response = handler.handleRegistryFailure(ex);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static int invokeHandlerStatus(RegistryFailureException ex) {
        RegistryApiExceptionHandler handler = new RegistryApiExceptionHandler();
        ResponseEntity<Map<String, Object>> response = handler.handleRegistryFailure(ex);
        return response.getStatusCode().value();
    }

    @Nested
    @DisplayName("异常处理器HTTP状态码映射（设计文档§7）")
    class FailureCodeToHttpStatusMapping {

        @Test
        @DisplayName("BUG#1: MALFORMED_ROUTE_HANDLE 应返回HTTP 400 但实际返回HTTP 404")
        void malformedRouteHandle_shouldReturn400_butReturns404() {
            MalformedRouteHandleException ex =
                    new MalformedRouteHandleException("route handle missing v2: prefix", "trace-001");
            int actualStatus = invokeHandlerStatus(ex);
            Map<String, Object> body = invokeHandler(ex);
            String actualCode = (String) body.get("error");

            assertThat(actualStatus).isEqualTo(404);

            System.out.println("============================================================");
            System.out.println("[BUG #1] MALFORMED_ROUTE_HANDLE → HTTP 状态码错误");
            System.out.println("  期望（设计文档§7 + Javadoc）: HTTP 400, error code: malformed_handle");
            System.out.println("  实际: HTTP " + actualStatus + ", error code: " + actualCode);
            System.out.println("  位置: RegistryApiExceptionHandler.mapFailureStatus() 第86行");
            System.out.println("  代码: case \"ENTRY_NOT_FOUND\", \"MALFORMED_ROUTE_HANDLE\" -> HttpStatus.NOT_FOUND;");
            System.out.println("  原因: MALFORMED_ROUTE_HANDLE 与 ENTRY_NOT_FOUND 合并为同一 case，");
            System.out.println("        被映射到 NOT_FOUND(404) 而非 BAD_REQUEST(400)");
            System.out.println("  影响: 客户端收到 404 可能误认为资源不存在，而非请求格式错误，");
            System.out.println("        导致重试策略错误（应修正请求而非放弃）");
            System.out.println("============================================================");
        }

        @Test
        @DisplayName("BUG#2: TENANT_SCOPE_DENIED 应返回HTTP 400 但实际返回HTTP 403")
        void tenantScopeDenied_shouldReturn400_butReturns403() {
            TenantIsolationViolationException ex =
                    new TenantIsolationViolationException(TENANT_A, TENANT_B, "trace-002");
            int actualStatus = invokeHandlerStatus(ex);
            Map<String, Object> body = invokeHandler(ex);
            String actualCode = (String) body.get("error");

            assertThat(actualStatus).isEqualTo(403);

            System.out.println("============================================================");
            System.out.println("[BUG #2] TENANT_SCOPE_DENIED → HTTP 状态码错误");
            System.out.println("  期望（设计文档§7 + Javadoc）: HTTP 400, error code: tenant_isolation_violation");
            System.out.println("  实际: HTTP " + actualStatus + ", error code: " + actualCode);
            System.out.println("  位置: RegistryApiExceptionHandler.mapFailureStatus() 第85行");
            System.out.println("  代码: case \"CALLER_NOT_AUTHORIZED\", \"TENANT_SCOPE_DENIED\" -> HttpStatus.FORBIDDEN;");
            System.out.println("  原因: TENANT_SCOPE_DENIED 与 CALLER_NOT_AUTHORIZED 合并为同一 case，");
            System.out.println("        被映射到 FORBIDDEN(403) 而非 BAD_REQUEST(400)");
            System.out.println("  影响: 403 暗示权限不足，但租户隔离违规是请求参数错误（请求了错误租户），");
            System.out.println("        客户端无法通过重试修正，应返回 400 提示请求本身有问题");
            System.out.println("============================================================");
        }

        @Test
        @DisplayName("ENTRY_NOT_FOUND 返回HTTP 404（符合设计文档§7）")
        void entryNotFound_returns404_asExpected() {
            EntryNotFoundException ex =
                    new EntryNotFoundException("entry not found: tenant=tenant-A", "trace-003");
            int actualStatus = invokeHandlerStatus(ex);
            Map<String, Object> body = invokeHandler(ex);
            String actualCode = (String) body.get("error");

            assertThat(actualStatus).isEqualTo(404);
            assertThat(actualCode).isEqualTo("ENTRY_NOT_FOUND");

            System.out.println("[OK] ENTRY_NOT_FOUND → HTTP 404（符合设计文档§7）");
        }
    }

    @Nested
    @DisplayName("错误码命名一致性（设计文档§7）")
    class FailureCodeNamingConsistency {

        @Test
        @DisplayName("BUG#3: TenantIsolationViolationException failureCode 应为 tenant_isolation_violation")
        void tenantIsolation_failureCodeMismatch() {
            TenantIsolationViolationException ex =
                    new TenantIsolationViolationException(TENANT_A, TENANT_B, "trace-004");
            String failureCode = ex.failure().failureCode();

            assertThat(failureCode).isEqualTo("TENANT_SCOPE_DENIED");

            System.out.println("============================================================");
            System.out.println("[BUG #3] 错误码命名不一致 — TenantIsolationViolationException");
            System.out.println("  期望（设计文档§7）: tenant_isolation_violation");
            System.out.println("  实际: " + failureCode);
            System.out.println("  位置: TenantIsolationViolationException 构造函数 第28行");
            System.out.println("  代码: super(RegistryFailure.of(\"TENANT_SCOPE_DENIED\", ...));");
            System.out.println("  同时 PgMvpDiscoveryServiceImpl / RouteHandleCodec 的 Javadoc");
            System.out.println("  均写明 HTTP 400 tenant_isolation_violation，与实际代码矛盾");
            System.out.println("  影响: 前端 / 网关按 error code 做分支时无法匹配，需额外适配");
            System.out.println("============================================================");
        }

        @Test
        @DisplayName("BUG#4: MalformedRouteHandleException failureCode 应为 malformed_handle")
        void malformedRoute_failureCodeMismatch() {
            MalformedRouteHandleException ex =
                    new MalformedRouteHandleException("bad handle", "trace-005");
            String failureCode = ex.failure().failureCode();

            assertThat(failureCode).isEqualTo("MALFORMED_ROUTE_HANDLE");

            System.out.println("============================================================");
            System.out.println("[BUG #4] 错误码命名不一致 — MalformedRouteHandleException");
            System.out.println("  期望（设计文档§7）: malformed_handle");
            System.out.println("  实际: " + failureCode);
            System.out.println("  位置: MalformedRouteHandleException 构造函数 第15行");
            System.out.println("  代码: super(RegistryFailure.of(\"MALFORMED_ROUTE_HANDLE\", ...));");
            System.out.println("  同时 PgMvpDiscoveryServiceImpl / RouteHandleCodec 的 Javadoc");
            System.out.println("  均写明 HTTP 400 malformed_handle，与实际代码矛盾");
            System.out.println("  影响: 前端 / 网关按 error code 做分支时无法匹配，需额外适配");
            System.out.println("============================================================");
        }
    }

    @Nested
    @DisplayName("路由句柄解析（service层）")
    class RouteHandleResolution {

        @Test
        @DisplayName("合法路由句柄解析返回完整RouteResolution")
        void validRouteHandle_resolvesToRouteResolution() {
            AgentRegistryRepository repo = mock(AgentRegistryRepository.class);
            AgentDiscoveryService discovery = buildDiscovery(repo);
            String handle = buildRouteHandle(TENANT_A, AGENT_1, SERVICE_1, INSTANCE_1, ROUTE_KEY, CONTRACT_VER);

            when(repo.findForResolve(TENANT_A, AGENT_1, SERVICE_1, INSTANCE_1))
                    .thenReturn(Optional.of(buildActiveResolveRow()));

            RouteResolution resolution = discovery.resolveRouteHandle(handle, TENANT_A);

            assertThat(resolution.instanceId()).isEqualTo(INSTANCE_1);
            assertThat(resolution.endpointUrl()).isEqualTo(ENDPOINT_URL);
            assertThat(resolution.routeKey()).isEqualTo(ROUTE_KEY);
            assertThat(resolution.contractVersion()).isEqualTo(CONTRACT_VER);
            assertThat(resolution.capabilityVersion()).isEqualTo(CAPABILITY_VER);

            System.out.println("[OK] 合法路由句柄解析成功");
            System.out.println("  routeHandle: " + handle);
            System.out.println("  → RouteResolution{instanceId=" + resolution.instanceId()
                    + ", endpointUrl=" + resolution.endpointUrl()
                    + ", routeKey=" + resolution.routeKey()
                    + ", contractVersion=" + resolution.contractVersion()
                    + ", capabilityVersion=" + resolution.capabilityVersion() + "}");
        }

        @Test
        @DisplayName("跨租户解析抛出TenantIsolationViolationException")
        void crossTenantResolve_throwsTenantIsolationViolation() {
            AgentRegistryRepository repo = mock(AgentRegistryRepository.class);
            AgentDiscoveryService discovery = buildDiscovery(repo);
            String handle = buildRouteHandle(TENANT_A, AGENT_1, SERVICE_1, INSTANCE_1, ROUTE_KEY, CONTRACT_VER);

            assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, TENANT_B))
                    .isInstanceOf(TenantIsolationViolationException.class);

            System.out.println("[OK] 跨租户解析被拒绝: handle 内 tenant=" + TENANT_A
                    + ", 调用方 tenant=" + TENANT_B + " → TenantIsolationViolationException");
        }

        @Test
        @DisplayName("格式错误的路由句柄（缺少v2:前缀）抛出MalformedRouteHandleException")
        void malformedHandle_throwsMalformedRouteHandle() {
            AgentRegistryRepository repo = mock(AgentRegistryRepository.class);
            AgentDiscoveryService discovery = buildDiscovery(repo);

            assertThatThrownBy(() -> discovery.resolveRouteHandle("not-a-valid-handle", TENANT_A))
                    .isInstanceOf(MalformedRouteHandleException.class);

            System.out.println("[OK] 畸形路由句柄被拒绝: 缺少 v2: 前缀 → MalformedRouteHandleException");
        }

        @Test
        @DisplayName("格式错误的路由句柄（缺少必需JSON字段）抛出MalformedRouteHandleException")
        void malformedHandle_missingJsonField_throwsMalformedRouteHandle() {
            AgentRegistryRepository repo = mock(AgentRegistryRepository.class);
            AgentDiscoveryService discovery = buildDiscovery(repo);

            String badJson = Base64.getEncoder().encodeToString(
                    "{\"tenantId\":\"tenant-A\",\"agentId\":\"agent-001\"}".getBytes());
            String badHandle = "v2:" + badJson;

            assertThatThrownBy(() -> discovery.resolveRouteHandle(badHandle, TENANT_A))
                    .isInstanceOf(MalformedRouteHandleException.class);

            System.out.println("[OK] 畸形路由句柄被拒绝: JSON 缺少 serviceId/instanceId/routeKey/contractVersion 字段");
        }

        @Test
        @DisplayName("entry不存在的路由句柄抛出EntryNotFoundException")
        void entryNotFound_throwsEntryNotFoundException() {
            AgentRegistryRepository repo = mock(AgentRegistryRepository.class);
            AgentDiscoveryService discovery = buildDiscovery(repo);
            String handle = buildRouteHandle(TENANT_A, AGENT_1, SERVICE_1, INSTANCE_1, ROUTE_KEY, CONTRACT_VER);

            when(repo.findForResolve(TENANT_A, AGENT_1, SERVICE_1, INSTANCE_1))
                    .thenReturn(Optional.empty());
            when(repo.findEndpoint(TENANT_A, AGENT_1, SERVICE_1, INSTANCE_1))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, TENANT_A))
                    .isInstanceOf(EntryNotFoundException.class);

            System.out.println("[OK] entry不存在被拒绝: findForResolve + findEndpoint 均返回 empty → EntryNotFoundException");
        }

        @Test
        @DisplayName("旧版v1:前缀的路由句柄被拒绝（baseline-breaking）")
        void oldV1PrefixHandle_rejected() {
            AgentRegistryRepository repo = mock(AgentRegistryRepository.class);
            AgentDiscoveryService discovery = buildDiscovery(repo);

            String oldHandle = "v1:" + Base64.getEncoder().encodeToString(
                    "{\"tenantId\":\"tenant-A\",\"agentId\":\"a1\",\"serviceId\":\"s1\"}".getBytes());

            assertThatThrownBy(() -> discovery.resolveRouteHandle(oldHandle, TENANT_A))
                    .isInstanceOf(MalformedRouteHandleException.class);

            System.out.println("[OK] 旧版 v1: 前缀句柄被拒绝（FEAT-016 baseline-breaking）");
        }
    }

    @Nested
    @DisplayName("实例路由查询（controller层）")
    class InstanceRouteQuery {

        @Test
        @DisplayName("实例路由查询返回ONLINE实例列表，每个实例携带opaque routeHandle")
        void listInstances_returnsOnlineInstancesWithOpaqueHandle() {
            AgentRegistryRepository repo = mock(AgentRegistryRepository.class);
            AgentDiscoveryService discovery = buildDiscovery(repo);
            InstanceRouteController controller = new InstanceRouteController(discovery, repo);

            when(repo.listByAgentId(TENANT_A, AGENT_1, null))
                    .thenReturn(List.of(buildOnlineRow()));

            List<AgentCardDto> results = controller.listInstances(TENANT_A, AGENT_1, null);

            assertThat(results).hasSize(1);
            AgentCardDto card = results.get(0);
            assertThat(card.getRouteHandle()).startsWith("v2:");
            assertThat(card.getServiceId()).isEqualTo(SERVICE_1);
            assertThat(card.getHealth()).isEqualTo("ONLINE");
            assertThat(card.getContractVersion()).isEqualTo(CONTRACT_VER);
            assertThat(card.getCapabilityVersion()).isEqualTo(CAPABILITY_VER);
            assertThat(card.getWeight()).isEqualTo(100);
            assertThat(card.getMaxConcurrency()).isEqualTo(10);
            assertThat(card.getAgentName()).isEqualTo("demo-agent");
            assertThat(card.getFrameworkType()).isEqualTo(FrameworkType.JIUWEN);

            System.out.println("[OK] 实例路由查询返回 " + results.size() + " 个 ONLINE 实例");
            System.out.println("  routeHandle: " + card.getRouteHandle());
            System.out.println("  serviceId: " + card.getServiceId());
            System.out.println("  health: " + card.getHealth());
        }

        @Test
        @DisplayName("反枚举：无实例时返回空列表（不区分不存在与无权限）")
        void listInstances_emptyResult_antiEnumeration() {
            AgentRegistryRepository repo = mock(AgentRegistryRepository.class);
            AgentDiscoveryService discovery = buildDiscovery(repo);
            InstanceRouteController controller = new InstanceRouteController(discovery, repo);

            when(repo.listByAgentId(TENANT_A, "nonexistent-agent", null))
                    .thenReturn(List.of());

            List<AgentCardDto> results = controller.listInstances(TENANT_A, "nonexistent-agent", null);

            assertThat(results).isEmpty();

            System.out.println("[OK] 反枚举验证: 无实例返回空 List（HTTP 200），");
            System.out.println("     不区分「目标不存在」与「无权限访问」");
        }

        @Test
        @DisplayName("resolve端到端：controller→service→repository→RouteResolution")
        void resolveRouteHandle_endToEnd() {
            AgentRegistryRepository repo = mock(AgentRegistryRepository.class);
            AgentDiscoveryService discovery = buildDiscovery(repo);
            InstanceRouteController controller = new InstanceRouteController(discovery, repo);

            String handle = buildRouteHandle(TENANT_A, AGENT_1, SERVICE_1, INSTANCE_1, ROUTE_KEY, CONTRACT_VER);
            when(repo.findForResolve(TENANT_A, AGENT_1, SERVICE_1, INSTANCE_1))
                    .thenReturn(Optional.of(buildActiveResolveRow()));

            InstanceRouteController.ResolveRequest request =
                    new InstanceRouteController.ResolveRequest(handle, TENANT_A, null);
            RouteResolution resolution = controller.resolveRouteHandle(request, null, null, null);

            assertThat(resolution.endpointUrl()).isEqualTo(ENDPOINT_URL);
            assertThat(resolution.routeKey()).isEqualTo(ROUTE_KEY);

            System.out.println("[OK] resolve端到端: controller → service → repository → RouteResolution");
            System.out.println("  endpointUrl: " + resolution.endpointUrl());
            System.out.println("  routeKey: " + resolution.routeKey());
        }
    }

    @Nested
    @DisplayName("AgentCardDto不透明性验证（HD3-006）")
    class AgentCardDtoOpacity {

        @Test
        @DisplayName("AgentCardDto不暴露endpointUrl/routeKey/instanceId")
        void agentCardDto_doesNotExposePhysicalRoutingFields() {
            Set<String> getterNames = Arrays.stream(AgentCardDto.class.getDeclaredMethods())
                    .map(Method::getName)
                    .filter(name -> name.startsWith("get"))
                    .collect(Collectors.toSet());

            assertThat(getterNames).doesNotContain("getEndpointUrl", "getRouteKey", "getInstanceId");
            assertThat(getterNames).contains(
                    "getRouteHandle", "getServiceId", "getHealth",
                    "getContractVersion", "getCapabilityVersion",
                    "getWeight", "getRegion", "getMaxConcurrency",
                    "getAgentName", "getFrameworkType");

            System.out.println("[OK] AgentCardDto 不透明性验证通过（HD3-006）");
            System.out.println("  不暴露: getEndpointUrl, getRouteKey, getInstanceId");
            System.out.println("  暴露: " + getterNames);
        }

        @Test
        @DisplayName("AgentCardDto.Builder不支持endpointUrl/routeKey/instanceId")
        void builder_doesNotAcceptPhysicalRoutingFields() {
            Set<String> builderMethods = Arrays.stream(AgentCardDto.Builder.class.getDeclaredMethods())
                    .map(Method::getName)
                    .collect(Collectors.toSet());

            assertThat(builderMethods).doesNotContain("endpointUrl", "routeKey", "instanceId");
            assertThat(builderMethods).contains(
                    "serviceId", "routeHandle", "health",
                    "contractVersion", "capabilityVersion",
                    "weight", "region", "maxConcurrency",
                    "agentName", "frameworkType");

            System.out.println("[OK] AgentCardDto.Builder 不支持 endpointUrl/routeKey/instanceId 字段");
        }
    }

    @Nested
    @DisplayName("RouteResolution转发层完整性（FEAT-016 v2: 6字段）")
    class RouteResolutionForwarding {

        @Test
        @DisplayName("RouteResolution包含instanceId（FEAT-016新增）")
        void routeResolution_containsInstanceId() {
            AgentRegistryRepository repo = mock(AgentRegistryRepository.class);
            AgentDiscoveryService discovery = buildDiscovery(repo);
            String handle = buildRouteHandle(TENANT_A, AGENT_1, SERVICE_1, INSTANCE_1, ROUTE_KEY, CONTRACT_VER);

            when(repo.findForResolve(TENANT_A, AGENT_1, SERVICE_1, INSTANCE_1))
                    .thenReturn(Optional.of(buildActiveResolveRow()));

            RouteResolution resolution = discovery.resolveRouteHandle(handle, TENANT_A);

            assertThat(resolution.instanceId()).isEqualTo(INSTANCE_1);
            assertThat(resolution.endpointUrl()).isNotBlank();
            assertThat(resolution.routeKey()).isNotBlank();
            assertThat(resolution.contractVersion()).isNotBlank();

            System.out.println("[OK] RouteResolution 包含 FEAT-016 新增 instanceId 字段");
            System.out.println("  instanceId: " + resolution.instanceId());
            System.out.println("  endpointUrl: " + resolution.endpointUrl());
        }

        @Test
        @DisplayName("RouteResolution通过legacy endpoint lookup回退（findForResolve为空时）")
        void routeResolution_fallsBackToFindEndpoint() {
            AgentRegistryRepository repo = mock(AgentRegistryRepository.class);
            AgentDiscoveryService discovery = buildDiscovery(repo);
            String handle = buildRouteHandle(TENANT_A, AGENT_1, SERVICE_1, INSTANCE_1, ROUTE_KEY, CONTRACT_VER);

            when(repo.findForResolve(TENANT_A, AGENT_1, SERVICE_1, INSTANCE_1))
                    .thenReturn(Optional.empty());
            when(repo.findEndpoint(TENANT_A, AGENT_1, SERVICE_1, INSTANCE_1))
                    .thenReturn(Optional.of(new EndpointEntry(ENDPOINT_URL, ROUTE_KEY, CONTRACT_VER)));

            RouteResolution resolution = discovery.resolveRouteHandle(handle, TENANT_A);

            assertThat(resolution.endpointUrl()).isEqualTo(ENDPOINT_URL);
            assertThat(resolution.routeKey()).isEqualTo(ROUTE_KEY);
            assertThat(resolution.contractVersion()).isEqualTo(CONTRACT_VER);

            System.out.println("[OK] RouteResolution 回退路径: findForResolve 为空 → findEndpoint → RouteResolution");
            System.out.println("  注意: 回退路径的 capabilityVersion 为 null（EndpointEntry 不携带该字段）");
        }
    }
}
