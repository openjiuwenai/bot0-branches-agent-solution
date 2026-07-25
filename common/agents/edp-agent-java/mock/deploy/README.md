# Versatile Mock 独立 Docker 部署手册

本目录只负责 Streaming-Agent-Testing-Evaluation 中的 mock 镜像和容器。它不启动、不停止、不删除 adapter、EDP Agent、Redis 或共享网络。

结论：接入这个 mock 不需要修改 adapter 的 Linux 启动命令或脚本。双方只需遵守以下运行契约：

| 项目 | 值 |
|---|---|
| 共享 Docker 网络 | agent-net |
| mock 网络别名 | versatile-mock |
| mock 容器内部端口 | 30001 |
| mock 容器监听地址 | 0.0.0.0，由 start.sh 强制设置 |
| adapter 下游 URL | http://versatile-mock:30001/v1/0/agent-manager/workflows/wealth-invest/conversations/{conversation_id} |
| adapter 结果节点 | GXZQAResponseNode |

## 1. 调用关系

~~~text
EDP Agent -> adapter-versatile:8191
                    |
                    +-> versatile-mock:30001

所有容器位于同一个 agent-net
~~~

adapter 使用 mock 的容器别名和内部端口，不使用 mock 的临时 IP，也不依赖宿主机端口映射。

## 2. network、build 和启动顺序

docker build 只生成镜像。agent-net 是容器运行时网络，两者没有先后依赖：

- 可以先 build，再创建网络；
- 也可以先创建网络，再 build；
- 只要求 docker run --network agent-net 前网络已经存在。

mock 和 adapter 的 start.sh 都会并发安全地创建或复用 agent-net。任何一方的 stop.sh 都不会删除共享网络。

技术上 adapter 可以在 mock 之前启动，但 adapter 的健康检查只证明自身端口和 Agent Card 可用，不证明下游 mock 可用。推荐严格顺序：

~~~text
构建 mock
-> 启动并验证 mock
-> 配置并启动 adapter
-> 执行 adapter 到 mock 的网络检查
-> 执行 A2A 端到端业务验证
~~~

## 3. 文件说明

- Dockerfile：Python 3.11 非 root 运行镜像。
- Dockerfile.dockerignore：排除测试、缓存、虚拟环境和本地密钥。
- requirements.txt：锁定当前已验证的直接依赖版本。
- .env.example：部署参数和 mock 行为参数模板。
- build-image.sh：只构建 mock 镜像。
- start.sh：只启动或更新 mock 容器，并等待健康。
- verify.sh：验证容器健康、Docker DNS、workflow 加载和真实 SSE 响应。
- stop.sh：只删除带有本部署 ownership label 的 mock 容器。
- deploy.sh：新手一键入口。
- pack-for-linux.ps1：在 Windows 上打包当前 mock 源码和 deploy 资源。
- _lib.sh：公共函数，不单独执行。

所有 Bash 脚本都按自身位置定位 mock 根目录，可以从任意当前目录执行。

## 4. Windows 打包并上传 Linux（可选）

如果 Linux 机器上没有完整源码，可在 Windows 执行：

~~~powershell
powershell -ExecutionPolicy Bypass -File mock\deploy\pack-for-linux.ps1
~~~

产物为 mock 目录下的 versatile-mock-deploy-<时间>.tar.gz，只包含运行所需源码和部署文件，不包含 tests、缓存或 deploy/.env。

上传 Linux 后：

~~~bash
tar xzf versatile-mock-deploy-*.tar.gz
cd mock
~~~

如果团队通过镜像仓库交付镜像，可以跳过打包和本地 build，在 .env 中填写已拉取的版本标签，然后执行 deploy.sh --skip-build。

## 5. 创建配置

在 mock 项目根目录执行：

~~~bash
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
vi deploy/.env
~~~

deploy/.env 已被 deploy/.gitignore 忽略。格式必须为 KEY=value，等号两侧不要加空格，值不要加引号。脚本不会 source 或 eval 该文件。

常用参数：

| 参数 | 默认值 | 说明 |
|---|---|---|
| MOCK_IMAGE | versatile-mock:1.0.0 | 构建或启动的镜像，生产建议使用明确版本 |
| MOCK_CONTAINER_NAME | versatile-mock | 本部署管理的容器名 |
| AGENT_NETWORK | agent-net | 必须和 adapter 一致 |
| MOCK_NETWORK_ALIAS | versatile-mock | adapter 使用的 Docker DNS 名 |
| RESTART_POLICY | unless-stopped | Docker 重启策略 |
| MOCK_PUBLISH_PORT | false | 是否发布宿主机端口；同机 adapter 不需要 |
| MOCK_HOST_BIND | 127.0.0.1 | 发布端口时的宿主绑定地址 |
| MOCK_HOST_PORT | 30001 | 可改；不会改变容器间 URL |
| MOCK_SKIP_COOKIE_AUTH | true | 联调时跳过 Cookie 鉴权 |
| MOCK_BALANCE_DELAY_SECONDS | 0 | 余额响应延时，非负整数 |
| MOCK_TRANSFER_AMOUNTS | 空 | 逗号分隔金额，例如 100,200,300 |
| MOCK_TRANSFER_MODE | cycle | cycle、last、full 或 fail |
| MOCK_LICAI_BALANCE | 1000.0 | 初始理财余额 |
| MOCK_CHUXU_BALANCE | 125680.5 | 初始储蓄余额 |
| MOCK_PRODUCT_BUY_SUCCESS | true | 是否模拟购买成功 |

start.sh 固定向容器传入 MOCK_SERVER_HOST=0.0.0.0 和 MOCK_SERVER_PORT=30001。不要依赖 config/server.json 中的 127.0.0.1，否则其他容器无法访问。

默认不发布宿主机端口，因为 /admin/reload 和 /reset_transfer_counter 没有部署层鉴权。同机 adapter 通过 agent-net 访问，不需要 -p。

## 6. 构建、启动、验证

分步执行：

~~~bash
bash deploy/build-image.sh
bash deploy/start.sh
bash deploy/verify.sh
~~~

新手一键执行：

~~~bash
bash deploy/deploy.sh
~~~

已经拉取镜像时：

~~~bash
bash deploy/deploy.sh --skip-build
~~~

verify.sh 会发起两次功能探测，覆盖以下校验，而不只是检查端口：

1. 从 agent-net 上的临时探针通过 versatile-mock:30001 请求 /health。
2. 校验 status=healthy、default workflow 存在、workflows_loaded 非空且 load_errors 为空。
3. POST 查询余额请求到真实 workflow HTTP 端点。
4. 校验 HTTP 内容类型是 text/event-stream，SSE 包含 GXZQAResponseNode 和结束帧。

Dockerfile 自带的 HEALTHCHECK 同样会拒绝 workflow 加载错误。

## 7. 配置并启动 adapter

在 adapter 项目的 deploy/.env 中配置：

~~~env
AGENT_NETWORK=agent-net
VERSATILE_URL=http://versatile-mock:30001/v1/0/agent-manager/workflows/wealth-invest/conversations/{conversation_id}
VERSATILE_RESULT_NODE=GXZQAResponseNode
~~~

adapter 的原命令不变：

~~~bash
bash deploy/start.sh
bash deploy/verify.sh
~~~

如果 adapter 已经使用上述 URL 运行，后来才启动 mock，不需要重启 adapter；请求会在 mock 可用后恢复。

如果 adapter 原先使用了其他 VERSATILE_URL，修改 .env 后必须重新执行 adapter 的 start.sh，使新环境变量进入容器。

从 adapter 容器验证 TCP/DNS：

~~~bash
docker exec adapter-versatile \
  bash -c 'exec 3<>/dev/tcp/versatile-mock/30001 && echo mock-network-OK'
~~~

该命令只证明网络连通。最终上线验收仍应向 adapter 的 /a2a 发送一条真实业务请求，确认完整的 A2A -> adapter -> mock -> SSE -> 结果节点链路。

## 8. 同主机与跨主机

同一台 Docker 主机时，双方使用 agent-net 和容器别名，mock 不需要发布宿主端口。

Docker bridge 不能跨主机。如果 adapter 在另一台主机：

1. 设置 MOCK_PUBLISH_PORT=true。
2. 根据安全策略把 MOCK_HOST_BIND 改为可访问地址；0.0.0.0 会暴露到所有宿主接口。
3. 配置防火墙和访问控制。
4. adapter 的 VERSATILE_URL 改成 mock 主机的可路由 DNS/IP 与宿主映射端口，不能再使用 versatile-mock 容器名。

mock 的管理和重置接口没有生产级鉴权，因此不建议跨不可信网络暴露。

## 9. 状态、更新和停止

~~~bash
docker logs -f versatile-mock
bash deploy/verify.sh
bash deploy/stop.sh
~~~

stop.sh 只删除属于本部署的 mock 容器，保留镜像和 agent-net。遇到同名但没有正确 ownership label 的容器时，脚本会拒绝删除。

workflow JSON 被烘焙进镜像。修改 versatile_main.py、engine、config 或 workflows 后，标准更新方式是：

~~~bash
bash deploy/build-image.sh
bash deploy/start.sh
bash deploy/verify.sh
~~~

/admin/reload 只能重新读取容器内已有文件。标准部署不挂载宿主 workflows，避免环境不可复现。

余额、转账计数等状态保存在单个 Python 进程内：

- 不需要 Docker volume；
- 容器重启后状态清空；
- 不应启动多个 Uvicorn worker；
- 多副本之间不会共享状态。

## 10. 源码回归测试

当前源码测试可在有 Python 环境时执行：

~~~bash
python -B -m unittest discover -s tests -v
~~~

这些测试覆盖 matcher 和 streamer，但不覆盖 Docker 网络和 HTTP 绑定，因此不能替代 deploy/verify.sh。

## 11. 常见问题

| 现象 | 处理 |
|---|---|
| mock 容器启动但 adapter 连接拒绝 | 确认 start.sh 强制的 0.0.0.0、双方 agent-net 和 alias |
| /health 返回 200 但 verify 失败 | 查看 load_errors，检查 config 和 workflows JSON |
| 找不到 workflow/config | docker build 的 context 必须是 mock 根目录，使用 build-image.sh |
| adapter healthy 但业务失败 | adapter health 不检查下游；先运行 mock verify，再做 adapter A2A 冒烟 |
| 修改 adapter 宿主端口后仍访问失败 | adapter 到 mock 始终使用 versatile-mock:30001，不使用宿主端口 |
| 修改 workflow 未生效 | rebuild/redeploy，或确认容器内文件后调用 /admin/reload |
| 同名容器拒绝替换 | 该容器不属于本部署；联系所有者或修改 MOCK_CONTAINER_NAME |
| 端口 30001 暴露风险 | 保持 MOCK_PUBLISH_PORT=false，或仅绑定 127.0.0.1 |
