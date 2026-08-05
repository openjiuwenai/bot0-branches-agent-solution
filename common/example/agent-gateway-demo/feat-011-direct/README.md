# FEAT-011 DIRECT — Gateway example

证明：**Gateway `POST /a2a` 在 DIRECT 路径上覆盖转测主场景**（治理 + 选路转发 + 幂等 + sticky）。

## 谁被打桩？

| 角色 | 联调真身 | 本 example |
|------|----------|------------|
| 晓娜 verification-app | UI `:9090` | **curl** 直打 Gateway |
| 翼维 Client SDK 传输 | A2A HTTP/SSE | **curl**（同 wire） |
| 国庆 RDC | `:8092` | 真 RDC **或** [`stubs/downstream_stub.py`](stubs/downstream_stub.py) |
| 下游 Runtime | scripted / travel | 真 Runtime **或** 同上 stub |

Gateway **本身不打桩**——始终打真实（或你刚起的）Gateway 进程。

## 覆盖矩阵（相对转测 TC）

| TC | smoke | 说明 |
|----|-------|------|
| G1-01～03 | ✅ | 缺票 / 非 Bearer / 坏 Bearer |
| G1-04 | ✅ | 合法路径（随 create 200） |
| G1-05 / G1-06 | ⏭ | 需第二 token / resume 剥票配置 |
| G2-01 / G2-03 / G2-04 | ✅ | 权威租户；脏/同名 `X-Tenant-Id` |
| G2-02 | ⏭ | 需「过 G1 无 tenant」token |
| G3-01～03 / G3-05 | ✅ | 合法创建、坏 JSON、坏 method、空 agentId |
| G3-04 | ✅/⏭ | 默认 Agent；未配置则 SKIP |
| G4-01 / G4-02 | ✅ | REPLAY / CONFLICT |
| G4-03 | ✅ | sticky resume（不走创建幂等） |
| G4-04 | ⏭ | 需停 Runtime 构造 abort（环境操作） |
| G5-01～04 | ✅/部分 | 成功路径 + traceparent；审计日志需人工看 gateway.log |
| S2-01 流式 | ✅ | SendStreamingMessage |
| S2-02～04 / S3 工具环 / S4 | ⏭ | 偏 Client SDK / UI 多轮 |
| S3-03 sticky miss | ✅ | `RESUME_OWNER_UNKNOWN` |
| S5-01 未知 agent | ✅ | `ROUTE_NO_CANDIDATES` + 无拓扑泄漏 |
| 拓扑清洗 | ✅ | 错误体 grep |

## 跑法

```bash
# 联调栈已起（GW:8080 + RDC + Runtime）— 推荐
GATEWAY_URL=http://127.0.0.1:8080 ./smoke.sh

# 仅治理（不依赖 RDC/Runtime）
GATEWAY_URL=http://127.0.0.1:8080 ./smoke.sh --governance-only

# 无真 RDC/Runtime：先起桩，再起 Gateway 指向桩端口
python3 stubs/downstream_stub.py --rdc-port 18092 --runtime-port 18094 &
# 然后用 examples/application-example.yml，把 gateway.rdc.base-url 改为 http://127.0.0.1:18092
# 并保证 default-agent-id 为 stub 认识的 travel-hotel / scripted-verify
```

参考配置：[`../application-example.yml`](../application-example.yml)。
