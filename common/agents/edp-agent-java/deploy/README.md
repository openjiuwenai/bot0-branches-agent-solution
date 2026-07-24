# EDP Agent 独立 Docker 部署手册

本目录可以独立构建、启动和停止 `edp-agent-java`，不依赖 `deploy-all`、Docker Compose、adapter 源码或 mock。EDP 团队只管理两类资源：

- `edp-agent` 容器；
- 选择 `local` 模式时，由 EDP 自管的 `edp-redis` 容器及 `edp-redis-data` 数据卷。

共享网络 `agent-net` 是 EDP 与 adapter 的运行契约。两个团队的启动脚本都可以创建或复用它，但任何一方的停止脚本都不删除它。

## 0. 从零开始：64 位 Linux（amd64/arm64）一键部署（推荐）

本手册所有 `.sh` 脚本均为 Linux 原生 bash，可在 64 位 amd64 或 arm64 Linux 上仅凭本代码仓完成“构建 jar → 构建镜像 → 建网络 → 起容器 → 验证”，**无需任何 Windows / PowerShell 步骤**。构建服务器和部署服务器为同一台时，`docker build` 会自动按当前服务器架构构建对应镜像，不需要 `buildx` 或手工指定 `--platform`。

前置：Linux 上已装 `git`、`docker`、`JDK 17`、`Maven 3.8+`（构建镜像必须 Docker；构建 jar 必须 JDK17+Maven）。

ARM64 服务器在部署前先确认操作系统、Docker 和基础镜像均为 64 位 ARM：

```bash
uname -m
getconf LONG_BIT
docker info --format '{{.Architecture}}'

docker pull eclipse-temurin:21-jre-jammy
docker pull python:3.11-slim
docker pull redis:7-alpine
docker image inspect \
  --format '{{.RepoTags}} -> {{.Os}}/{{.Architecture}}' \
  eclipse-temurin:21-jre-jammy \
  python:3.11-slim \
  redis:7-alpine
```

预期 `uname -m` 为 `aarch64` 或 `arm64`，`getconf LONG_BIT` 为 `64`，三个基础镜像均为 `linux/arm64`。本手册不覆盖 32 位 ARM（如 `armv7l`）。如果镜像来自企业私有仓库或镜像代理，还必须确认该仓库实际同步了 arm64 版本；使用 external Redis 时无需拉取本地 Redis 镜像。

部署前先一次性确认上述工具均已就位且版本符合要求（git 任意现代版本、Docker 引擎可用且守护进程已运行、JDK 必须为 17、Maven 不低于 3.8）：

```bash
git --version

docker --version
docker info --format '{{.ServerVersion}}'

java -version 2>&1

mvn -version
```

预期 `git --version` 显示 `git version 2.x`；`docker --version` 显示 `Docker version 20.10` 或更高，且 `docker info` 能正常输出（说明守护进程已启动，否则后续 `docker build`/`docker run` 都会失败）；`java -version` 显示主版本为 `17`（切勿使用 11 或 21，本项目依赖的 `agent-core-java:0.1.13` 是 JDK 17 构建，class 文件 major version 61）；`mvn -version` 显示 `Apache Maven 3.8.x` 或更高，且其下 `Java version` 行同样指向 17。任一项不满足，先安装或切换到对应版本再继续。


```bash
# 1) 拉取代码仓
git clone <你们的仓库地址> && cd agent-solution

# 2) 准备配置（至少填写 EDP_AGENT_MODEL_API_KEY）
cp common/agents/edp-agent-java/deploy/.env.example common/agents/edp-agent-java/deploy/.env
chmod 600 common/agents/edp-agent-java/deploy/.env
vi common/agents/edp-agent-java/deploy/.env

# 3) 一键：构建 jar -> 构建镜像 -> 准备本地 Redis -> 启动 -> 验证
bash common/agents/edp-agent-java/deploy/deploy.sh
```

`deploy.sh` 会自动调用 `build-jar.sh`（Maven 构建）、`build-image.sh`（Docker 构建），因此从一个干净的 clone 即可直接部署。若镜像已从镜像仓库拉取，用 `bash .../deploy.sh --skip-build` 跳过 jar 与镜像构建。

> **重要（Java 版本）**：本项目依赖 `com.openjiuwen:agent-core-java:0.1.13`（为 JDK 17 发布的构建，class 文件 major version 61）。请勿改用旧的裸 `0.1.12`——某些仓库中它是缺少 `harness.deep_agent`、`core.singleagent.interrupt` 包、且 `BaseInterruptRail` 为旧签名的历史构建，会导致 `agentcore-ext` 编译报“package does not exist / cannot find symbol”。详见第 8 节排错。

### 整体链路与外部依赖

跑通端到端业务的完整链路（同一 Docker 主机）：

```text
前端 / 调用方  ──A2A──▶  edp-agent(:8190)  ──A2A──▶  adapter-versatile(:8191)  ──HTTP──▶  真实 Versatile 或 versatile-mock(:30001)
                                    │
                                    └──▶  Redis(local edp-redis / external)
```

两个团队各自负责的边界：

- **edp-agent-java 团队**：构建/部署 `edp-agent` 容器与（local 模式下的）`edp-redis`；配置大模型 API Key、`EDP_AGENT_VERSATILE_A2A_URL` 指向 adapter。
- **adapter-versatile-agent-java 团队**：构建/部署 `adapter-versatile` 容器；配置真实 `VERSATILE_URL`。
- **外部依赖（需另行解决才能跑通整体流程）**：
  - **调用方 / 前端**：向 `edp-agent` 发起 A2A 业务请求的上游（前端或联调脚本）。仅部署本两个容器不会自动产生业务流量。
  - **Versatile 上游**：adapter 最终要访问的真实 Versatile 工作流；联调阶段可由测试团队提供监听 `30001` 端口的 `versatile-mock` 并接入 `agent-net`，把 adapter 的 `VERSATILE_URL` 指向它（详见 adapter 手册“mock 的边界”）。mock/前端的生命周期不由本目录脚本管理。

## 1. 先理解 network、volume 和 build

下面的命令是“存在就复用，不存在才创建”：

```bash
docker network inspect agent-net >/dev/null 2>&1 || docker network create agent-net
docker volume create edp-redis-data
```

- `docker network inspect ... || docker network create ...`：检查名为 `agent-net` 的网络。`>/dev/null 2>&1` 隐藏检查输出，`||` 表示只有检查失败才创建。用户自定义的 bridge 网络带容器 DNS，因此 EDP 能用 `adapter-versatile` 和 `edp-redis` 这样的名字访问同网络容器。
- `docker volume create edp-redis-data`：创建 Docker named volume。它挂载到 Redis `/data`，Redis 容器被删除和重建后数据仍保留。本地脚本同时启用 AOF。
- 二者都与 `docker build` **没有先后依赖**。可以在构建镜像之前或之后创建；network 只需在 `docker run --network` 前存在，volume 只需在启动本地 Redis 前存在。本目录脚本会在正确时机自动、幂等地确保它们存在。
- volume 不能阻止 Redis Key 按业务 TTL 正常过期；它解决的是容器重建导致的非预期数据丢失。

`docker build` 只读取 Dockerfile 和构建上下文生成镜像，不会把镜像“放入”某个运行网络，也不使用运行时 volume。

## 2. 前置条件

- Linux 部署机已安装 Docker Engine，当前用户能执行 `docker info`；不要求 Compose。
- 从源码构建时，必须先生成唯一的 `engine/target/edp-agent-engine-*.jar`。
- Dockerfile 当前还会复制 governance、场景和 deploy 下的 Python 依赖/config，不能只上传一个 jar 和 Dockerfile。
- adapter 由 adapter 团队独立部署。相同 Docker 主机使用约定网络和 alias；不同主机使用可路由 URL。
- Redis 最低版本是 **5.0**。外部 Redis 启动校验需要执行 `PING` 和 `INFO server`，相应 ACL 权限必须开放。

生产部署请给 EDP 镜像使用版本号或 Git 提交号标签，不要长期复用 `latest`。这样日志和回滚记录才能准确对应代码版本。

## 3. 准备配置

这些脚本可以从任意工作目录执行，所有路径都根据脚本自身位置计算：

```bash
cp common/agents/edp-agent-java/deploy/.env.example \
   common/agents/edp-agent-java/deploy/.env
chmod 600 common/agents/edp-agent-java/deploy/.env
vi common/agents/edp-agent-java/deploy/.env
```

至少核对：

- `EDP_IMAGE`：推荐改成可审计的发布版本标签；
- `EDP_AGENT_MODEL_API_KEY`：硬性必填，脚本不会打印它；
- 模型 provider/name/base URL；
- `EDP_REDIS_DEPLOYMENT=local` 或 `external`；
- `EDP_AGENT_VERSATILE_A2A_URL`。

脚本不会 `source` 或 `eval` `.env`，所以密钥不会被当作 shell 代码执行。配置格式必须是严格的 `KEY=value`，不要在等号两侧加空格或引号。

JAR 内置配置目前包含公网测试 MCP 地址/token。示例文件用以下空值显式覆盖它们，防止未配置时误向公网测试服务发送数据：

```dotenv
EDP_MCP_MASTER_URL=
EDP_MCP_STANDBY_URL=
EDP_MCP_ACCESS_TOKEN=
EDP_MCP_APP_NAME=
```

不调用 MCP 时空值不影响启动；启用 `call_mcp` 前必须填写本环境受控的 MCP 地址和凭据。

## 4. 严格部署顺序：本地 Redis

此路径适合单机验证或可信内网的小规模环境。本地 Redis 不映射宿主机 6379 端口、默认无密码；正式生产更推荐受控的外部 Redis。

### 步骤 1：生成 jar

**Linux（推荐，在代码仓内直接构建）**：

```bash
bash common/agents/edp-agent-java/deploy/build-jar.sh
```

该脚本等价于依次执行下面两条 Maven 命令，先安装共享依赖再打 EDP：

```bash
mvn -f common/agent-runtime-ext-java/pom.xml clean install -DskipTests
mvn -f common/agents/edp-agent-java/pom.xml clean package -DskipTests
```

产物为 `engine/target/edp-agent-engine-*.jar`。因为镜像直接以本代码仓的 `edp-agent-java` 目录为构建上下文，Linux 上**不需要**再打 tar 部署包。

> 若第一条 `mvn ... agent-runtime-ext-java ... install` 报 `agentcore-ext` 编译失败（`package com.openjiuwen.harness.deep_agent does not exist`、`cannot find symbol DeepAgent/InterruptRequest`、`resolveInterrupt(Object,Object,Object,Map)` 签名不符等），几乎都是解析到了错误的 `agent-core-java` 版本。请确认 pom 中版本为 `0.1.13`（见第 8 节）。

**可选（仅当构建机是 Windows、需先打 tar 再上传 Linux 时）**：

```powershell
powershell -ExecutionPolicy Bypass -File common\agents\edp-agent-java\deploy\pack-for-linux.ps1
```

该脚本只打 EDP，产物为 tar.gz，包含 jar、governance、`scenarios/wealth-demo` 和全部 deploy 资源，不包含 adapter、mock、`deploy-all` 或 `.env` 密钥；上传 Linux 解压后进入 `edp-agent-java` 目录继续后续步骤。纯 Linux 流程无需此脚本。

### 步骤 2：编辑 `.env`

按第 3 节创建配置，保持：

```dotenv
EDP_REDIS_DEPLOYMENT=local
EDPA_REDIS_HOST=edp-redis
EDPA_REDIS_PASSWORD=
```

### 步骤 3：构建 EDP 镜像

```bash
bash common/agents/edp-agent-java/deploy/build-image.sh
```

### 步骤 4：确认 adapter 的交付状态

同一主机时，adapter 团队应把自己的容器接入同一个 `agent-net`，并提供网络 alias `adapter-versatile`。推荐先启动 adapter，再启动 EDP，以便业务链路立即可用。

adapter 暂时未上线不会阻止 EDP HTTP 服务部署；`start.sh` 会给出警告。但是在 adapter 恢复前，涉及 Versatile 的业务调用会失败。

### 步骤 5：启动本地 Redis并等待 PONG

```bash
bash common/agents/edp-agent-java/deploy/start-local-redis.sh
```

此步骤会确保：

- 共享 bridge 网络 `agent-net`；
- named volume `edp-redis-data`；
- 带 ownership label 的 `edp-redis` 容器；
- DNS alias `edp-redis`；
- Redis AOF 和健康检查。

### 步骤 6：启动并验证 EDP

```bash
bash common/agents/edp-agent-java/deploy/start.sh
bash common/agents/edp-agent-java/deploy/verify.sh
```

`start.sh` 也会幂等调用本地 Redis 准备步骤，因此步骤 5 重复执行不会创建第二套 Redis。

上述步骤 3、5、6、验证也可以合并为：

```bash
bash common/agents/edp-agent-java/deploy/deploy.sh
```

已经从镜像仓库拉取 `EDP_IMAGE` 时：

```bash
bash common/agents/edp-agent-java/deploy/deploy.sh --skip-build
```

## 5. 严格部署顺序：外部 Redis

### 步骤 1：由 Redis 运维方准备服务

确认外部 Redis：

- 版本不低于 5.0；
- 从 EDP 容器所在主机/网络可路由；
- 密码、DB 和防火墙规则正确；
- ACL 允许应用启动所需的 `PING`、`INFO server`，以及运行期 Key 读写、TTL 等命令。

首版简化手册按已验证的 single Redis 配置。sentinel/cluster 涉及节点列表和 Checkpointer 连接配置，不能只改一个 host，必须单独完成集成验证后再使用。

### 步骤 2：修改 `.env`

```dotenv
EDP_REDIS_DEPLOYMENT=external
EDPA_REDIS_MODE=single
EDPA_REDIS_HOST=redis.example.internal
EDPA_REDIS_PORT=6379
EDPA_REDIS_PASSWORD=实际密码
EDPA_REDIS_DB=0
```

容器里的 `localhost`/`127.0.0.1` 指向 EDP 容器自身，不能代表宿主机或另一台 Redis 主机。

### 步骤 3：构建/拉取镜像，确认 adapter，然后启动

```bash
bash common/agents/edp-agent-java/deploy/build-image.sh
bash common/agents/edp-agent-java/deploy/start.sh
bash common/agents/edp-agent-java/deploy/verify.sh
```

external 模式只从临时探测容器检查 TCP 连通性，并把 Redis 配置传给 EDP。脚本绝不创建、启动、停止、删除外部 Redis，也不创建本地 Redis volume。EDP 应用自身会在启动时执行 PING/INFO；失败时容器无法变为 healthy。

## 5a. Redis 高可用与数据可靠性建议

Redis 在本服务中承担两类持久化职责：Agent 的 Todo 任务列表（会话级任务规划与执行进度）与会话 Checkpointer（Agent 执行中断恢复点）。前者丢失会导致任务需要重新规划，后者丢失会导致中断的会话无法续接。因此 Redis 不仅是运行时依赖，更是**承载业务会话状态的数据存储单元**——对它的要求不只是"尽量可用"，还包括"故障后已产生的会话数据可恢复"。生产部署应从以下三个维度统筹考虑，具体方案选型与实施步骤由各团队的 Redis 运维方负责。

### 维度一：服务高可用——避免单点故障

单实例 Redis（无论 local 还是 external 的 single 模式）存在单点故障风险：所在节点宕机、网络分区、进程崩溃都会导致服务不可用。EDP 应用在启动期对 Redis 执行 fail-fast 健康检查（`PING` + `INFO server`），Redis 不可达时容器无法启动；运行期 `RedisTodoStore` 对读操作有静默降级，但 Checkpointer 路径的容错能力取决于 core-sdk 实现，不应假设 Redis 故障时业务可无损继续。因此应从源头保证 Redis 服务自身的连续性。

业界成熟的 Redis 高可用形态主要有三种，按复杂度递增：

- **主从复制（Master-Replica）**：一个主节点写、一个或多个从节点异步复制。提供读冗余和数据副本，但主节点故障需人工介入切换，不满足自动恢复要求，适合对可用性要求不高的场景。
- **哨兵（Sentinel）**：在主从基础上部署独立的 Sentinel 进程集群，负责监控主从存活、自动进行故障判定与主从切换，并通知客户端新主地址。主节点故障时可在秒级到数十秒内完成自动切换，是中小规模部署的主流选择。本项目的 Redis 配置类已支持 `sentinel` 模式（见 `TodoRedisProperties`），Lettuce 客户端会感知 Sentinel 下发的主节点切换。
- **Redis Cluster**：数据分片存储在多个分片上，每个分片自带主从，集群通过 Gossip 协议自动完成故障检测与切换。兼顾高可用与水平扩展，适合数据量大或吞吐高的场景，但运维复杂度最高。

此外，**云厂商托管 Redis**（如华为云 DCS、AWS ElastiCache 等）通常内置主备自动切换、监控告警与备份恢复，可显著降低自运维成本，推荐优先评估。

> 不论采用哪种形态，都应通过实际故障注入（例如主动停止主节点）验证切换耗时与客户端重连行为，确认 EDP 应用在切换空窗期内不会出现不可恢复的状态。

### 维度二：数据持久化与恢复——故障后找回已产生的数据

高可用机制保证的是"服务连续"，但**不等于数据零丢失**。Redis 主从复制是异步的，主节点故障瞬间未同步到从节点的写入会丢失；Sentinel/Cluster 的故障切换同样存在复制延迟导致的数据窗口。对于本服务中 Checkpointer 和 Todo 这类会话状态数据，还需从持久化和备份两个层面保证可恢复：

- **持久化机制**：Redis 提供 RDB（周期性快照）和 AOF（追加写入日志）两种持久化方式。AOF 以 `appendonly` 方式记录每条写命令，配合 `appendfsync` 策略（`everysec` 为可用性与性能的常用折中）可将故障后的数据丢失窗口控制在 1 秒量级；RDB 适合作为周期性全量备份。生产环境建议同时开启 AOF 与 RDB，AOF 优先用于故障恢复，RDB 用于异地备份与长期归档。本目录 `start-local-redis.sh` 已为 local 模式启用 AOF，external 模式需由 Redis 运维方确认持久化策略。
- **定期备份**：应将 RDB/AOF 文件定期转储到独立存储（对象存储、异地卷），并验证备份的可恢复性——"有备份"与"能恢复"是两件事，未经验证的备份等于没有备份。备份恢复演练应纳入日常运维流程。
- **RDB 与 AOF 的取舍**：对数据一致性要求极高的场景优先 AOF，对恢复速度要求高、可容忍少量数据丢失的场景可侧重 RDB。本服务的会话数据有 TTL（Todo 默认 3600 秒、Checkpointer 默认 60 分钟）且读时续期，短期丢失影响可控，但仍建议以 AOF 为主，避免切换瞬间丢失整段会话进度。
- **数据迁移与扩缩容**：更换 Redis 实例或扩容时，应通过主从同步完成数据迁移后再切换，避免直接更换连接地址导致存量会话数据丢失。

### 维度三：运行期探活与运维编排——让故障可被发现、可被自愈

Redis 自身高可用解决了"主节点故障自动切换"，但应用侧与编排平台侧还需配合，才能实现端到端的故障自愈：

- **应用健康探活**：EDP 启动期已对 Redis 执行 `PING`/`INFO` 健康检查。在容器编排平台（Docker Compose、Kubernetes 等）中，应将此检查暴露为容器的存活探针（Liveness Probe）或就绪探针（Readiness Probe）语义：Redis 不可达时，新实例不应接管流量，已运行实例应被重新调度。需注意，由于 EDP 是 fail-fast 语义，Redis 故障期间实例会反复启动失败，编排平台应配置合理的重启退避策略（如 Kubernetes 的 `CrashLoopBackOff` 退避），避免在高频重启中放大对 Redis 的压力。
- **就绪与存活的区分**：推荐将"Redis 连通性"纳入就绪探针而非存活探针——Redis 短暂抖动时，实例进程仍健康，仅暂时不接流量，待 Redis 恢复后立即就绪；若纳入存活探针，则会在 Redis 抖动期反复重启实例，反而延长恢复时间。
- **编排平台与 Redis 高可用的关系**：编排平台（如 Kubernetes）负责应用 Pod 的调度与重启，但**不能替代 Redis 自身的高可用**。在 Kubernetes 中运行 Redis 时，应使用 Redis Operator 或 StatefulSet 部署 Sentinel/Cluster，而非依赖平台重启单实例 Redis Pod——重启一个数据进程不等于完成主从故障转移。编排平台的作用是让"应用实例"与"Redis 实例"都具备被自动调度与恢复的能力，真正的数据层高可用仍需由 Sentinel/Cluster 或托管服务提供。
- **监控告警**：应对 Redis 的关键指标（主从复制延迟、内存使用、连接数、慢查询、持久化耗时、故障切换事件）建立监控与告警，故障切换发生时应能及时通知运维人员核对数据一致性。Sentinel/Cluster 的切换日志、AOF 重写日志也应纳入采集。

### 与本项目配置的对应关系

上述三个维度中，维度一对应 `EDPA_REDIS_MODE`（`single`/`sentinel`/`cluster`）及相关节点配置，维度二对应 Redis 实例自身的持久化与备份策略（不由 EDP 配置，由 Redis 运维方负责），维度三对应编排平台的探针与监控配置。本目录脚本当前覆盖的是 local/external single 模式的部署与连通性验证，sentinel/cluster 模式及生产级持久化、备份、监控方案需由 Redis 运维方在完成集成验证后另行实施。

## 5b. 沙箱（Sandbox）环境配置

EDPA 支持沙箱隔离执行模式，脚本和工具调用在独立容器中运行，与宿主机环境隔离。**默认关闭**，需要手动配置开启。

### 两层开关机制

沙箱功能有**两层开关**，必须理解其关系才能正确配置：

- **第一层开关** `EDPA_SANDBOX_GOVERNED_ENABLED`：控制治理装饰层（熔断/重试/审计）的 `AgentCoreSandboxClientFactory` Bean 创建。`false`（默认）→ 直接模式（Path 1）；`true` → 治理装饰模式（Path 2），提供熔断保护、自动重试和审计日志。
- **第二层开关** `EDPA_SANDBOX_ENABLED`：控制 EDP 沙箱功能本身（SysOperation 创建、SandboxInitHook 注册、中断 Rail 委派）。`false`（默认）→ 无沙箱功能；`true` → 启用沙箱。

| 场景 | 第一层 | 第二层 | 说明 |
|------|--------|--------|------|
| 默认（无沙箱） | false | false | 所有执行走本地，无隔离 |
| 基础沙箱 | false | true | 隔离执行，但无熔断/重试/审计装饰 |
| 完整沙箱 | true | true | 隔离执行 + 熔断保护 + 自动重试 + 审计日志（推荐） |

### 启用沙箱的配置步骤

#### 步骤 1：确认沙箱服务可用

沙箱功能依赖 JiuwenSwarm / jiuwenbox 沙箱管理服务。确认：

- 沙箱服务已部署并可从 EDP 容器内访问；
- 沙箱服务默认监听端口 8321；
- 同一 Docker 主机时，沙箱服务容器应加入 `agent-net` 网络。

#### 步骤 2：编辑 `.env`

在 `.env` 文件中修改以下配置项：

```dotenv
# 开启两层沙箱开关
EDPA_SANDBOX_GOVERNED_ENABLED=true
EDPA_SANDBOX_ENABLED=true

# 沙箱服务地址（容器内可路由的 DNS 或 IP，不要用 localhost）
EDPA_SANDBOX_SERVICE_URL=http://jiuwenbox-service:8321
```

> **注意**：`EDPA_SANDBOX_SERVICE_URL` 不要填写 `localhost` 或 `127.0.0.1`，在容器内它指向 EDP 容器自身而非沙箱服务。同一 Docker 主机使用容器名（如 `jiuwenbox-service`），跨主机使用可路由的 DNS/IP。

其余沙箱参数（超时、隔离级别、降级策略等）通常无需修改，默认值适合大多数场景。详见 `.env.example` 中沙箱段的注释说明。

#### 步骤 3：验证沙箱已启用

启动 EDP 后，检查日志确认沙箱模式：

```bash
docker logs --tail 50 edp-agent | grep -i sandbox
```

期望看到：

```text
[EDP-SANDBOX] DecoratingSandboxClient created via factory (Path 2: governed)
EdpEngineConfiguration: edpaExtHandler bean created, ... sandboxPath=governed(Path2)
```

若看到 `Path 1 (direct)` 则表示沙箱未生效，请检查两层开关是否都已设为 `true`。

#### 步骤 4：验证沙箱容器创建

首次业务请求后，检查沙箱容器是否按 `SESSION` 隔离级别创建：

```bash
docker logs edp-agent | grep -i "container.*acquire"
```

### 沙箱排错

- **日志显示 `AgentCoreSandboxClientFactory absent, using Path 1 (direct)`**：第一层开关 `EDPA_SANDBOX_GOVERNED_ENABLED` 为 false 或未设置。需同时开启两层开关。
- **沙箱服务不可达**：检查 `EDPA_SANDBOX_SERVICE_URL` 是否填写了容器内可路由的地址。同主机使用容器名，跨主机使用 DNS/IP。
- **沙箱创建超时**：增大 `EDPA_SANDBOX_CREATE_TIMEOUT`（默认30秒），或检查沙箱服务资源是否充足。
- **降级到本地执行**：`EDPA_SANDBOX_FALLBACK_ON_FAILURE=true`（默认）时，沙箱不可用会自动降级。设为 `false` 可强制隔离语义，沙箱不可用时直接报错。

## 6. EDP 与 adapter 的网络契约

### 同一台 Docker 主机

双方使用：

```text
network: agent-net
adapter alias: adapter-versatile
adapter internal port: 8191
EDP URL: http://adapter-versatile:8191/a2a
```

容器间访问使用内部端口 8191，不依赖 adapter 是否把 8191 映射到宿主机。双方脚本都应只 `ensure` 这个共享网络，不在常规停止/卸载中删除。

### 不同 Docker 主机

Docker bridge 网络不能跨主机，`adapter-versatile` 也不会跨主机解析。必须由 adapter 团队提供可路由的 DNS、负载均衡或网关地址，例如：

```dotenv
EDP_AGENT_VERSATILE_A2A_URL=https://adapter.example.internal/a2a
```

同时配置防火墙、TLS 和鉴权。外部 Redis 同理使用容器内可路由的 DNS/IP，不能依靠另一台主机上的 Docker 容器名。

`EDP_AGENT_VERSATILE_URL` 是兼容性的 REST 直连配置。当前代码只要 A2A URL 非空就优先且持续走 adapter，adapter 调用失败不会自动回退 REST；但启动校验仍要求 direct URL 是合法 http(s) URL，所以示例提供了合法兼容值。真正启用 REST 直连时必须改为真实 Versatile URL。

## 7. 停止、清理和责任边界

日常停止 EDP：

```bash
bash common/agents/edp-agent-java/deploy/stop.sh
```

它只删除带正确 ownership label 的 EDP 容器，默认保留 Redis、`edp-redis-data` 和 `agent-net`，方便升级/重启后恢复会话数据。

确实需要停止本地 Redis 时，先停止 EDP，再执行：

```bash
bash common/agents/edp-agent-java/deploy/stop-local-redis.sh
```

它删除 Redis 容器但保留 named volume。external 模式下该脚本不做任何事。

删除 Redis 数据是不可恢复操作，不属于日常 stop。必须先确认容器已停止、检查 volume label 和备份，再由运维人工执行：

```bash
docker volume inspect edp-redis-data
docker volume rm edp-redis-data
```

本目录没有、也不应提供自动删除 `agent-net` 的命令，因为该网络可能仍被 adapter 或其他服务使用。

所有可持久容器都带以下标签：

```text
com.huawei.edpa.owner=edp-agent-java
com.huawei.edpa.component=edp-agent|redis
```

脚本替换/删除固定名称容器前会核对标签。同名容器没有标签或属于其他团队时会拒绝操作。若从旧 `deploy-all` 迁移，请先由原部署负责人确认并停止/删除旧容器，不要绕过保护直接接管。

## 8. 验证与排错

```bash
docker ps --filter name=edp-agent
docker logs --tail 100 edp-agent
bash common/agents/edp-agent-java/deploy/verify.sh
```

`verify.sh` 分层检查 EDP 容器健康、Agent Card 和 Redis。adapter 不可达只产生警告，因此“EDP 自身 healthy”不等于“Versatile 端到端业务可用”；上线验收还应发送一条真实业务请求。

常见问题：

- **Maven 编译报 `package com.openjiuwen.harness.deep_agent does not exist` / `cannot find symbol DeepAgent`/`InterruptRequest` / `resolveInterrupt(Object,Object,Object,Map)` 不是抽象方法的重写**：这是解析到了错误的 `agent-core-java`。本项目必须使用带 `-jdk17` 后缀的构建：
  - 确认 `common/agent-runtime-ext-java/pom.xml` 与 `common/agents/edp-agent-java/pom.xml` 中 `agent-core-java` 版本均为 `0.1.13`（为 JDK 17 发布的构建）；
  - 确认该构件可从你们的 Maven 仓库/镜像解析（`mvn -q dependency:get -Dartifact=com.openjiuwen:agent-core-java:0.1.13`）；
  - 若本机 `~/.m2` 里曾缓存过坏的 `0.1.12`，可删除 `~/.m2/repository/com/openjiuwen/agent-core-java` 后重试，让 Maven 重新下载正确构件。
  - 说明：裸版本号 `0.1.12` 在部分仓库里对应缺包、且 `BaseInterruptRail` 为旧 4 参数签名的历史构建，这正是此前在 Linux 上编译失败的根因。
- **Linux 上执行 `.sh` 报 `bad interpreter: ...^M` 或 `no such file or directory`**：脚本被存成了 CRLF（多因在 Windows 上编辑/提交）。仓库已加入 `.gitattributes` 强制 `*.sh` 为 LF；若仍遇到，重新 clone，或执行 `sed -i 's/\r$//' common/agents/edp-agent-java/deploy/*.sh`。
- API Key 为空：编辑 `deploy/.env`，填写 `EDP_AGENT_MODEL_API_KEY`。
- Redis 启动失败：local 查看 `docker logs edp-redis`；external 检查 DNS、端口、密码、版本和 ACL。
- adapter 不通：同主机检查双方是否加入同一个**实际名称**为 `agent-net` 的网络；跨主机检查 DNS/LB 和防火墙。
- 构建时报 `deploy/requirements-mcp.txt`、config 或场景不存在：确认命令以 `edp-agent-java` 为构建上下文，并确认部署包没有漏传这些目录。
