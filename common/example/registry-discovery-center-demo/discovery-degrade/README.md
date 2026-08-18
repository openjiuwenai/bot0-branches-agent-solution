# discovery-degrade — 发现过滤与 L1/L2 降级

证明本机 **RDC + PostgreSQL** 的发现过滤，以及可选的 **L1（切断 `agent_rdc` 后走进程缓存）/ L2（Gateway 在假 RDC 503 时走本地缓存）**。

## 一键跑

```bash
cd common/example/registry-discovery-center-demo/discovery-degrade

# 推荐：L1 不交互（CI / 无人值守）
DEGRADE_L1_YES=1 ./run-online.sh

# 或交互：L1 时提示输入 yes
./run-online.sh
```

默认顺序：过滤用例 → **L1**（只切断库 `agent_rdc` 的 CONNECT，**不停**整个 PostgreSQL，不影响同机 `agentbus`）→ **L2**（fake_rdc / fake_runtime / Gateway；首次会 `mvn package` Gateway）。

| 开关 / 环境变量 | 说明 |
|-----------------|------|
| `--basic-only` | 只验注册 / 列表 / resolve |
| `--filter-only` / `--skip-l1 --skip-l2` | 只要过滤，不要降级 |
| `DEGRADE_L1_YES=1` | L1 不交互，直接切断 `agent_rdc` |
| 提示输入 `yes` | 大小写均可（`yes` / `YES`） |
| `--reuse-rdc` / `--keep` | 复用已在跑的 RDC / 结束后不杀 RDC |

依赖：本机 PostgreSQL（默认库 `agent_rdc@127.0.0.1:5432`）、`psql`、`mvn`、`curl`、`python3`、`java`（L2）。RDC 默认 `:8092`。

RDC 已在跑时也可只跑断言：

```bash
RDC_URL=http://127.0.0.1:8092 \
DATABASE_URL=postgresql://agent_rdc:agent_rdc@127.0.0.1:5432/agent_rdc \
DEGRADE_L1_YES=1 ./smoke.sh --with-l1-degrade --with-l2-degrade
```

## 覆盖矩阵

| 场景 | 默认 |
|------|------|
| 注册两实例、列表含 `routeHandle`、resolve 出 `endpointUrl` | ✅ |
| `DRAINING` 不可见 / `DEGRADED`<15s 可见 / 心跳>15s 不可见 | ✅ |
| L1：切断 `agent_rdc` 后列表仍返回进程缓存（HTTP 200） | ✅（需 `yes` 或 `DEGRADE_L1_YES=1`） |
| L2：假 RDC 先 200 后 503，Gateway 第二次仍打到 fake-runtime | ✅ |

成功时汇总为 `PASS=… FAIL=0` 且打印 `ALL CHECKS PASSED`。

## 目录

```text
discovery-degrade/
├── run-online.sh / smoke.sh         # launcher / 断言本体
├── application-example.yml          # RDC：关 deployment-discovery；L1 用短 Hikari 超时 + 缓存 TTL
├── application-gateway-l2.yml       # Gateway → fake RDC
├── bodies/                          # register / gateway-create JSON
├── lib/pg-ctl.sh                    # L1：REVOKE/GRANT agent_rdc CONNECT
└── stubs/                           # fake_rdc.py / fake_runtime.py
```
