"""
mcp_main.py — 可配置 Mock MCP Server（MCP SSE 协议）。

实现 MCP SSE 传输协议，提供 mock `get-finance-productslist` 工具，
支持基于筛选参数的产品过滤、排序和分页。

启动：
    python mcp_main.py

端点：
    SSE:  GET http://127.0.0.1:30002/sse
    POST: POST http://127.0.0.1:30002/messages/?session_id=<uuid>

协议流程（使用 MCP SDK 的 sse_client + ClientSession）：
  1. Client GET /sse → Server 发送 endpoint 事件（POST URL）
  2. Client POST /messages/?session_id=xxx → JSON-RPC 请求
  3. Server 通过 SSE 流返回 JSON-RPC 响应

环境变量：
    MOCK_MCP_PORT          端口（默认 30002）
    MOCK_MCP_HOST          主机（默认 127.0.0.1）
    MOCK_MCP_FAIL          "true" 模拟 MCP 服务故障
    MOCK_MCP_DELAY_MS      响应延迟毫秒数
    MOCK_MCP_PRODUCT_COUNT 限制返回产品数量（0=不限）
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import time
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse, StreamingResponse

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger("mock_mcp")

_ROOT = Path(__file__).resolve().parent


# ── 配置加载 ─────────────────────────────────────────────────────────────

def load_config() -> dict[str, Any]:
    cfg_path = _ROOT / "config" / "mcp_server.json"
    with cfg_path.open(encoding="utf-8") as f:
        return json.load(f)


CONFIG = load_config()
TOOL_NAME = CONFIG.get("tool_name", "get-finance-productslist")
PROTOCOL_VERSION = CONFIG.get("protocol_version", "2024-11-05")
SERVER_NAME = CONFIG.get("server_name", "mock-mcp")
SERVER_VERSION = CONFIG.get("server_version", "1.0.0")
MOCK_PRODUCTS: List[dict[str, Any]] = CONFIG.get("products", [])

# prodType 中文 → filterProdType 数字映射
_PROD_TYPE_MAP = {
    "固定收益类": "1",
    "商品及金融衍生品类": "2",
    "混合类": "3",
    "传统产品": "4",
    "结构性存款": "5",
}


# ── Session 管理 ──────────────────────────────────────────────────────────

class SessionRegistry:
    """管理 SSE 会话：session_id → asyncio.Queue。

    Queue 的创建延迟到 async generator 内部，确保绑定正确的 event loop。
    """

    def __init__(self) -> None:
        self._sessions: Dict[str, asyncio.Queue] = {}

    def create_id(self) -> str:
        session_id = uuid.uuid4().hex
        self._sessions[session_id] = None  # 占位，Queue 延迟创建
        logger.info("session id created: %s", session_id)
        return session_id

    def init_queue(self, session_id: str) -> asyncio.Queue:
        """在 async 上下文中创建 Queue，绑定当前 event loop。"""
        q = asyncio.Queue()
        self._sessions[session_id] = q
        logger.info("session queue initialized: %s", session_id)
        return q

    def get_queue(self, session_id: str) -> Optional[asyncio.Queue]:
        return self._sessions.get(session_id)

    def remove(self, session_id: str) -> None:
        self._sessions.pop(session_id, None)
        logger.info("session removed: %s", session_id)

    @property
    def active_count(self) -> int:
        return sum(1 for v in self._sessions.values() if v is not None)


REGISTRY = SessionRegistry()


# ── Mock 产品过滤 ──────────────────────────────────────────────────────────

def filter_products(
    products: List[dict[str, Any]],
    arguments: dict[str, Any],
) -> List[dict[str, Any]]:
    """根据 MCP 工具调用参数过滤产品列表。

    支持的筛选参数（与 DEFAULT_MCP_PARAMS 对齐）：
      - filterRiskLevel: "0"=不限, "1"-"5"=R1-R5
      - filterProdType:  "0"=不限, "1"-"5" 对应产品类型
      - pageMark:        "20"=全部, "21"=活钱管理, "22"=低波稳健, "23"=收益进阶
      - filterStatus:    "0"=不限, "1"=在售
      - beginIndex:      起始位置（分页）
    """
    result = list(products)

    # filterRiskLevel
    risk = str(arguments.get("filterRiskLevel", "0"))
    if risk and risk != "0":
        result = [p for p in result if str(p.get("riskLevel")) == risk]

    # filterProdType
    prod_type = str(arguments.get("filterProdType", "0"))
    if prod_type and prod_type != "0":
        result = [
            p for p in result
            if _PROD_TYPE_MAP.get(p.get("prodType", ""), "") == prod_type
        ]

    # pageMark（产品分类）
    page_mark = str(arguments.get("pageMark", "20"))
    if page_mark and page_mark != "20":
        result = [p for p in result if str(p.get("pageMark", "20")) == page_mark]
        if page_mark == "21":
            # 活钱管理：R1 + 固定收益类
            result = [
                p for p in result
                if p.get("riskLevel") == "1" and p.get("prodType") == "固定收益类"
            ]
        elif page_mark == "22":
            # 低波稳健：R1-R2
            result = [p for p in result if str(p.get("riskLevel")) in ("1", "2")]
        elif page_mark == "23":
            # 收益进阶：R2-R3
            result = [p for p in result if str(p.get("riskLevel")) in ("2", "3")]

    # filterStatus（在售状态）
    status = str(arguments.get("filterStatus", "0"))
    if status == "1":
        # mock 产品全部视为在售
        pass

    # beginIndex（分页起始位置）
    begin = arguments.get("beginIndex", "0")
    try:
        begin_idx = int(begin)
        if begin_idx > 0:
            result = result[begin_idx:]
    except (ValueError, TypeError):
        pass

    # sortType
    sort_type = str(arguments.get("sortType", "0"))
    if sort_type and sort_type != "0":
        def _yield_key(p: dict) -> float:
            raw = str(p.get("profitValue", "0%")).replace("%", "")
            try:
                return float(raw)
            except ValueError:
                return 0.0
        if sort_type in ("1", "2", "3", "4", "5", "6"):
            # 按收益率降序
            result.sort(key=_yield_key, reverse=True)

    # 限制返回数量
    max_count = int(os.environ.get("MOCK_MCP_PRODUCT_COUNT", "0"))
    if max_count > 0 and len(result) > max_count:
        result = result[:max_count]

    return result


def build_tool_response(arguments: dict[str, Any]) -> dict[str, Any]:
    """构造 get-finance-productslist 工具的响应 payload。

    返回格式与 mcp_sse_client._parse_payload 兼容：
      {
        "status": 200,
        "code": "200",
        "opData": {"prodList": [...]}
      }
    """
    # 故障模拟
    if os.environ.get("MOCK_MCP_FAIL", "").lower() == "true":
        return {
            "status": 500,
            "code": "500",
            "message": "MOCK_MCP_FAIL=true：模拟 MCP 服务故障",
        }

    products = filter_products(MOCK_PRODUCTS, arguments)
    return {
        "status": 200,
        "code": "200",
        "opData": {"prodList": products},
    }


# ── JSON-RPC 处理 ──────────────────────────────────────────────────────────

def handle_jsonrpc(message: dict[str, Any]) -> Optional[dict[str, Any]]:
    """处理 JSON-RPC 2.0 请求，返回响应 dict（None 表示通知无需响应）。"""
    method = message.get("method", "")
    msg_id = message.get("id")
    params = message.get("params", {})

    # ── initialize ──
    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "result": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            },
        }

    # ── notifications/initialized（通知，无需响应）──
    if method == "notifications/initialized":
        logger.info("received notifications/initialized")
        return None

    # ── tools/list ──
    if method == "tools/list":
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "result": {
                "tools": [
                    {
                        "name": TOOL_NAME,
                        "description": "获取理财产品列表（支持风险等级、产品类型等筛选）",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "filterRiskLevel": {
                                    "type": "string",
                                    "description": "风险等级: 0=不限, 1-5=R1-R5",
                                },
                                "filterProdType": {
                                    "type": "string",
                                    "description": "产品类型: 0=不限, 1=固定收益类, 3=混合类, 等",
                                },
                                "pageMark": {
                                    "type": "string",
                                    "description": "20=全部, 21=活钱管理, 22=低波稳健, 23=收益进阶",
                                },
                                "sortType": {
                                    "type": "string",
                                    "description": "排序: 0=默认, 1-6=按年化收益降序",
                                },
                                "beginIndex": {
                                    "type": "string",
                                    "description": "分页起始位置",
                                },
                            },
                        },
                    }
                ]
            },
        }

    # ── tools/call ──
    if method == "tools/call":
        tool_name = params.get("name", "")
        if tool_name != TOOL_NAME:
            return {
                "jsonrpc": "2.0",
                "id": msg_id,
                "error": {
                    "code": -32602,
                    "message": f"未知工具: {tool_name}（本服务仅支持 {TOOL_NAME}）",
                },
            }

        arguments = params.get("arguments", {})
        if not isinstance(arguments, dict):
            arguments = {}

        # 延迟模拟
        delay_ms = int(os.environ.get("MOCK_MCP_DELAY_MS", "0"))
        if delay_ms > 0:
            time.sleep(delay_ms / 1000.0)

        payload = build_tool_response(arguments)
        payload_text = json.dumps(payload, ensure_ascii=False)

        product_count = 0
        op_data = payload.get("opData")
        if isinstance(op_data, dict):
            prod_list = op_data.get("prodList", [])
            product_count = len(prod_list) if isinstance(prod_list, list) else 0

        logger.info(
            "tools/call: tool=%s, filterRiskLevel=%s, filterProdType=%s, pageMark=%s, "
            "products=%d, status=%s",
            tool_name,
            arguments.get("filterRiskLevel", "0"),
            arguments.get("filterProdType", "0"),
            arguments.get("pageMark", "20"),
            product_count,
            payload.get("status"),
        )

        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "result": {
                "content": [
                    {"type": "text", "text": payload_text}
                ]
            },
        }

    # ── 未知方法 ──
    logger.warning("unknown method: %s", method)
    return {
        "jsonrpc": "2.0",
        "id": msg_id,
        "error": {"code": -32601, "message": f"method not found: {method}"},
    }


# ── SSE 格式化 ─────────────────────────────────────────────────────────────

def _sse_event(event: str, data: str) -> str:
    """格式化 SSE 事件帧。"""
    return f"event: {event}\ndata: {data}\n\n"


# ── FastAPI ────────────────────────────────────────────────────────────────

app = FastAPI(
    title="Mock MCP Server",
    description="MCP SSE 协议 Mock，提供 get-finance-productslist 工具",
    version=SERVER_VERSION,
)


@app.get("/health")
async def health() -> dict[str, Any]:
    """健康检查。"""
    return {
        "status": "healthy",
        "service": "mock_mcp",
        "version": SERVER_VERSION,
        "protocol_version": PROTOCOL_VERSION,
        "tool_name": TOOL_NAME,
        "product_count": len(MOCK_PRODUCTS),
        "active_sessions": REGISTRY.active_count,
        "config": {
            "fail_mode": os.environ.get("MOCK_MCP_FAIL", "false"),
            "delay_ms": int(os.environ.get("MOCK_MCP_DELAY_MS", "0")),
            "max_product_count": int(os.environ.get("MOCK_MCP_PRODUCT_COUNT", "0")),
        },
        "timestamp": datetime.now().isoformat(),
    }


@app.get("/sse")
async def sse_endpoint():
    """MCP SSE 传输端点。

    1. 创建会话 ID，在 async 上下文中初始化 Queue
    2. 发送 endpoint 事件（POST URL）
    3. 保持连接，从队列读取 JSON-RPC 响应并推送
    """
    session_id = REGISTRY.create_id()
    post_url = f"/messages/?session_id={session_id}"

    async def event_stream():
        # 在 async 上下文中创建 Queue，确保绑定正确的 event loop
        queue = REGISTRY.init_queue(session_id)
        try:
            # 发送 endpoint 事件
            yield _sse_event("endpoint", post_url)
            logger.info("SSE endpoint sent: session_id=%s", session_id)

            # 从队列读取响应并推送
            while True:
                response = await queue.get()
                if response is None:
                    break
                yield _sse_event("message", json.dumps(response, ensure_ascii=False))
        except Exception as exc:
            logger.error("SSE stream error: session_id=%s, err=%s", session_id, exc)
        finally:
            REGISTRY.remove(session_id)
            logger.info("SSE stream closed: session_id=%s", session_id)

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@app.post("/messages/")
async def messages_endpoint(
    request: Request,
    session_id: str = Query(...),
):
    """接收 JSON-RPC 请求，处理后通过 SSE 流返回响应。"""
    queue = REGISTRY.get_queue(session_id)
    if queue is None:
        return JSONResponse(
            status_code=404,
            content={"error": f"session not found or not initialized: {session_id}"},
        )

    try:
        body = await request.json()
    except Exception:
        return JSONResponse(
            status_code=400,
            content={"error": "invalid JSON body"},
        )

    if not isinstance(body, dict):
        return JSONResponse(
            status_code=400,
            content={"error": "body must be a JSON-RPC object"},
        )

    # 处理 JSON-RPC
    response = handle_jsonrpc(body)

    # 通知（无 id）不需要响应
    if response is not None:
        await queue.put(response)
        logger.info(
            "JSON-RPC response queued: session_id=%s, method=%s, id=%s",
            session_id,
            body.get("method", ""),
            body.get("id"),
        )

    return JSONResponse(status_code=202, content={"status": "accepted"})


@app.get("/admin/reload")
async def reload_config() -> JSONResponse:
    """热重载配置。"""
    global CONFIG, TOOL_NAME, MOCK_PRODUCTS
    CONFIG = load_config()
    TOOL_NAME = CONFIG.get("tool_name", "get-finance-productslist")
    MOCK_PRODUCTS = CONFIG.get("products", [])
    return JSONResponse({
        "status": "ok",
        "tool_name": TOOL_NAME,
        "product_count": len(MOCK_PRODUCTS),
    })


@app.get("/products")
async def list_products() -> JSONResponse:
    """列出所有 mock 产品（调试用）。"""
    return JSONResponse({
        "total": len(MOCK_PRODUCTS),
        "products": MOCK_PRODUCTS,
    })


# ── 启动 ───────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn

    port = int(os.environ.get("MOCK_MCP_PORT", CONFIG.get("port", 30002)))
    host = os.environ.get("MOCK_MCP_HOST", CONFIG.get("host", "127.0.0.1"))

    print("=" * 60)
    print("Mock MCP Server (mcp_main.py) 启动中...")
    print(f"服务地址: http://{host}:{port}")
    print(f"SSE 端点: http://{host}:{port}/sse")
    print(f"POST 端点: http://{host}:{port}/messages/")
    print(f"健康检查: http://{host}:{port}/health")
    print(f"产品列表: http://{host}:{port}/products")
    print(f"热加载: GET http://{host}:{port}/admin/reload")
    print(f"工具名: {TOOL_NAME}")
    print(f"协议版本: {PROTOCOL_VERSION}")
    print(f"产品数量: {len(MOCK_PRODUCTS)}")
    if os.environ.get("MOCK_MCP_FAIL", "").lower() == "true":
        print("⚠ 故障模式已启用 (MOCK_MCP_FAIL=true)")
    print("=" * 60)

    uvicorn.run(app, host=host, port=port, log_level="info")
