# EDPAgent 客户开发环境集成示例

将 `edp-agent-engine` JAR 作为 Maven 依赖，嵌入客户自有 Spring Boot 应用。

## 与 quickstart 的区别

| 对比项 | quickstart | integration-example |
|--------|-----------|---------------------|
| 使用方式 | `java -jar` 直接运行 fat jar | Maven 依赖嵌入自有应用 |
| JAR 类型 | fat jar（190MB，含全部依赖） | plain jar（200KB，依赖传递） |
| 自定义代码 | 无（仅配置 + 场景） | 有（自有 Application + 自定义 Tool/Rail） |
| 适用场景 | 快速体验、独立部署 | 客户开发环境集成、二次开发 |
| 开发层级 | 层级一：配置驱动（零代码） | 层级一 + 层级二：深度定制（Java 代码） |

## 目录结构

```
edp-agent-integration-example/
├── pom.xml                                    # Maven POM（依赖 edp-agent-engine）
├── README.md
├── src/main/java/com/customer/agent/
│   ├── CustomerApplication.java               # 客户 Spring Boot 入口
│   └── CustomerAgentConfig.java               # Layer 2 深度定制（自定义 Tool + Rail）
├── src/main/resources/
│   └── application.yml                        # 客户配置（覆盖引擎默认值）
└── scenarios/                                 # 场景目录（来自 agent-store）
    ├── wealth-demo/                           # 理财购买场景（完整示例）
    │   ├── governance/
    │   │   ├── actrule.yaml                   #   4 步 DAG + 2 条动态路径
    │   │   ├── planrule.yaml                  #   理财顾问角色 + scope + 提示词
    │   │   └── scriptconfig.yaml              #   通用话术 + 流式 + 确认话术
    │   ├── skills/                            #   4 个技能
    │   │   ├── fund_planning_skill/           #     资金筹划
    │   │   ├── interact_finance_rec_skill/    #     交互式理财筛选
    │   │   ├── product_recommend_skill/       #     产品推荐
    │   │   └── product_select_skill/          #     产品选定
    │   └── test/
    │       └── test_wealth_recommend_e2e.py   #   E2E 测试
    └── hz-zhidaitong/                         # 智贷通场景（精简示例）
        └── governance/
            ├── actrule.yaml                   #   2 步 DAG（贷款申请 → 审批）
            └── planrule.yaml                  #   智贷通角色 + scope
```

## 场景说明

### wealth-demo（理财购买）— 默认场景

完整的端到端理财顾问场景，包含：
- **4 步 DAG 任务模板**：product_recommend → interact_finance_rec → product_select → fund_planning
- **2 条动态路径**：shortcut（跳过筛选）、余额为零（跳过购买）
- **4 个技能**：产品推荐、交互式筛选、产品选定、资金筹划
- **完整话术配置**：通用话术 + real_stream 流式 + 确认话术

### hz-zhidaitong（智贷通）— 精简场景

最小可用示例，仅含 governance 层：
- **2 步 DAG**：loan_apply → loan_approval
- **scope 约束**：允许智贷通贷款，禁止理财/信用卡

切换方式：

```bash
java -jar target/customer-agent-app-1.0.0.jar \
  --edpa.agent.scenario-home=./scenarios/hz-zhidaitong
```

## 前置条件

### 1. Java 环境

```bash
java -version  # Java 17+
mvn -version   # Maven 3.8+
```

### 2. 本地 Maven 仓库依赖

客户开发环境需要以下 4 个 JAR 安装到本地 Maven 仓库（`~/.m2/repository`）：

| groupId | artifactId | version | 来源 |
|---------|-----------|---------|------|
| `com.huawei.ascend` | `edp-agent-engine` | 0.1.0-SNAPSHOT | edp-agent-java 构建 |
| `com.openjiuwen` | `agent-core-java` | 0.1.13 | OpenJiuwen 发布 |
| `com.openjiuwen` | `agent-service-app` | 0.1.0 | OpenJiuwen 发布 |
| `com.openjiuwen` | `agent-service-adapters-agentcore-ext` | 0.1.0-SNAPSHOT | agent-runtime-ext-java 构建 |

**安装方式（任选其一）：**

方式 A — 如果 JAR 已发布到 Maven 仓库（Nexus/Artifactory），在 pom.xml 中配置 `<repositories>` 即可。

方式 B — 手动安装到本地仓库：

```bash
# 安装 edp-agent-engine（plain JAR，非 fat jar）
mvn install:install-file \
  "-Dfile=edp-agent-engine-0.1.0-SNAPSHOT.jar.original" \
  "-DgroupId=com.huawei.ascend" \
  "-DartifactId=edp-agent-engine" \
  "-Dversion=0.1.0-SNAPSHOT" \
  "-Dpackaging=jar" \
  "-DgeneratePom=true"

# 同理安装其他 3 个 JAR...
```

### 3. Redis

```bash
redis-server  # localhost:6379
```

### 4. LLM API Key

```bash
export EDP_AGENT_MODEL_API_KEY=sk-your-key-here   # Linux/Mac
set EDP_AGENT_MODEL_API_KEY=sk-your-key-here       # Windows
```

## 集成步骤

### 第一步：创建 Maven 项目

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.6</version>
</parent>

<dependencies>
    <dependency>
        <groupId>com.huawei.ascend</groupId>
        <artifactId>edp-agent-engine</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

### 第二步：创建 Application 入口

```java
@SpringBootApplication
public class CustomerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CustomerApplication.class, args);
    }
}
```

EDPA 引擎的 AutoConfiguration 会自动扫描以下包并注册所有 Bean：
- `com.openjiuwen.service.*` — A2A/Query 入口、Agent Card
- `com.huawei.ascend.edp.*` — EDPA 引擎（handler/rails/tools/config）

**客户无需配置任何 Bean。**

### 第三步：配置 application.yml

参考 `src/main/resources/application.yml`，关键配置：

```yaml
edpa:
  agent:
    scenario-home: ./scenarios/wealth-demo  # 场景路径
    model:
      name: deepseek-v4-pro
      api-key: ${EDP_AGENT_MODEL_API_KEY}
```

### 第四步：创建场景

场景来自 `agent-store-0707/edp-agent-java/scenarios/`，已内置两个：
- `wealth-demo/` — 理财购买（默认）
- `hz-zhidaitong/` — 智贷通

自定义场景：复制 `wealth-demo/` 目录，修改 governance YAML 和 skills。

### 第五步（可选）：Layer 2 深度定制

参考 `CustomerAgentConfig.java`，通过 Java 代码注册自定义 Tool 和 Rail：

```java
@Configuration
public class CustomerAgentConfig {

    private final AgentHandler agentHandler;

    @PostConstruct
    public void registerCustomExtensions() {
        EdpaExtHandler edpaHandler = (EdpaExtHandler) agentHandler;
        DeepAgent deepAgent = edpaHandler.getDeepAgent();

        // 注册自定义工具
        deepAgent.registerHarnessTool(greetingTool);

        // 注册自定义 Rail
        deepAgent.getAgent().registerRail(new CustomerAuditRail());
    }
}
```

### 第六步：构建和运行

```bash
# 构建
mvn clean package -DskipTests

# 运行（默认 wealth-demo 场景）
java -jar target/customer-agent-app-1.0.0.jar

# 运行（切换 hz-zhidaitong 场景）
java -jar target/customer-agent-app-1.0.0.jar \
  --edpa.agent.scenario-home=./scenarios/hz-zhidaitong
```

### 第七步：调用

```bash
# Agent Card
curl http://localhost:8190/.well-known/agent-card.json

# 阻塞调用
curl -X POST http://localhost:8190/v1/query \
  -H "Content-Type: application/json" \
  -d '{"conversation_id":"c1","message":"推荐理财产品"}'

# A2A JSON-RPC
curl -X POST http://localhost:8190/a2a \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{"jsonrpc":"2.0","method":"SendMessage","id":"r1","params":{"message":{"role":"user","parts":[{"text":"推荐理财产品"}],"contextId":"c2"}}}'
```

## 扩展能力

### 自定义 Tool（Layer 2）

使用 `LocalFunction` + `ToolCard.builder()` 构建自定义工具：

```java
ToolCard card = ToolCard.builder()
        .id("my_tool")
        .name("my_tool")
        .description("My custom tool")
        .inputParams(paramsSchema)
        .build();

LocalFunction tool = new LocalFunction(card, inputs -> {
    // 工具逻辑
    return Map.of("result", "done");
});

deepAgent.registerHarnessTool(tool);
```

### 自定义 Rail（Layer 2）

继承 `DeepAgentRail`，覆写回调方法：

```java
public class CustomerAuditRail extends DeepAgentRail {
    @Override
    public int priority() { return 15; }

    @Override
    public void beforeModelCall(AgentCallbackContext ctx) {
        // 模型调用前逻辑
    }

    @Override
    public void afterModelCall(AgentCallbackContext ctx) {
        // 模型调用后逻辑
    }
}

deepAgent.getAgent().registerRail(new CustomerAuditRail());
```

### 配置覆盖

所有配置均可通过以下方式覆盖（优先级从高到低）：
1. 命令行参数：`--edpa.agent.model.name=xxx`
2. 环境变量：`EDP_AGENT_MODEL_NAME=xxx`
3. 外部配置文件：`--spring.config.additional-location=file:./config.yml`
4. application.yml 默认值

## 部署方式

### 方式 A：Maven 依赖嵌入（本文档方式）

```
客户开发 → mvn package → java -jar customer-agent-app.jar
```

特点：引擎与客户代码打在同一个 fat jar 中，客户可自定义扩展。

### 方式 B：独立部署 + HTTP 调用

```
EDPAgent 引擎独立部署（quickstart 方式） → 客户应用通过 HTTP/A2A 调用
```

特点：引擎与客户应用解耦，通过 HTTP API 交互。

### 方式 C：Docker 容器部署

```dockerfile
FROM eclipse-temurin:21-jre-jammy
COPY target/customer-agent-app-1.0.0.jar /app/app.jar
COPY scenarios/ /app/scenarios/
WORKDIR /app
EXPOSE 8190
ENTRYPOINT ["java", "-jar", "app.jar", "--edpa.agent.scenario-home=./scenarios/wealth-demo"]
```

## 常见问题

**Q: 编译报错 "Cannot resolve com.huawei.ascend:edp-agent-engine"？**
A: 确认 plain JAR（非 fat jar）已安装到本地 Maven 仓库。fat jar（190MB）不能作为 Maven 依赖。

**Q: 启动报错 "agent not loaded"？**
A: 检查 Redis 是否启动、API Key 是否设置、场景目录是否存在。

**Q: 如何切换场景？**
A: 修改 `application.yml` 的 `scenario-home`，或通过命令行参数 `--edpa.agent.scenario-home=./scenarios/hz-zhidaitong` 覆盖。

**Q: 如何切换 LLM 模型？**
A: 修改 `application.yml` 的 `edpa.agent.model.*` 或设置环境变量。

**Q: 客户自定义 Tool/Rail 如何注册？**
A: 参考 `CustomerAgentConfig.java`，注入 `AgentHandler`，强转为 `EdpaExtHandler`，通过 `getDeepAgent()` 获取 `DeepAgent` 后注册。
