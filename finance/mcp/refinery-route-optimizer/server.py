# -*- coding: utf-8 -*-
"""慧炼 MCP Server —— 对外提供收率预测 / 路线对比 等工具。

供 Dify 等低码平台的 Agent 通过 MCP 协议调用。

传输:
  - http (默认, :7489): streamable-http,给 Dify 等远程平台用
  - stdio            : 给本地 IDE / Claude Desktop 用

鉴权:
  - 环境变量 MCP_TOKEN 配置;http 模式校验 Authorization: Bearer <token>
  - 不配 MCP_TOKEN 则不鉴权(本地调试);stdio 模式跳过鉴权

启动:
  python server.py                          # 默认 http :7489
  python server.py --transport stdio        # stdio 模式
  python server.py --port 8000              # 改端口
"""
import argparse
import json
import os
import sys
from pathlib import Path
from typing import Annotated

from pydantic import Field
from mcp.server.fastmcp import FastMCP

# 确保 core/ 可被 import(独立运行场景)
sys.path.insert(0, str(Path(__file__).parent))

from core.yield_calc import predict_yields, load_coefficients  # noqa: E402
from core.benefit_calc import compare_routes  # noqa: E402
from core.rule_engine import _load  # noqa: E402


# ---------------------------------------------------------------------------
# 共享入参类型:用 Annotated[Field] 让描述与范围约束自动进 MCP schema,
# 调用方(Dify 等)无需硬编码即可自描述调用。扁平结构,无 $ref 嵌套。
# ---------------------------------------------------------------------------
PonaP = Annotated[float, Field(description="烷烃含量 % (0~100)", ge=0, le=100)]
PonaO = Annotated[float, Field(description="烯烃含量 % (0~100)", ge=0, le=100)]
PonaN = Annotated[float, Field(description="环烷烃含量 % (0~100)", ge=0, le=100)]
PonaA = Annotated[float, Field(description="芳烃含量 % (0~100,PONA 总和应约 100%)", ge=0, le=100)]
Density = Annotated[float, Field(description="密度 g/ml (0.6~1.0)", gt=0.6, lt=1.0)]
Sulfur = Annotated[float, Field(description="硫含量 ppm (>=0)", ge=0)]
Nitrogen = Annotated[float, Field(description="氮含量 ppm (默认 0)", ge=0, default=0)]
CarbonResidue = Annotated[float, Field(description="残炭 % (默认 0)", ge=0, default=0)]
FeedRate = Annotated[float, Field(description="进料量 吨/日 (默认 3500)", gt=0, default=3500)]
BatchId = Annotated[str, Field(description="批次编号(可选,仅展示用)", default="")]
DeviceType = Annotated[str, Field(
    description="装置类型,可选值: diesel_hydro(柴油加氢) / wax_hydro_crack(蜡油加氢裂化) / dcc(DCC)。可用 list_devices 工具查询"
)]


# ---------------------------------------------------------------------------
# FastMCP 实例
# ---------------------------------------------------------------------------
mcp = FastMCP("refinery-route-optimizer")


# ---------------------------------------------------------------------------
# 工具 1:收率预测(核心)
# ---------------------------------------------------------------------------
@mcp.tool()
def predict_yields_tool(
    P: PonaP,
    O: PonaO,
    N: PonaN,
    A: PonaA,
    density: Density,
    sulfur: Sulfur,
    device_type: DeviceType,
) -> str:
    """基于 PONA 线性关联模型预测某装置各产品收率。

    公式: yield = base + P*cP + O*cO + N*cN + A*cA + density*cD + sulfur*cS

    返回 JSON 字符串,形如 {"diesel": 58.2, "naphtha": 12.1, ...},值为收率%。
    装置类型可选值见 list_devices 工具。
    """
    result = predict_yields(P=P, O=O, N=N, A=A, density=density, sulfur=sulfur,
                            device_type=device_type)
    return json.dumps(result, ensure_ascii=False)


# ---------------------------------------------------------------------------
# 工具 2:三路线效益对比(核心)
# ---------------------------------------------------------------------------
@mcp.tool()
def compare_routes_tool(
    P: PonaP,
    O: PonaO,
    N: PonaN,
    A: PonaA,
    density: Density,
    sulfur: Sulfur,
    nitrogen: Nitrogen = 0,
    carbon_residue: CarbonResidue = 0,
    feed_rate: FeedRate = 3500,
    batch_id: BatchId = "",
) -> str:
    """对柴油加氢 / 蜡油加氢裂化 / DCC 三条加工路线做效益对比并推荐最优。

    自动计算三路线各自的产品收率、吨油毛利、日效益、安全合规性,
    并给出最优推荐(device_id + 推荐理由)。这是慧炼项目的核心能力。

    返回 JSON,结构:
      {
        "batch_id": str, "feed_rate": float,
        "routes": [
          {"device_id","device_name","route_label","products":[...],
           "total_product_value","processing_cost","feedstock_cost",
           "gross_margin","daily_benefit","is_recommended",
           "recommendation_reason","safety_violations":[...],"causal_reasons":[...]}
        ],
        "best_route": "device_id",        # 最优路线
        "safety_check_passed": bool,
        "safety_violations": [...]         # 所有路线违规汇总
      }
    """
    result = compare_routes(
        P=P, O=O, N=N, A=A, density=density, sulfur=sulfur,
        nitrogen=nitrogen, carbon_residue=carbon_residue,
        feed_rate=feed_rate, batch_id=batch_id,
    )
    return json.dumps(result, ensure_ascii=False)


# ---------------------------------------------------------------------------
# 工具 3:装置清单(元数据)
# ---------------------------------------------------------------------------
@mcp.tool()
def list_devices() -> str:
    """列出所有可用加工装置及其约束参数。

    Agent 调 predict_yields_tool 前可先调本工具,获取合法的 device_type 取值
    及各装置的进料约束(硫/氮/残炭/密度限值)。

    Returns:
        JSON 数组,每项含 device_id / name / device_type / capacity /
        sulfur_limit / nitrogen_limit / carbon_residue_limit / density_min / density_max。
    """
    devices = _load("devices.json")
    return json.dumps(devices, ensure_ascii=False)


# ---------------------------------------------------------------------------
# 工具 4:产品清单(元数据)
# ---------------------------------------------------------------------------
@mcp.tool()
def list_products() -> str:
    """列出所有产品及其单价。

    Returns:
        JSON 对象,{产品key: {product_id, name, unit, price, transfer_price}}。
    """
    products = _load("products.json")
    return json.dumps(products, ensure_ascii=False)


# ---------------------------------------------------------------------------
# HTTP 鉴权中间件(Bearer Token)
# ---------------------------------------------------------------------------
def _build_auth_middleware():
    """构造 Bearer Token 鉴权中间件;未配 MCP_TOKEN 则返回 None。"""
    token = os.getenv("MCP_TOKEN", "").strip()
    if not token:
        return None

    from starlette.middleware.base import BaseHTTPMiddleware
    from starlette.responses import JSONResponse

    EXPECTED = token

    class BearerAuthMiddleware(BaseHTTPMiddleware):
        async def dispatch(self, request, call_next):
            auth = request.headers.get("Authorization", "")
            if not auth.startswith("Bearer "):
                return JSONResponse(
                    {"error": "missing or invalid Authorization header"},
                    status_code=401,
                )
            if auth[7:].strip() != EXPECTED:
                return JSONResponse(
                    {"error": "invalid token"},
                    status_code=401,
                )
            return await call_next(request)

    return BearerAuthMiddleware


# ---------------------------------------------------------------------------
# 启动
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(description="慧炼 MCP Server")
    parser.add_argument(
        "--transport",
        choices=["stdio", "http"],
        default="http",
        help="传输方式: http(默认,给 Dify 等远程平台) / stdio(给本地 IDE)",
    )
    parser.add_argument("--host", default="0.0.0.0", help="HTTP 监听地址(默认 0.0.0.0)")
    parser.add_argument("--port", type=int, default=7489, help="HTTP 端口(默认 7489)")
    args = parser.parse_args()

    if args.transport == "stdio":
        # stdio 模式:本地调用,无需鉴权
        mcp.run(transport="stdio")
        return

    # http 模式:streamable-http,挂鉴权中间件
    token = os.getenv("MCP_TOKEN", "").strip()
    auth_mw = _build_auth_middleware()

    import uvicorn
    app = mcp.streamable_http_app()
    if auth_mw is not None:
        app.add_middleware(auth_mw)
        print(f"[refinery-route-optimizer] HTTP 鉴权已启用 (Bearer Token)", flush=True)
    else:
        print(f"[refinery-route-optimizer] HTTP 未配 MCP_TOKEN,鉴权关闭(仅本地调试用)", flush=True)

    print(f"[refinery-route-optimizer] listening on http://{args.host}:{args.port}/mcp", flush=True)
    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
