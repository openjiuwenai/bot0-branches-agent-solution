# versatile-a2a-adapter-demo 部署说明

本包用于生产/测试环境部署与联调。内容为 Spring Boot 可执行 jar + 外部配置 +
启停脚本 + 调测脚本 + 请求样例。

## 1. 目录结构

```
versatile-a2a-adapter-demo-0.1.0/
├── versatile-a2a-adapter-demo-0.1.0.jar   Spring Boot 可执行 jar（fat jar）
├── config/
│   └── application.yml                    外部配置（优先级高于 jar 内置配置）
├── a2a-requests/                          send-requests.sh 使用的三轮请求体 JSON
├── script/
│   ├── start.sh                           启动服务（含 --stop）
│   └── send-requests.sh                   调测：curl 发送三轮请求
└── DEPLOY.md                              本说明
```

启动后日志写入 `logs/demo.log`（不存在 `target/` 时自动创建 `logs/`）。

## 2. 部署

```bash
# 解包
tar -xzf versatile-a2a-adapter-demo-0.1.0.tar.gz
cd versatile-a2a-adapter-demo-0.1.0

# 按环境修改配置（可选）
vi config/application.yml
```

Spring Boot 会优先加载 jar 同级的 `config/application.yml`，覆盖 jar 内置配置，
无需改命令行参数。常用覆盖项：

| 配置项 | 说明 |
|---|---|
| `server.port` | 服务端口，默认 `18080`，A2A 入口为 `http://<host>:<port>/a2a/` |
| `openjiuwen.service.versatile.url-template` | 远端 Versatile 地址模板，`{conversation_id}` 会被替换为请求的 contextId |
| `openjiuwen.service.versatile.timeout` | 远端调用超时，默认 `600s` |
| `openjiuwen.service.versatile.headers-template` | 固定发给远端的请求头（如 `Cookie`、`x-language`），优先级高于透传头 |
| `openjiuwen.service.versatile.forward-header-whitelist` | 允许从 A2A 请求透传到远端的 header 白名单 |
| `openjiuwen.service.versatile.result-node-name` | 非流式结果抽取的远端节点名 |
| `openjiuwen.service.versatile.insecure-skip-verify` | 远端为自签 HTTPS 时置 `true` 跳过证书校验 |

也支持环境变量覆盖：`SERVER_PORT`、`VERSATILE_URL`（更省事时用）。

## 3. 启停

```bash
./script/start.sh            # 后台启动，轮询等待就绪（最多 30s）
./script/start.sh --stop     # 按 .demo.pid 停止
```

启动成功标志：`Ready after Xs: http://127.0.0.1:18080/a2a/`。

如需注册为 systemd 服务，ExecStart 指向 `java -jar <abs>/versatile-a2a-adapter-demo-0.1.0.jar`，
工作目录设为包根目录（保证 `config/` 生效），并自行管理日志重定向。

## 4. 调测

```bash
# 默认三轮 SendStreamingMessage（流式 SSE）
./script/send-requests.sh

# 只发第 2 轮，非流式 SendMessage
./script/send-requests.sh --round 2 --non-stream

# 发送任意请求 JSON（不完全用 request-N.json 时）
./script/send-requests.sh --file /path/to/my-request.json

# 发一发自定义 body 的示例（演示 query/intent/headers/query 参数如何传）
./script/send-requests.sh --custom

# 指定入口地址（例如本机对外网卡）
./script/send-requests.sh --url http://<host>:18080/a2a/
```

**请求体与程序解耦**：`send-requests.sh` 优先读包内 `a2a-requests/`（与 jar 同级），
回退到 jar 内置的 `src/main/resources/a2a-requests/`。调测请求体可以直接改
`a2a-requests/request-{1,2,3}.json`（或放任意文件用 `--file` 指定），**不需要
重新打包**；只有改程序/配置才需要重新部署。

body / headers 传参规则见 `script/README.md` 第 4 节（或仓库内
`VersatileRequestExtractor.java`）：`message.text` 决定 query/intent；
`metadata.body.custom_data` 决定远端 body 基底；`metadata.headers` 按白名单透传；
`metadata.query` 进 URL。

## 5. 观察日志

```bash
tail -f logs/demo.log | grep -E 'Versatile (remote|outbound) request'
```

- `Versatile remote request`：解析后的远端请求摘要（url / headers / params / body keys）
- `Versatile outbound request`（DEBUG 级）：完整请求体
- `Versatile invocation failed`：远端调不通，按第 6 节排查

## 6. 常见问题

- **URL 出现 `.../conversations/xxx}` 残留大括号**：`VERSATILE_URL` 的值含 `{conversation_id}`，
  若自己改脚本时写成 `export VERSATILE_URL="${VERSATILE_URL:-http://.../{conversation_id}}"`，
  bash 会把 `{conversation_id}` 的 `}` 误认为参数展开结束符，残留一个 `}`。
  正确写法见 `script/start.sh`（用 `if` + 直接赋值）。
- **远端 Versatile 连接失败**：确认 `url-template` 指向可达地址；容器/跨主机时
  不要用 `127.0.0.1` 指代远端，改用真实 IP（或 `host.docker.internal`）。
- **A2A 请求 404**：确认入口是 `http://<host>:<port>/a2a/`（带斜杠）。
- **自签 HTTPS 报证书错误**：设置 `insecure-skip-verify: true`。
- **改配置不生效**：确认 `config/application.yml` 位于 jar 同级目录，且重启了进程。