# agent-gateway examples（FEAT-011 / FEAT-012）

本目录是 **Gateway 侧可运行样例 + smoke**，对应晓娜 Client 侧的
`agent-client/examples/cloud-client`（verification-app）：

| 角色 | 目录 | 职责 |
|------|------|------|
| Client example | `agent-client/examples/cloud-client` | 验证台 UI / SDK 打桩对接 |
| **Gateway example（本目录）** | `common/example/agent-gateway-demo` | 起 Gateway、直打 `/a2a` smoke、配置说明 |

与同目录下 travel / deep-research 等 demo 并列；本工程测的是 **agent-gateway** 接入面，不是多 Agent 业务链。

## 目录

```text
agent-gateway-demo/
├── README.md                 # 本文件
├── application-example.yml   # 本地起 Gateway 的参考配置
├── validate.sh               # 校验 example 自身（结构 + 可选联机 / bus unit）
├── feat-011-direct/          # FEAT-011 DIRECT 路径
│   ├── README.md
│   ├── smoke.sh
│   ├── bodies/
│   └── stubs/
└── feat-012-bus/             # FEAT-012 BUS 路径
    ├── README.md
    ├── smoke.sh
    └── bodies/
```

## 验证到什么程度

| 层级 | 本 example | 说明 |
|------|------------|------|
| 011 / 套件 R | ✅ `feat-011-direct` 或 `feat-012-bus/smoke.sh` | curl；可选 Fake RDC/Runtime |
| 012 套件 B1 | ✅ `./validate.sh --bus-unit` | Fake Outbox/投影/五态单测（需本机已编 Gateway） |
| 012 套件 B2 | ✅ `feat-012-bus/smoke.sh` | 共享入口治理（不宣称 MQ） |
| 012 套件 B3 | ❌ | 真 MQ + FEAT-017 后置 |
| 晓娜 UI / SDK 工具环 | ❌ | verification-app |

## 快速校验（不依赖联调栈）

```bash
cd common/example/agent-gateway-demo
./validate.sh
```

联机（Gateway 已在 `:8080`）：

```bash
GATEWAY_URL=http://127.0.0.1:8080 ./validate.sh --online
# Suite R+B2：
GATEWAY_URL=http://127.0.0.1:8080 ./feat-012-bus/smoke.sh
```

## 与 verification-app 配合

1. 按 `application-example.yml` 起 Gateway（`path-mode=direct` 验 011；BUS 见 yml 注释）。
2. Client 侧：`AGENT_GATEWAY_URL=http://127.0.0.1:8080` 起 verification-app。
3. 或直接跑本目录 `feat-011-direct/smoke.sh` / `feat-012-bus/smoke.sh`。
