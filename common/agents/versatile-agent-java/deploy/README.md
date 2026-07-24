# Adapter Versatile 独立部署手册

本目录只负责 `adapter-versatile-agent-java` 镜像及其容器。它不依赖 `deploy-all` 或 Docker Compose，也不会启动、停止或删除 EDP Agent、Redis、Versatile mock 和共享网络。

## 0. 从零开始：x86 Linux 一键部署（推荐）

本手册所有 `.sh` 脚本均为 Linux 原生 bash，可在 x86 Linux 上仅凭本代码仓完成“构建 jar → 构建镜像 → 建网络 → 起容器 → 验证”，**无需任何 Windows / PowerShell 步骤**。

前置：Linux 上已装 `git`、`docker`、`JDK 17`、`Maven 3.8+`。

```bash
# 1) 拉取代码仓
git clone <你们的仓库地址> && cd agent-solution
cd common/agents/adapter-versatile-agent-java

# 2) 准备配置（必须填写真实 VERSATILE_URL）
cp deploy/.env.example deploy/.env
vi deploy/.env

# 3) 一键：构建 jar -> 构建镜像 -> 确保网络 -> 启动 -> 验证
bash deploy/deploy.sh
```

`deploy.sh` 会自动调用 `build-jar.sh`（Maven 构建）与 `build-image.sh`（Docker 构建），因此从干净 clone 即可直接部署。若镜像已从镜像仓库拉取，用 `bash deploy/deploy.sh --skip-build` 跳过 jar 与镜像构建。

> **重要（Java 版本）**：本 adapter 依赖的共享模块 `agent-runtime-ext-java` 使用 `com.openjiuwen:agent-core-java:0.1.13`（为 JDK 17 发布的构建）。请勿改用旧的裸 `0.1.12`，否则在 x86 Linux 上会出现 `package com.openjiuwen.harness.deep_agent does not exist`、`cannot find symbol DeepAgent/InterruptRequest` 等编译错误。详见文末“常见问题”。

## 先理解两个 Docker 动作

`docker build` 把 jar 和 Dockerfile 制作成镜像；`docker network create agent-net` 创建的是容器运行时网络。两者没有先后依赖，可以先 build，也可以先建网络。唯一约束是：容器执行 `docker run --network agent-net` 前，该网络必须存在。

`start.sh` 会幂等地确保网络存在，因此部署人员通常不必手工创建。adapter 不使用 Docker volume；`edp-redis-data` 是 Redis 数据持久化卷，只应由 EDP/Redis 部署侧管理。

共享网络采用以下固定契约：

| 项目 | 默认值 | 含义 |
|---|---|---|
| Docker 网络 | `agent-net` | 同一台 Docker 主机上两个团队共同使用的 user-defined bridge |
| Adapter 网络别名 | `adapter-versatile` | EDP 容器访问 adapter 时使用的 DNS 名 |
| Adapter 容器端口 | `8191` | 固定，不允许通过本部署配置更改 |
| A2A 地址 | `http://adapter-versatile:8191/a2a` | 同机、同网络的 EDP 配置值 |
| ownership label | `com.huawei.edpa.owner=adapter-versatile-agent-java` | 防止脚本误删其他团队的同名容器 |

两个团队的启动脚本都可以执行“网络不存在则创建”。首次并发创建时，其中一方创建失败会重新检查网络；已有网络必须是 `bridge/local`。任意一方停止服务时都不得删除这个共享网络。

## 文件说明

- `.env.example`：独立部署配置模板。
- `build-jar.sh`：**（Linux 原生）** 在代码仓内构建共享依赖并打出 adapter 运行 jar。
- `build-image.sh`：只构建 adapter 镜像（要求 jar 已存在）。
- `start.sh`：只启动或更新 adapter 容器，并等待健康。
- `verify.sh`：检查所有权、容器状态、网络、Docker health 和 Agent Card。
- `stop.sh`：只删除本团队标签匹配的 adapter 容器。
- `deploy.sh`：新手一键入口，依次执行 `build-jar.sh` → `build-image.sh` → `start.sh`。
- `pack-for-linux.ps1`：**（可选，仅 Windows）** 在 Windows 上构建并打 tar 包再上传 Linux；纯 Linux 流程无需它。
- `_lib.sh`：公共函数，不单独执行。

所有脚本都按自身文件位置定位项目，可从任意当前目录执行。推荐始终写成 `bash /完整路径/deploy/start.sh`；不依赖脚本的可执行位。

## 严格部署顺序

### 1. 先准备 Versatile 上游

生产环境先确认真实 Versatile 已发布，并取得：

- 容器内可访问的 HTTP/HTTPS 地址；
- workflow 路径；
- 结果节点名称；
- 网络、防火墙和认证要求。

`VERSATILE_URL` 必须保留 `{conversation_id}`。不要填写 `localhost` 或 `127.0.0.1`，因为在 adapter 容器内它们指向 adapter 自己。

### 2. 生成或取得 adapter jar

**方式一：Linux 上直接构建（推荐）**

在代码仓内执行（脚本会先 `install` 共享依赖 `agent-runtime-ext-java`，再只构建 adapter，不构建 edp-agent）：

```bash
cd common/agents/adapter-versatile-agent-java
bash deploy/build-jar.sh
```

它等价于依次执行：

```bash
mvn -f ../../agent-runtime-ext-java/pom.xml clean install -DskipTests
mvn -f pom.xml clean package -DskipTests
```

产物为 `target/adapter-versatile-agent-java-*.jar`。镜像直接以本项目目录为构建上下文，Linux 上**无需**再打 tar 包。

> 若构建报 `package com.openjiuwen.harness.deep_agent does not exist` 或 `cannot find symbol DeepAgent/InterruptRequest`，说明解析到了错误的 `agent-core-java`，需确认版本为 `0.1.13`（见文末“常见问题”）。

**方式二：通过镜像仓库交付**

如果团队直接交付镜像，可跳过 jar 构建和 `build-image.sh`：先 `docker pull`，再把 `.env` 中 `ADAPTER_IMAGE` 改为实际版本。

**方式三（可选，仅 Windows 构建机）**

在 Windows 仓库中打 tar 包再上传 Linux：

```powershell
powershell -ExecutionPolicy Bypass -File common\agents\adapter-versatile-agent-java\deploy\pack-for-linux.ps1
# jar 已存在时可加 -SkipBuild 复用
```

产物为 `adapter-versatile-agent-java-deploy-<时间>.tar.gz`，仅含 adapter 运行 jar、`.dockerignore` 和 `deploy` 资源。上传 Linux 后：

```bash
tar xzf adapter-versatile-agent-java-deploy-*.tar.gz
cd adapter-versatile-agent-java
```

### 3. 创建并填写配置

```bash
cp deploy/.env.example deploy/.env
vi deploy/.env
```

至少填写：

```env
VERSATILE_URL=http://versatile-mock:30001/v1/0/agent-manager/workflows/wealth-invest/conversations/{conversation_id}
```

`.env` 使用 `KEY=VALUE`，等号两侧不要有空格，值不要加引号。脚本逐行安全解析，不会 `source`、`eval` 配置。

主要参数：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `ADAPTER_IMAGE` | `adapter-versatile-agent-java:latest` | 构建或启动的镜像 |
| `ADAPTER_CONTAINER_NAME` | `adapter-versatile` | 本团队容器名 |
| `ADAPTER_HOST_PORT` | `8191` | 宿主机发布端口，可改 |
| `AGENT_NETWORK` | `agent-net` | 同机部署时必须和 EDP 团队一致 |
| `ADAPTER_NETWORK_ALIAS` | `adapter-versatile` | 同机 EDP 使用的 DNS 别名 |
| `RESTART_POLICY` | `unless-stopped` | Docker 重启策略 |
| `VERSATILE_URL` | 无 | 必填，HTTP/HTTPS，保留占位符 |
| `VERSATILE_TIMEOUT` | `600s` | 上游请求超时 |
| `VERSATILE_RESULT_NODE` | `GXZQAResponseNode` | 真实工作流的结果节点名 |
| `VERSATILE_AGENT_TENANT_ID` | `edp-tenant` | A2A 默认租户 |

容器内部端口固定为 8191。即使宿主端口改成 18191，同机 EDP 仍应访问 `http://adapter-versatile:8191/a2a`，不能改成 18191。

### 4. 构建镜像

```bash
bash deploy/build-image.sh
```

该步骤不创建网络、不启动容器。如果使用已从镜像仓库拉取的镜像，跳过此步。

### 5. 启动 adapter

```bash
bash deploy/start.sh
```

脚本按顺序完成：

1. 校验 Docker、镜像和 `.env`；
2. 幂等确保 `agent-net` 是本地 bridge；
3. 检查同名容器的 ownership label；
4. 只替换本团队旧 adapter 容器；
5. 映射宿主端口，加入共享网络并设置稳定别名；
6. 等待 Docker health，再请求 Agent Card。

也可用新手一键入口：

```bash
bash deploy/deploy.sh
```

### 6. 验证

```bash
bash deploy/verify.sh
docker logs --tail 100 adapter-versatile
```

浏览器或 curl 验证：

```bash
curl -f http://127.0.0.1:8191/.well-known/agent-card.json
```

上述检查证明 adapter 进程和 A2A 发现端点可用，不会真实调用 Versatile。发布验收还必须由业务方发送一条 A2A 请求，确认 adapter 能访问真实 Versatile、workflow 路径正确且结果节点能返回。

## 两个团队如何互通

### 同一台 Docker 主机

EDP 团队也把容器接入字面量相同的 `agent-net`，并配置：

```env
EDP_AGENT_VERSATILE_A2A_URL=http://adapter-versatile:8191/a2a
```

容器间使用网络别名和容器内部端口；不要使用 `localhost`、容器临时 IP 或宿主机映射端口。

如果真实 Versatile 也是同机容器，应由其所有者将它接入 `agent-net` 并提供稳定网络别名，`VERSATILE_URL` 使用该别名。如果真实 Versatile 是宿主机进程，可填写 `host.docker.internal`；`start.sh` 检测到该主机名后会添加 Linux 的 `host-gateway` 映射。

### 不同 Docker 主机

Docker bridge 只在单机有效，`agent-net` 不能跨主机提供 DNS。EDP 应通过 adapter 主机的可路由 DNS/IP 和发布端口访问，例如：

```env
EDP_AGENT_VERSATILE_A2A_URL=http://adapter-host.example.com:8191/a2a
```

同时需要网络团队开放端口，并按环境要求配置 TLS、网关或访问控制。跨主机时仍可在各自主机保留本地 `agent-net`，但两个同名网络彼此不连通。

## mock 的边界

本部署脚本绝不构建、启动或停止 mock。生产环境必须填写真实 `VERSATILE_URL`。

仅在联调环境中，如果测试团队已发布 `versatile-mock:<version>`，可由测试环境负责人单独把它接入 `agent-net` 并提供 `versatile-mock` 别名，然后把 adapter 配置为：

```env
VERSATILE_URL=http://versatile-mock:30001/v1/0/agent-manager/workflows/wealth-invest/conversations/{conversation_id}
```

mock 的镜像版本、容器生命周期和测试数据由其所有者负责，不属于 adapter 生产部署脚本。

## 日常运维

```bash
# 查看状态和 Agent Card
bash deploy/verify.sh

# 查看日志
docker logs -f adapter-versatile

# 使用新 jar/镜像更新（start 只替换带正确 ownership label 的旧容器）
bash deploy/build-image.sh
bash deploy/start.sh

# 停止 adapter；共享网络和镜像保留
bash deploy/stop.sh
```

如果已经存在同名容器但缺少正确 ownership label，`start.sh` 和 `stop.sh` 都会拒绝删除。不要绕过保护直接删除；先确认容器所有者，或在 `.env` 中改用不冲突的 `ADAPTER_CONTAINER_NAME`。

本目录没有“删除共享网络”的命令。只有在所有关联团队确认网络已无人使用后，才应由明确的环境管理员单独清理。

## 常见问题

| 现象 | 处理 |
|---|---|
| 编译报 `package com.openjiuwen.harness.deep_agent does not exist` / `cannot find symbol DeepAgent`/`InterruptRequest` / `resolveInterrupt(Object,Object,Object,Map)` 签名不符 | 解析到了错误的 `agent-core-java`。确认 `common/agent-runtime-ext-java/pom.xml` 中版本为 `0.1.13`（JDK17 构建）；用 `mvn -q dependency:get -Dartifact=com.openjiuwen:agent-core-java:0.1.13` 验证可解析；必要时删除 `~/.m2/repository/com/openjiuwen/agent-core-java` 后重新构建。旧的裸 `0.1.12` 在部分仓库是缺包的旧构建，切勿使用 |
| Linux 执行 `.sh` 报 `bad interpreter: ...^M` | 脚本被存成 CRLF。仓库已用 `.gitattributes` 强制 `*.sh` 为 LF；仍遇到则重新 clone，或 `sed -i 's/\r$//' deploy/*.sh` |
| 提示 `VERSATILE_URL 必须填写` | 复制 `.env.example` 为 `.env` 并填写真实地址 |
| 提示 URL 不能使用 localhost | 改用容器可路由 DNS/IP；宿主机服务可用 `host.docker.internal` |
| 镜像不存在 | 运行 `build-image.sh`，或 pull 后修改 `ADAPTER_IMAGE` |
| 宿主机端口被占用 | 修改 `ADAPTER_HOST_PORT`；容器间仍使用 8191 |
| 同名容器不属于本团队 | 联系所有者或更换容器名，脚本不会误删 |
| 网络同名但不是 bridge/local | 由环境管理员协调网络名；脚本不会重建共享资源 |
| 容器 healthy 但业务失败 | 检查真实 Versatile 地址、网络、workflow、认证和结果节点 |
| 跨主机访问失败 | 不要使用 Docker 别名；改用可路由 DNS/IP 并检查防火墙 |
