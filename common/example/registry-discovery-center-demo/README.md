# registry-discovery-center-demo

`registry-discovery-center`（RDC）侧可运行样例 + 联机 smoke，对标同目录下的 `agent-gateway-demo`。

| 角色 | 目录 | 职责 |
|------|------|------|
| 产品模块 | `common/agent-bus/registry-discovery-center` | RDC 应用本体 + 单测 |
| **本目录** | `common/example/registry-discovery-center-demo` | 联机验收套件（无 Docker） |

## 目录

```text
registry-discovery-center-demo/
├── README.md                 # 本文件（总览）
└── discovery-degrade/        # 发现过滤 + L1/L2 降级联机冒烟
    ├── README.md
    ├── run-online.sh         # 一键：起 RDC → smoke → 收尾
    ├── smoke.sh              # 断言本体（假定 RDC 已在跑）
    ├── application-*.yml
    ├── bodies/ lib/ stubs/
    └── ...
```

## 快速跑

```bash
cd common/example/registry-discovery-center-demo/discovery-degrade
DEGRADE_L1_YES=1 ./run-online.sh
```

只要过滤、不要降级：

```bash
./run-online.sh --filter-only
```

细节（开关、覆盖矩阵、`smoke.sh` 单独用法）见 [`discovery-degrade/README.md`](discovery-degrade/README.md)。
