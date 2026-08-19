# versatile-a2a-adapter-demo 脚本使用说明

本目录提供 shell 脚本 + Docker 镜像两种方式启动 demo 服务并发请求，是根目录
`README.md`（PowerShell 方式）的 Linux/容器等价实现。

```
script/
├── start.sh          启动 demo jar（宿主机方式）
├── send-requests.sh  用 curl 向 A2A 入口发送三轮请求（流式/非流式/自定义 body）
├── start-handoff.sh  启动/停止 versatile-controller-handoff-demo 的 L1/L2 双 runtime
└── build-image.sh    构建 Docker 镜像（eclipse-temurin:17-jdk-alpine，含两个 demo）
```

配套文件（demo 模块根目录）：

```
Dockerfile     基于 eclipse-temurin:17-jdk-alpine，容器内目录结构与仓库一致
.dockerignore  构建上下文裁剪
```

镜像同时可调试两个 demo：

| demo | 入口 | 说明 |
|---|---|---|
| versatile-a2a-adapter-demo | `:18080/a2a/` | 对接远端 Versatile（`start.sh` + `send-requests.sh`） |
| versatile-controller-handoff-demo | `:18091` / `:18092` | FEAT-002 控制器意图转调，自包含 mock 控制器无外部依赖（`start-handoff.sh` / `local-e2e.sh`） |

---

## 1. 快速开始（宿主机直接跑）

```bash
# 1) 构建 jar（首次）
mvn -f common/example/versatile-a2a-adapter-demo/pom.xml clean package -DskipTests

# 2) 启动服务（默认端口 18080，4 秒内就绪）
cd common/example/versatile-a2a-adapter-demo
./script/start.sh

# 3) 另一终端发三轮请求（默认流式 SendStreamingMessage）
./script/send-requests.sh
```

停止服务：`./script/start.sh --stop`。

---

## 2. 快速开始（Docker 镜像）

容器只用于调测：**启动不自动拉起服务**，进入容器后自行 `start.sh` / `send-requests.sh`。

```bash
# 1) 构建镜像（jar 缺失时会自动先 mvn 构建）
cd common/example/versatile-a2a-adapter-demo
./script/build-image.sh

# 2) 启动容器（仅保持存活，不启动服务）
#    方式一（Linux 推荐）：--network host，容器内 127.0.0.1 即宿主机 127.0.0.1，
#    远端 Versatile 跑在宿主机 127.0.0.1:31113 时可直接访问
docker run -d --name versatile-demo --network host versatile-a2a-adapter-demo:latest

#   方式二（Docker Desktop / 端口映射）：远端 Versatile 地址需指向宿主机入口
#         （只调 controller-handoff demo 时不需 VERSATILE_URL，也不必映射 18080）
docker run -d --name versatile-demo -p 18080:18080 -p 18091:18091 -p 18092:18092 \
  -e VERSATILE_URL=http://host.docker.internal:31113/v1/0/agents/{agent_id}/conversations/{conversation_id} \
  versatile-a2a-adapter-demo:latest

# 3) 进入容器，手动启动服务并发请求（容器内路径与仓库一致）
docker exec -it versatile-demo /bin/sh
/app/script/start.sh                      # demo1: 手动启动服务（Ready 后可调测）
/app/script/send-requests.sh              # 三轮默认请求
/app/script/send-requests.sh --round 2 --non-stream
/app/script/start.sh --stop               # 停止服务
/app/script/start-handoff.sh              # demo2: controller-handoff L1(:18091)/L2(:18092)
/app/script/start-handoff.sh --stop       # 停止两个 runtime
exit                                       # 退出容器，容器继续存活

# 查看服务端日志（容器外）
docker logs -f versatile-demo        # 或容器内: tail -f /app/target/demo.log
                                          #           tail -f /app/controller-handoff-demo/target/layer{1,2}.log
```

controller-handoff demo 十场景旅程验收（自动起停 L1/L2 并逐场景断言）：

```bash
docker exec versatile-demo sh -c 'SKIP_BUILD=1 /app/controller-handoff-demo/scripts/local-e2e.sh'
```

交互式一步到位：`docker run -it --entrypoint /bin/sh versatile-a2a-adapter-demo:latest`，
进入后依次执行 `/app/script/start.sh && /app/script/send-requests.sh`。

**请求体独立于镜像（生产改请求体不用重建镜像）**：
`send-requests.sh` 优先读 `/app/a2a-requests/`（镜像内置的兜底在
`/app/src/main/resources/a2a-requests/`）。生产环境把请求体放宿主机目录，
用 `-v` 挂载覆盖即可：

```bash
# 宿主机准备请求体（任意文件名也行，用 --file 指定）
mkdir -p ~/versatile-demo/a2a-requests
# ... 把你的 request-1.json 等放进去 ...

docker run -d --name versatile-demo -p 18080:18080 \
  -v ~/versatile-demo/a2a-requests:/app/a2a-requests:ro \
  -e "VERSATILE_URL=http://host.docker.internal:31113/v1/0/agents/main_planner/conversations/{conversation_id}" \
  versatile-a2a-adapter-demo:latest

# 之后改宿主机 ~/versatile-demo/a2a-requests/ 下的 JSON 即可，镜像保持不变
# （先确保容器内服务已启动: docker exec versatile-demo /app/script/start.sh）
docker exec versatile-demo /app/script/send-requests.sh --round 1
docker exec versatile-demo /app/script/send-requests.sh --file /app/a2a-requests/my-prod-request.json
```

交互模式：`docker run -it --entrypoint /bin/sh versatile-a2a-adapter-demo:latest`，
进入后依次执行 `/app/script/start.sh && /app/script/send-requests.sh`。

---

## 3. 脚本参数

### start.sh

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | `18080` | 本地 A2A runtime 端口 |
| `VERSATILE_URL` | `http://127.0.0.1:31113/v1/0/agents/{agent_id}/conversations/{conversation_id}` | 远端 Versatile 地址模板；`{conversation_id}`→请求 contextId，`{agent_id}`→`params.metadata.agent_id`（缺失替换为空串） |
| `JAR` | 自动探测 `target/*.jar` | 显式指定 jar 路径 |

`./start.sh --stop` 通过 `.demo.pid` 停止进程。日志写入 `target/demo.log`。

### start-handoff.sh

启动 versatile-controller-handoff-demo（FEAT-002 控制器意图转调）的 L1/L2 双
runtime 供长时调测；模块在 `common/example/versatile-controller-handoff-demo`
（场景关键字、配置说明见其 README）。

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `L1_PORT` | `18091` | 一级 runtime 端口（`/v1/query` 调试入口） |
| `L2_PORT` | `18092` | 二级 runtime 端口 |
| `HANDOFF_DIR` | 自动探测 | demo 模块目录（仓库布局 / 容器 `/app/controller-handoff-demo`） |
| `JAR` | 自动探测 `target/*.jar` | 显式指定 jar 路径 |

`./start-handoff.sh --stop` 通过 `.handoff-l{1,2}.pid` 停止两个 runtime。
日志写入 `$HANDOFF_DIR/target/layer{1,2}.log`。启动顺序先 L2 后 L1（静态发现），
脚本会等待 L1 拉到 `agent_card_l2` 后才报 Ready。

调试示例：

```bash
./script/start-handoff.sh
curl -N -X POST "http://127.0.0.1:18091/v1/query" -H "Content-Type: application/json" \
  -H "X-User-ID: u-42" \
  -d '{"conversation_id":"debug-1","stream":true,"messages":[{"role":"user","content":"帮我转调订机票"}]}'
```

十场景验收（自动起停、逐场景断言）：`SKIP_BUILD=1 ../versatile-controller-handoff-demo/scripts/local-e2e.sh`。

### send-requests.sh

| 参数 | 默认 | 说明 |
|---|---|---|
| `--round <1\|2\|3\|all>` | `all` | 发送第几轮（复用 `a2a-requests/request-{1,2,3}.json`） |
| `--file <path>` | — | 发送任意请求 JSON（指定后只发一次，不读 request-N.json） |
| `--stream` | 默认 | `SendStreamingMessage`，SSE 流式返回 |
| `--non-stream` | — | `SendMessage`，一次性 JSON 返回 |
| `--url <URL>` | `http://127.0.0.1:18080/a2a/` | 本地 A2A 入口 |
| `--custom` | — | 发送脚本内置的自定义 body 示例 |

请求体目录探测顺序：**`a2a-requests/`（与 jar 同级，可挂载覆盖）优先**，
回退到镜像内置的 `src/main/resources/a2a-requests/`。

### build-image.sh

| 参数 | 默认 | 说明 |
|---|---|---|
| `--tag <TAG>` | `versatile-a2a-adapter-demo:latest` | 镜像 tag |
| `--jar <PATH>` | 自动探测 `target/*.jar` | 指定要 COPY 进镜像的 jar |
| `--out <DIR>` | `target/` | 镜像 tar 包输出目录 |
| `--gzip` | 关闭 | 导出 gzip 压缩的镜像包 `.tar.gz` |

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `HANDOFF_JAR` | 自动探测 | 指定 controller-handoff demo 的 jar（默认探测兄弟模块 `target/`，缺失时自动 mvn 构建） |

镜像同时打包两个 demo：本 demo jar + controller-handoff demo
（stage 到 `target/image-extra/controller-handoff-demo/` 后 COPY 为
`/app/controller-handoff-demo/`）。构建上下文外（兄弟模块缺失）时仅告警、
镜像不含 handoff demo。

构建成功后自动 `docker save` 导出镜像包：
`target/versatile-a2a-adapter-demo-<版本>.tar`。复制到生产环境离线部署：

```bash
scp target/versatile-a2a-adapter-demo-0.1.0.tar <host>:<dir>/
# 生产机（无需联网拉镜像）
docker load -i versatile-a2a-adapter-demo-0.1.0.tar
docker run -d --name versatile-demo --network host versatile-a2a-adapter-demo:latest
```

---

## 4. 输入的 body / headers 是怎么传给 Versatile 的

A2A JSON-RPC 请求体里，adapter 只认 `params.metadata` 下的三类东西：

```
message.parts[0].text
    └─ 若为 {"query","intent"} JSON → 解析出 query/intent，否则整段文本作为 query
       【覆盖】最终远端 body 的 custom_data.inputs.query / .intent（每轮变化的内容放这里）

metadata.body
    ├─ custom_data        → 远端 HTTP body 的【基底】：整个 custom_data 原样成为远端 body 顶层字段
    │    └─ inputs        → 远端 body.inputs 的基底，query/intent 会被上面的 message.text 覆盖
    └─ 顶层字段 (input / conversation_id / timeout / role_id / role_name / stream ...)
         默认【不会】进入远端请求！除非 application.yml 配置了
         interrupt.resume-request-template.body，用 {字段名} 占位符引用

metadata.headers
    └─ 按 application.yml 的 forward-header-whitelist 过滤后透传，
       再叠加 headers-template（template 优先级最高，同名覆盖）

metadata.query
    └─ 作为 query 参数拼到远端 URL 上

metadata.agent_id            (顶层字段，非 body.agent_id)
    └─ 当 url-template 含 {agent_id} 占位符时，用它替换；缺失则替换为空串。
       与 {conversation_id}（取自 contextId）一起决定远端 URL。
```

一句话：**message.text 决定 query/intent；metadata.body.custom_data 决定远端 body
基底；metadata.headers 按白名单透传；metadata.query 进 URL；
metadata.agent_id + contextId 进 url-template 占位符。**

完整实现见
`common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-versatile/
src/main/java/com/openjiuwen/service/adapters/versatile/agentfw/VersatileRequestExtractor.java`。

---

## 5. 常见问题

- **远端 Versatile 连接失败（`Versatile invocation failed` / Connection refused）**
  容器内 `127.0.0.1:31113` 指向的是容器自身。远端服务在宿主机时：
  - Linux：用 `--network host` 启动容器；
  - Docker Desktop：`--env VERSATILE_URL=http://host.docker.internal:31113/...`。
- **URL 出现 `.../conversations/xxx}` 残留大括号**：`VERSATILE_URL` 的值含 `{conversation_id}`，
  不要用 `export VERSATILE_URL="${VERSATILE_URL:-http://.../{conversation_id}}"` 这种写法，
  bash 会把 `{conversation_id}` 的 `}` 误认为参数展开结束符导致残留 `}`（详见 start.sh 注释）。
- **观察最终发给远端 Versatile 的请求**：日志里搜 `Versatile remote request` /
  `Posting Versatile request`（DEBUG 级别还有 `Versatile outbound request` 完整 body）。
- **jar 未构建**：`start.sh` / `build-image.sh` 都会给出提示并自动构建。