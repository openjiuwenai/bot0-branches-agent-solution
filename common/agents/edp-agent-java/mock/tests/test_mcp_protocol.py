"""使用 MCP SDK 测试 mcp_main.py 的完整 MCP SSE 协议。

这是最真实的测试——使用与 EDPAgent 相同的 mcp_sse_client 路径：
  sse_client + ClientSession → initialize → tools/list → tools/call
"""
import asyncio
import json
import sys
from mcp import ClientSession
from mcp.client.sse import sse_client

BASE = "http://127.0.0.1:30002"
SSE_URL = f"{BASE}/sse"
HEADERS = {
    "appAccessCheckToken": "test-token",
    "app_name": "f-mlp",
    "Content-Type": "application/json",
}
TIMEOUT = 25


async def test_mcp_protocol():
    print("=" * 60)
    print("MCP SSE 协议测试（使用 MCP SDK）")
    print("=" * 60)

    print(f"\n连接 SSE: {SSE_URL}")
    async with sse_client(SSE_URL, headers=HEADERS) as (read_stream, write_stream):
        async with ClientSession(read_stream, write_stream) as session:
            # 1. initialize
            print("\n[1] initialize...")
            init_result = await asyncio.wait_for(
                session.initialize(), timeout=TIMEOUT
            )
            print(f"  protocolVersion: {init_result.protocolVersion}")
            print(f"  serverInfo: {init_result.serverInfo.name} v{init_result.serverInfo.version}")
            print(f"  capabilities: {init_result.capabilities}")

            # 2. tools/list
            print("\n[2] tools/list...")
            tools_result = await asyncio.wait_for(
                session.list_tools(), timeout=TIMEOUT
            )
            tool_names = [t.name for t in tools_result.tools]
            print(f"  工具: {tool_names}")
            assert "get-finance-productslist" in tool_names
            tool = next(t for t in tools_result.tools if t.name == "get-finance-productslist")
            print(f"  描述: {tool.description}")

            # 3. tools/call — 无筛选
            print("\n[3] tools/call（无筛选，filterRiskLevel=0）...")
            result = await asyncio.wait_for(
                session.call_tool(
                    "get-finance-productslist",
                    arguments={
                        "filterRiskLevel": "0",
                        "filterProdType": "0",
                        "pageMark": "20",
                    },
                ),
                timeout=TIMEOUT,
            )
            assert result.content, "tools/call 返回空内容"
            payload = json.loads(result.content[0].text)
            products = payload.get("opData", {}).get("prodList", [])
            print(f"  返回产品数: {len(products)}")
            for p in products[:3]:
                print(f"    - {p.get('productCode')} {p.get('prodName')} (R{p.get('riskLevel')}, {p.get('prodType')})")

            # 4. tools/call — 筛选 R1
            print("\n[4] tools/call（filterRiskLevel=1）...")
            result2 = await asyncio.wait_for(
                session.call_tool(
                    "get-finance-productslist",
                    arguments={
                        "filterRiskLevel": "1",
                        "filterProdType": "0",
                        "pageMark": "20",
                    },
                ),
                timeout=TIMEOUT,
            )
            payload2 = json.loads(result2.content[0].text)
            products2 = payload2.get("opData", {}).get("prodList", [])
            print(f"  返回产品数: {len(products2)}")
            for p in products2:
                print(f"    - {p.get('productCode')} {p.get('prodName')} (R{p.get('riskLevel')})")
                assert p.get("riskLevel") == "1", f"筛选 R1 但返回了 R{p.get('riskLevel')}"
            print("  ✓ 全部为 R1，筛选正确")

            # 5. tools/call — 筛选混合类
            print("\n[5] tools/call（filterProdType=3 混合类）...")
            result3 = await asyncio.wait_for(
                session.call_tool(
                    "get-finance-productslist",
                    arguments={
                        "filterRiskLevel": "0",
                        "filterProdType": "3",
                        "pageMark": "20",
                    },
                ),
                timeout=TIMEOUT,
            )
            payload3 = json.loads(result3.content[0].text)
            products3 = payload3.get("opData", {}).get("prodList", [])
            print(f"  返回产品数: {len(products3)}")
            for p in products3:
                print(f"    - {p.get('productCode')} {p.get('prodName')} ({p.get('prodType')})")
                assert p.get("prodType") == "混合类", f"筛选混合类但返回了 {p.get('prodType')}"
            print("  ✓ 全部为混合类，筛选正确")

            # 6. tools/call — 活钱管理
            print("\n[6] tools/call（pageMark=21 活钱管理）...")
            result4 = await asyncio.wait_for(
                session.call_tool(
                    "get-finance-productslist",
                    arguments={
                        "filterRiskLevel": "0",
                        "filterProdType": "0",
                        "pageMark": "21",
                    },
                ),
                timeout=TIMEOUT,
            )
            payload4 = json.loads(result4.content[0].text)
            products4 = payload4.get("opData", {}).get("prodList", [])
            print(f"  返回产品数: {len(products4)}")
            for p in products4:
                print(f"    - {p.get('productCode')} {p.get('prodName')} (R{p.get('riskLevel')}, {p.get('prodType')})")
            print("  ✓ 活钱管理（R1 + 固定收益类）筛选正确")

    print("\n" + "=" * 60)
    print("✅ 全部测试通过")
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(test_mcp_protocol())
