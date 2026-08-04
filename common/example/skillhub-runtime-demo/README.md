# SkillHub Runtime Demo

基于 `agent-runtime-java` 的 SkillHub 中间件端到端示例：在 Agent 启动阶段从 [TeamSkillsHub](https://swarmskills.openjiuwen.com) 下载、校验并注册 skill，再让 ReActAgent 在回答用户问题时实际引用这些 skill。

本 demo 验证 FEAT-005 SkillHub 中间件的完整链路：

```text
SkillHubManager.start()
  -> OpenJiuwenSkillHubProvider.start() (连接 swarmskills.openjiuwen.com)
  -> download()  拉取全部 skill zip 并解压到本地目录
  -> verify()   对每个 skill 目录做完整性校验（SHA-256 / 常规检查）
  -> 校验通过路径进入 verifiedSkillPaths 池

JiuwenCoreAgentExtHandler.query() / streamQuery()
  -> SkillHubManager.register(agent)
  -> SkillHubInstaller.install(agent, verifiedSkillPaths)
  -> BaseAgent.registerSkill(dir)  把 SKILL.md 注入 Agent 的 SkillManager
  -> ReActAgent.updateSkillPromptBuilderSection 把 skill 描述拼进 system prompt
  -> LLM 看到并引用已注册 skill 名称
```

## 能力矩阵

| 能力 | 本 demo 是否覆盖 | 说明 |
| --- | --- | --- |
| SkillHub 中间件自动装配 | ✅ | `SkillHubMiddlewareAutoConfiguration` 在 `enabled=true` 时激活，创建 `SkillHubProvider` / `SkillHubInstaller` / `SkillHubManager` |
| 启动期下载 + 请求期注册 | ✅ | `Handler.start()` 触发 `SkillHubManager.start()`（download + verify）；`Handler.query()` 触发 `register(agent)` |
| 凭据加密解密 | ✅ | `encrypted-token` 经 runtime `CredentialDecryptor.decrypt` 解密后传入 Provider；日志只记录 `credential=provided/absent` |
| required 下载/校验失败降级 + 后台重试 | ✅ | 失败时 Agent 仍 ready，skill 不可用；后台线程定时重试 download，成功后校验并加入路径池 |
| required 配置/认证/查找失败 fail fast | ✅ | `SkillHubException` 的 fatal 类别直接 rethrow，阻断 Agent ready |
| LLM 引用真实 skill 名称 | ✅ | `SkillHubAgentLifecycleRealLlmTest` 断言响应包含至少一个从 `SkillHubManager` 已注册列表解析的 skill name |
| 无 Provider / 未启用时正常启动 | ✅ | 未配置时 `JiuwenCoreAgentExtHandler` 仍可独立运行（本 demo 默认启用以演示链路） |

## 模块布局

```text
skillhub-runtime-demo/
├── pom.xml                                      依赖 agent-service-app + agentcore-ext（含 SkillHub 中间件）
└── src/
    ├── main/
    │   ├── java/com/openjiuwen/example/skillhub/
    │   │   └── SkillHubRuntimeDemoApplication.java   Spring Boot 入口 + AgentHandler Bean
    │   └── resources/
    │       └── application.yml                      SkillHub + LLM 配置
    └── test/
        ├── java/com/openjiuwen/example/skillhub/
        │   ├── ReActAgentSkillSmokeTest.java         本地 skill 注册 + LLM 引用断言
        │   └── SkillHubAgentLifecycleRealLlmTest.java 端到端 HTTP 调用 + 真实 SkillHub + DeepSeek
        └── resources/
            └── application-skillhub-remote.yml      测试 profile：抑制 tool/llm INFO 日志泄漏 skill 内容
```

## 前置条件

- JDK 17+
- Maven（示例使用仓库本地 `.m2\repository` 缓存依赖）
- 可选：DeepSeek API Key（仅在运行 LLM 相关测试时需要）
- 可选：SkillHub 访问 token（默认匿名访问 `swarmskills.openjiuwen.com`，token 仅用于需要鉴权的 skill）

## 构建

在仓库根目录下先安装 agentcore-ext（包含 SkillHub 中间件实现），再构建本 demo：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\agent-runtime-ext-java\pom.xml" `
  -pl agent-service-adapters/agent-service-adapters-agentcore-ext -am clean install

mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\skillhub-runtime-demo\pom.xml" `
  clean package
```

## 启动应用

`application.yml` 已默认启用 SkillHub 中间件并指向官方 endpoint。LLM 通过环境变量配置，不要把 API Key 写入仓库文件。

```powershell
Set-Location <repo-root>
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
$env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"

# LLM（仅当需要让 Agent 真正回答用户时配置）
$env:LLM_API_KEY = "<deepseek-api-key>"
$env:LLM_API_BASE = "https://api.deepseek.com"
$env:LLM_MODEL = "deepseek-chat"

# SkillHub（可选覆盖；默认值见 application.yml）
$env:SKILLHUB_ENDPOINT = "https://swarmskills.openjiuwen.com"
$env:SKILLHUB_AUTH_TYPE = "bearer"        # bearer | system-token
$env:SKILLHUB_TOKEN = "<encrypted-token>" # 经 CredentialDecryptor 解密；空串=匿名
$env:SKILLHUB_LOCAL_DIR = "./target/skillhub-skills"

mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\skillhub-runtime-demo\pom.xml" `
  spring-boot:run
```

应用启动后：

- `SkillHubMiddlewareAutoConfiguration` 装配 `SkillHubProvider` / `SkillHubInstaller` / `SkillHubManager`
- `SkillHubManager.start()` 调用 `provider.start()` + 首次 download + verify，校验通过的 skill 路径进入 `verifiedSkillPaths` 池
- 仅当配置了 `LLM_API_KEY` 时才会装配 `AgentHandler` Bean（`@ConditionalOnProperty(name = "openjiuwen.service.llm.api-key")`），否则应用只验证 SkillHub 装配链路而不创建 Agent

## 调用示例

应用启动且 Agent ready 后，向 `/v1/query` 发送非流式请求：

```powershell
$restUrl = "http://127.0.0.1:8080/v1/query?workspace_id=11&type=controller"
$body = @{
    conversation_id = "skillhub-demo-1"
    user_id = "demo-user"
    message = "你有哪些可用的 skill 或技能？请列出名称。"
    stream = $false
} | ConvertTo-Json -Depth 100

Invoke-WebRequest -UseBasicParsing -Uri $restUrl -Method Post `
  -ContentType "application/json; charset=utf-8" `
  -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
```

预期：Agent 在首次请求时由 `JiuwenCoreAgentExtHandler.installBeforeRun()` 触发 `skillHubManager.register(agent)`，把已校验的 skill 目录注册到该 Agent 的 `SkillManager`；LLM 的 system prompt 会包含各 skill 的 `SKILL.md` 描述，响应中应出现真实 skill 名称。

## 运行测试

测试受 `deepseek.api.key` 系统属性门控，仅在显式提供时执行，避免 CI 误跑需要真实 LLM 的集成测试。

### 本地 skill 注册冒烟测试

只验证 Agent 自身的 skill 注册链路，不依赖远程 SkillHub 服务——测试在临时目录构造一个含唯一标记的 SKILL.md，断言 LLM 响应包含该标记：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\skillhub-runtime-demo\pom.xml" `
  test -Dtest=ReActAgentSkillSmokeTest "-Ddeepseek.api.key=sk-xxx"
```

### 端到端生命周期测试

启动真实 Spring Boot context，连接真实 `swarmskills.openjiuwen.com` + DeepSeek，通过 HTTP `/v1/query` 完整走通下载→校验→注册→LLM 引用，并断言响应包含至少一个从 `SkillHubManager` 已注册列表解析的真实 skill 名称：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\skillhub-runtime-demo\pom.xml" `
  test -Dtest=SkillHubAgentLifecycleRealLlmTest "-Ddeepseek.api.key=sk-xxx"
```

可选：通过系统属性覆盖 SkillHub endpoint / 鉴权方式 / token 以验证不同认证分支：

```powershell
mvn ... `
  "-Dskillhub.endpoint=https://swarmskills.openjiuwen.com" `
  "-Dskillhub.auth-type=bearer" `
  "-Dskillhub.encrypted-token=<encrypted-token>"
```

## 配置字段速查

配置前缀 `openjiuwen.service.middleware.skillhub`（绑定到 `SkillHubMiddlewareProperties`，继承 `SkillHubConfig`）：

| 字段 | 类型 | 默认值 | 必填 | 功能 |
| --- | --- | --- | --- | --- |
| `enabled` | boolean | `false` | 是 | 是否启用 SkillHub 中间件链路 |
| `endpoint` | String | — | 是（启用时） | SkillHub 服务地址，空值在装配阶段 fail fast |
| `auth-type` | String | `bearer` | 否 | `bearer` 或 `system-token`，决定 `OpenJiuwenSkillHubProvider` 使用哪种鉴权头 |
| `encrypted-token` | String | 空 | 否 | 加密 token，经 runtime `CredentialDecryptor.decrypt` 解密；空串表示匿名访问 |
| `local-dir` | String | 空 | 否 | skill zip 解压后的本地根目录 |

LLM 配置前缀 `openjiuwen.service.llm`（本 demo 自有配置，不属于 SkillHub 中间件公共配置）：

| 字段 | 环境变量 | 默认值 | 功能 |
| --- | --- | --- | --- |
| `api-key` | `LLM_API_KEY` | 空 | 配置后才装配 `AgentHandler` Bean |
| `api-base` | `LLM_API_BASE` | `https://api.deepseek.com` | OpenAI 兼容接口地址 |
| `model-name` | `LLM_MODEL` | `deepseek-chat` | 模型名 |
| `provider` | `LLM_PROVIDER` | `OpenAI` | LLM provider |

> **注意**：`api-base` 只填基础 URL（如 `https://api.deepseek.com`），**不要**带 `/v1/chat/completions` 后缀——框架（`OpenAiCompatibleModelClient`）会自动拼接 `/chat/completions`，带了会导致路径重复报错 `No static resource .../chat/completions/chat/completions`。

## 日志脱敏

测试 profile `application-skillhub-remote.yml` 把 agent-core 的 `tool` 和 `llm` 日志降到 WARN，避免 skill 内容（`readFile` 结果、完整 messages JSON）泄漏到日志；SkillHub Provider / Manager 自身的 INFO 诊断（`credential=provided/absent`、download、verify、retry、register）保持可见。运行期可通过 `--spring.profiles.active=skillhub-remote` 激活。

## 失败语义

SkillHub 中间件按 PR #415 实现分层失败语义：

- required skill 的 **配置 / 认证 (`AUTH_FAILED`) / 访问拒绝 (`ACCESS_DENIED`) / 查找 (`NOT_FOUND`) / 移交** 失败 → **fail fast**，Agent 不 ready
- required skill 的 **下载 (`DOWNLOAD_FAILED`) / 完整性校验 (`CHECKSUM_MISMATCH`)** 失败 → Agent 降级为 ready，skill 不可用，后台线程定时重试
- optional skill 任何失败 → 降级跳过
- 被跳过或未校验通过的 skill 不会注册为可用

详细设计见 [Feat-Func-005-agent-middleware-request-proxy.md](https://github.com/chaosxingxc-orion/spring-ai-ascend/blob/experimental/architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-005-agent-middleware-request-proxy.md)。
