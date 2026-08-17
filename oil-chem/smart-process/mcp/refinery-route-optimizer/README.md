# 炼厂路径收率优化 MCP 服务（refinery-route-optimizer）

> 炼厂加工路线优化器 —— 给定原油原料属性(PONA/密度/硫含量等),
> 预测各装置产品收率、对比三条加工路线(柴油加氢 / 蜡油加氢裂化 / DCC)的效益并推荐最优。

对外提供**收率预测**与**三路线效益对比**两个核心能力(含 2 个元数据工具),
供 openjiuwen 等低码平台的 Agent 通过 MCP 协议调用。

## 能力概览

| 工具 | 类型 | 说明 |
|---|---|---|
| `predict_yields_tool` | 核心 | 基于 PONA 线性模型预测某装置各产品收率 |
| `compare_routes_tool` | 核心 | 三路线(柴油加氢/蜡油加氢裂化/DCC)效益对比 + 最优推荐 |
| `list_devices` | 元数据 | 装置清单及约束参数(供 Agent 选 device_type) |
| `list_products` | 元数据 | 产品清单及单价 |

## 快速开始

### 1. 安装依赖

```bash
cd refinery-route-optimizer
pip install -r requirements.txt
```

> 依赖很轻:`mcp` + `pydantic` + `uvicorn` + `starlette`,无数据库 / 无 LLM / 无外部服务。

### 2. 启动(HTTP 模式,给 openjiuwen 用)

```bash
# Linux/Mac
export MCP_TOKEN="your-secret-token"
./start.sh

# Windows
set MCP_TOKEN=your-secret-token
start.bat
```

默认监听 `http://0.0.0.0:7489/mcp`。启动后看到:

```
[refinery-route-optimizer] HTTP 鉴权已启用 (Bearer Token)
[refinery-route-optimizer] listening on http://0.0.0.0:7489/mcp
INFO:     Uvicorn running on http://0.0.0.0:7489
```

### 3. 启动(stdio 模式,给本地 IDE / Claude Desktop 用)

```bash
python server.py --transport stdio
```

## openjiuwen 接入

1. 在 openjiuwen 的「Agent / 工作流」配置中,选择「MCP Server」类型工具。
2. 填写:
   - **服务地址**:`http://<你的服务器IP>:7489/mcp`
     > 注意:末尾不要加 `/`。客户端访问时若带了尾斜杠会被 307 重定向到无斜杠路径,大多数 MCP 客户端(openjiuwen/Claude)会自动跟随,但为避免个别客户端问题,直接配无斜杠地址最稳。
   - **鉴权方式**:Bearer Token
   - **Token**:你在 `MCP_TOKEN` 环境变量里设置的值
3. openjiuwen 会自动拉取工具清单(4 个),Agent 即可调用。

## 工具详解

> 所有工具的入参字段名、类型、描述、取值范围、是否必填均通过 `tools/list`
> 自描述暴露,调用方(openjiuwen 等)无需硬编码,握手后即可发现完整 schema。

### `predict_yields_tool`

输入 PONA + 密度 + 硫 + 装置类型,返回各产品收率(%)。

| 入参 | 类型 | 必填 | 描述 | 约束 |
|---|---|---|---|---|
| `P` | number | 是 | 烷烃含量 % | 0~100 |
| `O` | number | 是 | 烯烃含量 % | 0~100 |
| `N` | number | 是 | 环烷烃含量 % | 0~100 |
| `A` | number | 是 | 芳烃含量 % | 0~100 |
| `density` | number | 是 | 密度 g/ml | 0.6~1.0(开区间) |
| `sulfur` | number | 是 | 硫含量 ppm | >=0 |
| `device_type` | string | 是 | 装置类型 | diesel_hydro / wax_hydro_crack / dcc |

返回 JSON:
```json
{"diesel": 58.2, "naphtha": 12.1, "jet_fuel": 15.3, "...": ...}   // 收率%
```

### `compare_routes_tool`(核心)

输入 PONA 等,自动对比三路线并推荐最优。

| 入参 | 类型 | 必填 | 描述 | 约束/默认 |
|---|---|---|---|---|
| `P` | number | 是 | 烷烃含量 % | 0~100 |
| `O` | number | 是 | 烯烃含量 % | 0~100 |
| `N` | number | 是 | 环烷烃含量 % | 0~100 |
| `A` | number | 是 | 芳烃含量 % (PONA 总和应约 100%) | 0~100 |
| `density` | number | 是 | 密度 g/ml | 0.6~1.0(开区间) |
| `sulfur` | number | 是 | 硫含量 ppm | >=0 |
| `nitrogen` | number | 否 | 氮含量 ppm | 默认 0 |
| `carbon_residue` | number | 否 | 残炭 % | 默认 0 |
| `feed_rate` | number | 否 | 进料量 吨/日 | 默认 3500 |
| `batch_id` | string | 否 | 批次编号(仅展示用) | 默认空 |

返回 JSON:
```json
{
  "batch_id": "...",
  "feed_rate": 3500,
  "routes": [
    {
      "device_id": "diesel_hydro",
      "device_name": "柴油加氢",
      "route_label": "路线A",
      "products": [{"product_key","product_name","yield_pct","price","value_per_ton"}, ...],
      "total_product_value": 6500.0,
      "processing_cost": 200,
      "feedstock_cost": 5500,
      "gross_margin": 800.0,        // 吨油毛利 元/吨
      "daily_benefit": 2800000.0,   // 日效益 元
      "is_recommended": true,
      "recommendation_reason": "吨油毛利最高，达 800 元/吨，日效益 280.0 万元",
      "safety_violations": [],      // 安全违规(空=合规)
      "causal_reasons": [...]       // 因果推理说明
    }
    // ... 另外两条路线
  ],
  "best_route": "diesel_hydro",     // 最优路线 device_id
  "safety_check_passed": true,
  "safety_violations": []           // 所有路线违规汇总
}
```

### `list_devices` / `list_products`

无入参,返回装置 / 产品配置。Agent 调用前可先用这两个工具探查合法取值。

## 配置项

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `MCP_TOKEN` | (空) | Bearer Token 鉴权码;`server.py` 层面不配则 HTTP 不鉴权(仅本地调试) |
| `MCP_DATA_DIR` | `./data` | 配置文件目录;可指向自定义路径 |
| `PORT` / `HOST` | 7489 / 0.0.0.0 | 启动脚本读取的端口/地址 |

> 注意:`start.sh` / `start.bat` 在 `MCP_TOKEN` 未设置时会注入默认值 `refinery-route-optimizer-token`,即通过脚本启动时鉴权始终开启;生产部署务必改成自己的 token。
>
> 命令行参数优先级高于环境变量:`python server.py --port 8000 --host 127.0.0.1`

## 目录结构

```
refinery-route-optimizer/
├── server.py              # FastMCP 入口(4 工具 + 双传输 + 鉴权)
├── core/
│   ├── yield_calc.py      # 收率预测(线性公式)
│   ├── benefit_calc.py    # 三路线效益对比
│   └── rule_engine.py     # 安全规则 + 因果推理
├── data/                  # 配置文件(收率系数/装置/产品/价格/规则)
│   ├── yield_coefficients.json
│   ├── devices.json
│   ├── products.json
│   ├── cost_params.json
│   ├── safety_rules.json
│   └── causal_rules.json
├── requirements.txt
├── start.sh / start.bat
└── README.md
```

## 数据维护

`data/` 下的 JSON 是计算依据,工艺工程师维护:

- `yield_coefficients.json` — 收率模型系数(按装置类型分套)
- `devices.json` — 装置主数据(3 套:柴油加氢 / 蜡油加氢裂化 / DCC)
- `products.json` — 产品清单与单价
- `cost_params.json` — 加工成本参数
- `safety_rules.json` — 进料安全约束规则
- `causal_rules.json` — 因果推理规则

> 改完 JSON **无需重启**,下次调用即生效(每次调用都重新读文件)。

## 验证

```bash
# 1. 工具注册 + schema 自描述检查(字段描述/范围约束是否进 schema)
python -c "
import asyncio, json
from server import mcp
async def check():
    tools = await mcp.list_tools()
    for t in tools:
        print(f'[{t.name}] 入参: {list(t.inputSchema.get(\"properties\",{}).keys())}')
asyncio.run(check())
"

# 2. 本地调用测试(不启动服务)
python -c "
from core.benefit_calc import compare_routes
import json
print(json.dumps(compare_routes(P=45,O=2,N=35,A=18,density=0.76,sulfur=800,feed_rate=3500,batch_id='T001'), ensure_ascii=False, indent=2))
"

# 3. 启动服务后,用 MCP 客户端握手 + 拉 tools/list 验证完整 schema
python -c "
import asyncio, json
from mcp.client.streamable_http import streamablehttp_client
from mcp import ClientSession
async def main():
    async with streamablehttp_client('http://127.0.0.1:7489/mcp', headers={'Authorization':'Bearer refinery-route-optimizer-token'}) as (r,w,_):
        async with ClientSession(r,w) as s:
            await s.initialize()
            tools = await s.list_tools()
            print(json.dumps([t.inputSchema for t in tools.tools], ensure_ascii=False, indent=2))
asyncio.run(main())
"
```

## 备注

- 收率模型为线性关联式 `yield = base + P·cP + O·cO + N·cN + A·cA + density·cD + sulfur·cS`,
  系数按装置类型分三套。
- 本服务**不依赖数据库、不依赖 LLM、不依赖外部服务**,单进程纯计算,适合容器化部署。
