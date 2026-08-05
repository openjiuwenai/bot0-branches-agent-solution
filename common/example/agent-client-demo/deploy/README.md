# agent-client-demo 独立 Docker 部署手册

本目录可以独立构建、启动和停止 `agent-client-demo`（把 `mock-gateway` 和 `verification-app` 打包成一个镜像），不依赖 `deploy-all`、Docker Compose 或任何外部 gateway / Redis / 大模型 API Key。本镜像团队只管理一类资源：

- `agent-client-demo` 容器。

本 demo 为单容器自检：`verification-app` 内嵌启动 `mock-gateway`，全部在容器内完成端到端断言，不涉及任何跨容器通信，因此**不创建也不使用 `agent-net` 等共享网络**。这与 `edp-agent-java` 不同——`edp-agent-java` 需要 `agent-net` 是因为它要和 `adapter-versatile` 容器跨容器通信（用容器名做 DNS 解析），那是真正的运行契约；而本 demo 没有这样的跨容器依赖。

## 0. 从零开始：64 位 Linux（amd64/arm64）一键部署（推荐）

本手册所有 `.sh` 脚本均为 Linux 原生 bash，可在 64 位 amd64 或 arm64 Linux 上仅凭本代码仓完成"构建 jar → 构建镜像 → 起容器 → 验证"，**无需任何 Windows / PowerShell 步骤**。构建服务器和部署服务器为同一台时，`docker build` 会自动按当前服务器架构构建对应镜像，不需要 `buildx` 或手工指定 `--platform`。

前置：Linux 上已装 `git`、`docker`、`JDK 17`、`Maven 3.9+`（构建镜像必须 Docker；构建 jar 必须 JDK17+Maven）。注意 Maven 版本要求是 **3.9+**（比 edp-agent-java 的 3.8+ 更高，因为本工程使用更新的插件）。

ARM64 服务器在部署前先确认操作系统、Docker 和基础镜像均为 64 位 ARM：

```bash
uname -m
getconf LONG_BIT
docker info --format '{{.Architecture}}'

docker pull maven:3.9-eclipse-temurin-17
docker pull eclipse-temurin:17-jre
docker image inspect \
  --format '{{.RepoTags}} -> {{.Os}}/{{.Architecture}}' \
  maven:3.9-eclipse-temurin-17 \
  eclipse-temurin:17-jre
```

预期 `uname -m` 为 `aarch64` 或 `arm64`，`getconf LONG_BIT` 为 `64`，两个基础镜像均为 `linux/arm64`。本手册不覆盖 32 位 ARM（如 `armv7l`）。如果镜像来自企业私有仓库或镜像代理，还必须确认该仓库实际同步了 arm64 版本。

部署前先一次性确认上述工具均已就位且版本符合要求（git 任意现代版本、Docker 引擎可用且守护进程已运行、JDK 必须为 17、Maven 不低于 3.9）：

```bash
git --version

docker --version
docker info --format '{{.ServerVersion}}'

java -version 2>&1

mvn -version
```

预期 `git --version` 显示 `git version 2.x`；`docker --version` 显示 `Docker version 20.10` 或更高，且 `docker info` 能正常输出（说明守护进程已启动，否则后续 `docker build`/`docker run` 都会失败）；`java -version` 显示主版本为 `17`（切勿使用 11 或 21，本项目用 `<release>17</release>` 把 API 与字节码锁定在 JDK 17 基线，本机更高 JDK 也可交叉编译）；`mvn -version` 显示 `Apache Maven 3.9.x` 或更高，且其下 `Java version` 行同样指向 17。任一项不满足，先安装或切换到对应版本再继续。


```bash
# 1) 拉取代码仓
git clone <你们的仓库地址> && cd agent-solution

# 2) 准备配置（本 demo 无密钥，主要是确认运行模式与端口）
cp common/example/agent-client-demo/deploy/.env.example common/example/agent-client-demo/deploy/.env
chmod 600 common/example/agent-client-demo/deploy/.env
vi common/example/agent-client-demo/deploy/.env

# 3) 一键：构建 jar -> 构建镜像 -> 启动 -> 验证
bash common/example/agent-client-demo/deploy/deploy.sh
```

`deploy.sh` 会自动调用 `build-jar.sh`（Maven 构建）和 `build-image.sh`（Docker 构建），因此从一个干净的 clone 即可直接部署。若镜像已从镜像仓库拉取，用 `bash .../deploy.sh --skip-build` 跳过 jar 与镜像构建。

### 这个 demo 验证了什么

`verification-app` 内嵌启动 `mock-gateway`，再由 SDK 经真实 HTTP + SSE 发起调用，覆盖：

1. **STREAMING + client 工具多轮**：远端经 `_interrupt` 逐个请求工具 → SDK 自动就地执行并续传 → 完成。
2. **BLOCKING + client 工具**：非流式（`SendMessage`）路径同样能驱动多轮。
3. **用户输入续传**（`continueInput`）。
4. **取消**（`cancel`，本版本非 MUST，但 wire 已打通）。

关键不变量由断言守护：每个工具恰好执行一次；ACTION 工具触发且仅触发一次审批；调用到达 `COMPLETED` / `CANCELED` 终态。CLI 模式下跑完即退出，**退出码 0 = 全部断言通过，非 0 = 失败**，可直接作为 CI / 容器健康门禁。

### 整体链路与外部依赖

本 demo 无任何外部依赖（无 Redis、无 adapter、无大模型 API Key），容器内即可完成端到端自检：

```text
verification-app  ──内嵌启动──▶  mock-gateway(127.0.0.1)  ──A2A HTTP+SSE──▶  SDK 驱动多轮断言
```

EXTERAL 模式下可让 `verification-app` 连接外部 gateway 做联调验证：

```text
verification-app  ──A2A HTTP+SSE──▶  外部 gateway（由 AGENT_GATEWAY_URL 指定）
```

## 1. 先理解 build

本 demo 为单容器自检，不创建任何 Docker 网络。CLI 模式不映射端口（无需宿主机访问）；UI 模式用 `--network host`（见第 5 节）；EXTERNAL 模式用 Docker 默认 bridge 网络访问外部 gateway。

`docker build` 只读取 Dockerfile 和构建上下文生成镜像。

**构建上下文的关键说明**：本工程是多模块 Maven reactor，父 pom 在 `common/example/agent-client-demo/pom.xml`，SDK 在 `common/agent-client/agent-client-sdk-for-jvm/` 下（通过 `<module>../../agent-client/agent-client-sdk-for-jvm</module>` 相对路径纳入 reactor）。因此 `docker build` 的构建上下文必须是 **`common/` 目录**（不是 `agent-client-demo/` 本身），Dockerfile 通过 `COPY example/agent-client-demo/...` 与 `COPY agent-client/...` 引用两个子目录。`build-image.sh` 已自动处理这一点。

## 2. 前置条件

- Linux 部署机（建议配置 4CPU、8G 即可，本 demo 是轻量验证程序，无大模型调用）已安装 Docker Engine，当前用户能执行 `docker info`；不要求 Compose。
- 从源码构建时，必须先完成 Maven 多模块构建（父 pom 一条命令即可，SDK 会随 reactor 一起构建）。
- Dockerfile 采用多阶段构建（构建阶段 `maven:3.9-eclipse-temurin-17` 已内置 Maven + JDK17，运行阶段 `eclipse-temurin:17-jre`），因此也可不在宿主机装 Maven/JDK，仅靠 Docker 即可完成构建（但本地 `build-jar.sh` 路径需要宿主机 Maven）。
- 本 demo 无 Redis、无 adapter、无大模型 API Key 等外部依赖。

生产部署请给镜像使用版本号或 Git 提交号标签，不要长期复用 `latest`。这样日志和回滚记录才能准确对应代码版本。

## 3. 准备配置

这些脚本可以从任意工作目录执行，所有路径都根据脚本自身位置计算：

```bash
cp common/example/agent-client-demo/deploy/.env.example \
   common/example/agent-client-demo/deploy/.env
chmod 600 common/example/agent-client-demo/deploy/.env
vi common/example/agent-client-demo/deploy/.env
```

至少核对：

- `ACD_IMAGE`：推荐改成可审计的发布版本标签；
- `ACD_RUN_MODE`：`CLI`（默认，跑完退出，退出码表达成败）/ `UI`（浏览器看板，常驻）/ `EXTERNAL`（连接外部 gateway）；
- `ACD_HOST_PORT`：宿主机端口（UI 模式下不生效，见第 5 节）；
- 仅 `EXTERNAL` 模式需填 `AGENT_GATEWAY_URL`。

脚本不会 `source` 或 `eval` `.env`，所以内容不会被当作 shell 代码执行。配置格式必须是严格的 `KEY=value`，不要在等号两侧加空格或引号。本 demo 无密钥，`.env` 主要是控制运行模式与端口。

## 4. 严格部署顺序：CLI 自检模式（推荐）

此路径适合 CI、容器门禁或快速验证。容器跑完即退出，退出码 0 = 全部断言通过。

### 步骤 1：生成 jar

**Linux（推荐，在代码仓内直接构建）**：

```bash
bash common/example/agent-client-demo/deploy/build-jar.sh
```

该脚本等价于执行下面一条 Maven 命令（父 pom 已把 SDK 纳入同一 reactor，无需单独 install SDK）：

```bash
mvn -f common/example/agent-client-demo/pom.xml clean package -DskipTests
```

产物为三个瘦 jar（不打 fat-jar，避免联网拉取 assembly/shade 插件依赖，保证离线 / 鲲鹏 aarch64 可构建）：

- `common/agent-client/agent-client-sdk-for-jvm/target/agent-client-sdk-for-jvm.jar`
- `common/example/agent-client-demo/mock-gateway/target/mock-gateway.jar`
- `common/example/agent-client-demo/verification-app/target/verification-app.jar`

因为镜像直接以 `common/` 目录为构建上下文，Linux 上**不需要**再打 tar 部署包。

> 若 Maven 构建报 `Failed to read artifact descriptor for com.openjiuwen:agent-client-sdk-for-jvm` 或 reactor 找不到 SDK 模块，几乎都是 SDK 目录缺失或父 pom 的 `<module>` 相对路径不对。确认 `common/agent-client/agent-client-sdk-for-jvm/pom.xml` 存在，且其 `<relativePath>` 指向 `../../example/agent-client-demo/pom.xml`（见第 8 节）。

**可选（仅当构建机是 Windows、需先打 tar 再上传 Linux 时）**：

```powershell
powershell -ExecutionPolicy Bypass -File common\example\agent-client-demo\deploy\pack-for-linux.ps1
```

该脚本打 tar.gz，包含 demo 源码、SDK 源码、已构建的 jar 和全部 deploy 资源，不包含其他 example、`deploy-all` 或 `.env`；上传 Linux 解压后进入 `common/example/agent-client-demo` 目录继续后续步骤。纯 Linux 流程无需此脚本。

### 步骤 2：编辑 `.env`

按第 3 节创建配置，保持：

```dotenv
ACD_RUN_MODE=CLI
```

### 步骤 3：构建镜像

```bash
bash common/example/agent-client-demo/deploy/build-image.sh
```

### 步骤 4：启动并验证

```bash
bash common/example/agent-client-demo/deploy/start.sh
bash common/example/agent-client-demo/deploy/verify.sh
```

`start.sh` 会在 CLI 模式下等待容器退出并解析退出码，退出码 0 = 验证通过（通过后自动清理容器），非 0 = 验证失败（容器保留便于排查）。

上述步骤 3、4 也可以合并为：

```bash
bash common/example/agent-client-demo/deploy/deploy.sh
```

已经从镜像仓库拉取 `ACD_IMAGE` 时：

```bash
bash common/example/agent-client-demo/deploy/deploy.sh --skip-build
```

## 5. UI 看板模式

适合不熟悉 Java 的人员观察断言细节。`verification-app` 内嵌一个小 HTTP 服务，浏览器打开即可看到 12 个场景的进度与绿/红断言。**不需要 Node。**

### 重要：UI 绑定 127.0.0.1 的限制

当前 `VerificationUiServer` 硬编码绑定 `127.0.0.1`（见 `verification-app/src/main/java/com/openjiuwen/client/verify/VerificationUiServer.java`）。这意味着：

- 在容器内用 `-p 8080:9090` 端口映射**无法**从宿主机访问，因为容器内服务只监听回环地址，端口映射转发的流量到达容器 eth0 但没有进程在 eth0 上监听。
- `start.sh` 在 UI 模式下会自动改用 `--network host` 启动容器，让容器直接复用宿主机网络栈，宿主机浏览器即可访问 `http://127.0.0.1:<UI_PORT>/`。
- `--network host` 与 `-p` 端口映射互斥，因此 UI 模式下 `ACD_HOST_PORT` 不生效，实际端口由 `UI_PORT`（默认 9090）决定。

### 步骤

```bash
# 1) 编辑 .env，切换为 UI 模式
vi common/example/agent-client-demo/deploy/.env
#   ACD_RUN_MODE=UI
#   UI_PORT=9090

# 2) 构建镜像（若已构建可跳过）
bash common/example/agent-client-demo/deploy/build-image.sh

# 3) 启动看板
bash common/example/agent-client-demo/deploy/start.sh
```

`start.sh` 会打印：

```text
[agent-client-demo] 验证控制台已启动：http://127.0.0.1:9090/
```

在浏览器打开该地址，点「开始验证」即可。看板是 `verification-app` 里的静态 HTML + 原生 JS（`src/main/resources/web/`），经 JDK `HttpServer` 提供；**不要**为这个验证控制台再单独起 Node 工程。

UI 模式常驻不退出，停止用：

```bash
bash common/example/agent-client-demo/deploy/stop.sh
```

## 6. EXTERNAL 模式：连接外部 gateway 联调

若你要对接自己实现的 gateway（而非内嵌的 mock-gateway），用 EXTERNAL 模式。`verification-app` 通过环境变量 `AGENT_GATEWAY_URL` 找你的 gateway。

### 步骤

```bash
# 1) 编辑 .env
vi common/example/agent-client-demo/deploy/.env
#   ACD_RUN_MODE=EXTERNAL
#   AGENT_GATEWAY_URL=http://10.0.0.5:8080   # 外部 gateway 的 baseUrl（不含 /a2a）

# 2) 构建镜像（若已构建可跳过）
bash common/example/agent-client-demo/deploy/build-image.sh

# 3) 启动联调验证
bash common/example/agent-client-demo/deploy/start.sh
```

### 关于 AGENT_GATEWAY_URL

- 给的是 baseUrl（不含 `/a2a`）。SDK 会自动在末尾追加 `/a2a`，所以你的 gateway 必须在 `POST /a2a` 路径上接收请求。如果你的 gateway 入口路径不是 `/a2a`，要么在 gateway 侧把入口挂到 `/a2a`，要么在变量里直接写完整地址（如 `http://10.0.0.5:8080/a2a`，SDK 检测到已含 `/a2a` 就不再追加）。
- 每个请求会带 `Authorization: Bearer mock-token`。你的 gateway 至少要校验"存在且非空"，缺失/空一律 401。
- EXTERNAL 模式跑完即退出，退出码 0 = 全部断言通过，非 0 = 失败。
- 跨主机部署时，`AGENT_GATEWAY_URL` 必须是容器内可路由的 DNS/IP，不能写 `localhost`/`127.0.0.1`（那指向容器自身）。

gateway 需要支持的完整协议见 `Guidance4GatewayTest.md`，这里不再重复。

## 7. 停止、清理和责任边界

日常停止：

```bash
bash common/example/agent-client-demo/deploy/stop.sh
```

它只删除带正确 ownership label 的容器。本 demo 不创建任何 Docker 网络，因此 `stop.sh` 也不涉及网络清理。

本 demo 没有 Redis、volume 等持久化资源，因此没有 `stop-local-redis.sh`、`start-local-redis.sh` 之类的脚本，也没有任何数据需要备份或恢复。CLI/EXTERNAL 模式验证通过后 `start.sh` 会自动清理容器；验证失败时容器保留以便排查，排错后用 `stop.sh` 清理。

所有容器都带以下标签：

```text
com.huawei.edpa.owner=agent-client-demo
com.huawei.edpa.component=agent-client-demo
```

脚本替换/删除固定名称容器前会核对标签。同名容器没有标签或属于其他团队时会拒绝操作。若从旧 `deploy-all` 迁移，请先由原部署负责人确认并停止/删除旧容器，不要绕过保护直接接管。

## 8. 验证与排错

```bash
# 查看容器状态
docker ps -a --filter name=agent-client-demo

# 查看最近日志（CLI/EXTERNAL 模式容器已退出时仍可查看）
docker logs --tail 100 agent-client-demo

# 重新检查退出码与日志摘要
bash common/example/agent-client-demo/deploy/verify.sh
```

`verify.sh` 会根据运行模式分层检查：CLI/EXTERNAL 模式解析退出码并打印日志摘要；UI 模式检查容器是否在运行、UI 端口是否可访问。

常见问题：

- **Maven 构建报 `Failed to read artifact descriptor for com.openjiuwen:agent-client-sdk-for-jvm` 或 reactor 找不到 SDK 模块**：SDK 目录缺失或父 pom 的 `<module>` 相对路径不对。确认 `common/agent-client/agent-client-sdk-for-jvm/pom.xml` 存在，且 `common/example/agent-client-demo/pom.xml` 中有 `<module>../../agent-client/agent-client-sdk-for-jvm</module>`；SDK 的 `pom.xml` 中 `<relativePath>` 指向 `../../example/agent-client-demo/pom.xml`。
- **Maven 构建报 Jackson 依赖无法解析**：本工程 SDK 侧仅一个 Jackson 运行时依赖（`jackson-databind:2.17.3`，会传递引入 jackson-core/jackson-annotations）。确认你的 Maven 镜像仓库可解析 `com.fasterxml.jackson.core:jackson-databind:2.17.3`。若本机 `~/.m2` 有损坏缓存，可删除 `~/.m2/repository/com/fasterxml/jackson` 后重试。
- **Linux 上执行 `.sh` 报 `bad interpreter: ...^M` 或 `no such file or directory`**：脚本被存成了 CRLF（多因在 Windows 上编辑/提交）。仓库已加入 `.gitattributes` 强制 `*.sh` 为 LF；若仍遇到，重新 clone，或执行 `sed -i 's/\r$//' common/example/agent-client-demo/deploy/*.sh`。
- **docker build 报 `COPY example/agent-client-demo/... no such file or directory`**：构建上下文目录不对。`build-image.sh` 已自动以 `common/` 为上下文；若手工执行 `docker build`，确保在 `common/` 目录下、且 `-f` 指向 `example/agent-client-demo/deploy/Dockerfile`。
- **docker build 国内 ECS 上 `apt-get` GPG/慢速失败**：本 Dockerfile 全程不使用 `apt-get`（构建阶段用 `maven:3.9-eclipse-temurin-17` 已内置工具链，运行阶段用 `eclipse-temurin:17-jre`），规避了这个问题。若拉取基础镜像慢，配置 Docker 镜像加速器（如阿里云 / 华为云加速器）。
- **UI 模式下浏览器无法访问 `http://127.0.0.1:9090/`**：确认 `start.sh` 是否用了 `--network host`（UI 模式自动启用）。若仍不通，检查宿主机 9090 端口是否被占用（改 `UI_PORT`），或宿主机防火墙是否放行。注意：`--network host` 在 Docker Desktop for Windows/Mac 上行为不同，本模式主要面向 Linux 宿主机。
- **UI 模式下用 `-p 9090:9090` 端口映射仍无法访问**：这是预期的。`VerificationUiServer` 硬编码绑定 `127.0.0.1`，端口映射转发的流量到达容器 eth0 但没有进程在 eth0 上监听。必须用 `--network host`（`start.sh` 已自动处理），或修改源码把绑定地址改为 `0.0.0.0` 后重新构建。
- **EXTERNAL 模式验证失败**：先看 `docker logs agent-client-demo` 的日志，通常能直接定位是 gateway 返回了什么导致的问题。对照 `Guidance4GatewayTest.md` 的协议要求检查你的 gateway 实现。注意 S8/S9 两个断连场景对接真实 gateway 时可能失败（见 `Guidance4GatewayTest.md` 末尾说明），可暂时忽略。
- **容器启动后立即退出且 exit=1**：CLI 模式下这表示有断言失败。用 `docker logs agent-client-demo` 查看具体失败场景与断言文案。

## 9. 与 edp-agent-java 部署的差异对照

本表供熟悉 `edp-agent-java/deploy` 的部署人员快速对照：

| 项 | edp-agent-java | agent-client-demo |
|----|----------------|-------------------|
| 构建上下文 | `edp-agent-java/`（单模块） | `common/`（多模块，SDK 与 demo 分属两个子目录） |
| Maven 版本 | 3.8+ | 3.9+（插件更新） |
| JDK 版本 | 17（`agent-core-java:0.1.13` 为 JDK17 构建） | 17（`<release>17</release>` 基线） |
| 外部依赖 | Redis（local/external）、adapter、大模型 API Key | 无（容器内端到端自检） |
| 密钥 | `EDP_AGENT_MODEL_API_KEY` 必填 | 无密钥 |
| 运行模式 | 常驻 HTTP 服务 | CLI（跑完退出）/ UI（看板常驻）/ EXTERNAL（外接 gateway） |
| 健康检查 | Agent Card HTTP + Redis PING | 退出码（CLI/EXTERNAL）/ 端口可达（UI） |
| Redis 脚本 | `start-local-redis.sh` / `stop-local-redis.sh` | 无（本 demo 不用 Redis） |
| 容器标签 owner | `edp-agent-java` | `agent-client-demo` |
| 共享网络 | `agent-net`（与 adapter 契约） | 无（单容器自检，不创建网络） |
| `pack-for-linux.ps1` 产物 | tar.gz（jar + governance + scenarios + deploy） | tar.gz（demo 源码 + SDK 源码 + jar + deploy） |

操作流程完全一致：`cp .env.example .env` → `vi .env` → `bash deploy/deploy.sh`（或分步 `build-jar.sh` → `build-image.sh` → `start.sh` → `verify.sh`），停止用 `stop.sh`，排错用 `docker logs` + `verify.sh`。
