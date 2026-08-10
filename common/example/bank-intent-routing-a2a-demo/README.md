# DeepAgent 意图路由银行多 Agent 示例

本示例用五个独立 Spring Boot 进程验证 DeepAgent 意图匹配、A2A 动态 Agent Card 目录、
本地工具路由、远端中断续接和意图变化后的重新匹配。

| 服务 | 端口 | 职责 |
|---|---:|---|
| IntentBankRouter | 18200 | 统一入口；调用 `intent_match`，执行本地工具或委托业务 Agent |
| BalanceAgent | 18201 | 查询账户余额 |
| TransferAgent | 18202 | 追问转账信息并在确认后执行 |
| WealthAdvisorAgent | 18203 | 推荐理财产品 |
| WealthPurchaseAgent | 18204 | 追问购买信息并在确认后执行 |

所有 Agent 均由 DeepAgent 构建。IntentBankRouter 通过 A2A Agent Card 的 Skill 动态建立远端
意图目录；计算器、日期和天气是只通过意图目录暴露的本地工具。普通逐 Agent Card Tool 注入
在入口服务关闭，模型只能看到 `intent_match`。

## 本地模型配置

在本目录执行：

```bash
cp application-intent_local-example.yml application-intent_local.yml
```

在 `application-intent_local.yml` 中填写 LLM 和 reranker 的真实地址、模型和密钥。该文件已被
仓库根目录的 `.gitignore` 忽略，不能提交。提交的
`application-intent_local-example.yml` 只提供结构和占位值。

五个模块的 `application.yml` 都声明了：

```yaml
spring:
  config:
    import: optional:file:./application-intent_local.yml
```

因此应从当前示例根目录启动服务；Spring Boot 会自动加载本地 YAML，不需要 `.env.local` 或
额外的环境变量加载器。环境变量仍可覆盖端口、远端 Agent URL 和匹配阈值。

## 构建与运行

先安装当前功能分支的跨仓依赖，然后构建示例：

```bash
# agent-runtime-java 仓库
mvn -pl :agent-service-app -am clean install -DskipTests -Drevision=0.1.1.post1

# agent-solution 仓库
mvn -f common/agent-core-ext-java/pom.xml clean install
mvn -f common/agent-runtime-ext-java/pom.xml clean install

# 当前示例目录
mvn clean package
```

可以分别启动五个可执行 Jar，先启动四个业务 Agent，再启动入口 Agent：

```bash
java -jar balance-agent-runtime/target/intent-bank-balance-agent-runtime-0.1.0.jar
java -jar transfer-agent-runtime/target/intent-bank-transfer-agent-runtime-0.1.0.jar
java -jar wealth-advisor-agent-runtime/target/intent-bank-wealth-advisor-agent-runtime-0.1.0.jar
java -jar wealth-purchase-agent-runtime/target/intent-bank-wealth-purchase-agent-runtime-0.1.0.jar
java -jar intent-agent-runtime/target/intent-bank-intent-agent-runtime-0.1.0.jar
```

服务健康检查和 Agent Card：

```bash
curl http://127.0.0.1:18200/health
curl http://127.0.0.1:18202/.well-known/agent-card.json
```

## 端到端验收

Linux/macOS 使用：

```bash
bash smoke-bank-intent.sh
```

需要在成功后保留五个进程日志和响应作为验收证据时使用：

```bash
BANK_INTENT_KEEP_ARTIFACTS=true bash smoke-bank-intent.sh
```

Windows PowerShell 使用：

```powershell
./smoke-bank-intent.ps1
```

```powershell
./smoke-bank-intent.ps1 -KeepArtifacts
```

脚本构建并启动全部服务，通过 IntentBankRouter 验证：

- 余额、理财推荐分别路由到正确的远端 DeepAgent；
- 在同一会话中根据上一轮推荐结果解析“刚才推荐的第一个产品”，并路由到理财购买；
- 计算、日期、天气路由到入口 Agent 的本地工具；
- 转账缺少收款人或金额时追问，信息齐全后要求确认，并使用同一 A2A 任务续接；
- 理财购买在执行前要求确认并续接；
- 转账确认阶段输入新的理财购买意图后，原 Agent 返回 `INTENT_CHANGED`，入口 Agent 重新调用
  `intent_match` 并进入理财购买确认；
- “给张三和李四各转100元”先生成计划，再按单一意图逐项执行直至计划完成。

脚本统一使用 A2A `SendStreamingMessage`。每次调用都会打印发送的 JSON-RPC 报文和收到的完整 SSE
事件序列。复杂转账中，Intent Agent 在调用业务 Agent 前通过 `bank_plan_progress` Artifact 输出实际
Todo 计划；每笔转账完成后输出更新后的计划，再进入下一笔确认。非流式 `SendMessage` 只用于获取
当前中断或最终结果，不承诺返回执行过程中的规划进度。

脚本除校验 A2A 任务状态、用户可见结果和规划 Artifact 与 `INPUT_REQUIRED` 的先后顺序外，还会读取
本次启动进程的审计日志，确认每个请求实际到达指定业务 Tool、意图跳转没有执行原转账、所有有副作用
的 Tool 只执行预期次数，并确认复杂转账中 `todo_create` 早于首次 `intent_match` 且恰好执行两个意图
步骤。

失败时脚本保留临时目录中的五个服务日志和 A2A 响应；成功时自动停止进程并清理临时目录。
