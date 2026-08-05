"""Integration smoke tests for versatile_main streaming."""
from __future__ import annotations

import asyncio
import json
import sys
import unittest
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from engine.loader import WorkflowStore
from engine.matcher import WorkflowMatcher
from engine.streamer import stream_workflow


def _unwrap_sse_envelope(obj: dict) -> dict:
    if obj.get("event") and isinstance(obj.get("data"), dict):
        return obj["data"]
    return obj


async def collect_sse(workflow_id: str, query: str, conversation_id: str = "test-conv") -> list[dict]:
    store = WorkflowStore()
    matcher = WorkflowMatcher(store)
    inputs = {"query": query}
    ctx = {
        "inputs": inputs,
        "query": query,
        "conversation_id": conversation_id,
        "menu_type": "",
        "menu_confirm": None,
        "config": store.server_config,
    }
    wf = matcher.resolve(inputs)
    assert wf["id"] == workflow_id, f"expected {workflow_id}, got {wf['id']}"

    chunks: list[dict] = []
    async for line in stream_workflow(wf, ctx, store.server_config):
        if line.startswith("data: "):
            chunks.append(json.loads(line[6:].strip()))
    return chunks


async def collect_sse_data(workflow_id: str, query: str, conversation_id: str = "test-conv") -> list[dict]:
    envelopes = await collect_sse(workflow_id, query, conversation_id)
    return [_unwrap_sse_envelope(item) for item in envelopes]


class StreamSmokeTests(unittest.TestCase):
    def test_wealth_recommend_has_qa_product_list(self) -> None:
        envelopes = asyncio.run(collect_sse("wealth_recommend", "帮我推荐几款稳健型理财产品"))
        frames = [_unwrap_sse_envelope(item) for item in envelopes]
        qa_frames = [f for f in frames if f.get("node_type") == "QA"]
        self.assertTrue(qa_frames)
        display_frame = next(f for f in qa_frames if f.get("node_name") == "问答-产品列表展示")
        payload = json.loads(display_frame["text"])
        self.assertIn("responseData", payload)
        prod_list = payload["responseData"][1]["pageData"]["showData"]["prodList"]
        self.assertEqual(len(prod_list), 3)

        gxz_frame = next(f for f in qa_frames if f.get("node_name") == "GXZQAResponseNode")
        gxz_payload = json.loads(gxz_frame["text"])
        self.assertIn("productList", gxz_payload)
        self.assertIn("bankCardNumber", gxz_payload)
        self.assertIsInstance(gxz_payload["productList"], list)

    def test_wealth_recommend_ends_with_event_end(self) -> None:
        envelopes = asyncio.run(collect_sse("wealth_recommend", "帮我推荐理财产品"))
        frames = [_unwrap_sse_envelope(item) for item in envelopes]
        self.assertEqual(frames[-2].get("node_type"), "End")
        self.assertEqual(envelopes[-1].get("event"), "end")

    def test_balance_query_qa_result_node_for_adapter(self) -> None:
        frames = asyncio.run(collect_sse_data("balance_query", "查询尾号为6605的卡的余额"))
        qa_frames = [f for f in frames if f.get("node_type") == "QA"]
        self.assertTrue(qa_frames, "balance query must emit QA result frame")
        self.assertEqual(qa_frames[-1].get("node_name"), "GXZQAResponseNode")
        payload = json.loads(qa_frames[-1]["summary"])
        self.assertIn("bankCardBalanceList", payload)
        self.assertEqual(payload.get("queryStatus"), "成功")
        balance_item = payload["bankCardBalanceList"][0]
        self.assertEqual(
            balance_item["currencyBalanceList"][0]["currencyCode"],
            "001",
        )
        self.assertEqual(frames[-2].get("node_type"), "End")
        envelopes = asyncio.run(collect_sse("balance_query", "查询尾号为6605的卡的余额"))
        self.assertEqual(envelopes[-1].get("event"), "end")

    def test_transfer_round1_final_output(self) -> None:
        envelopes = asyncio.run(collect_sse("transfer_round1", "转账1000元"))
        frames = [_unwrap_sse_envelope(item) for item in envelopes]
        gxz_frames = [f for f in frames if f.get("node_name") == "GXZQAResponseNode"]
        self.assertEqual(len(gxz_frames), 2)
        payload = json.loads(gxz_frames[-1]["summary"])
        self.assertEqual(payload.get("transferStatus"), "success")
        self.assertIn("payerCardNumber", payload)
        self.assertIn("payeeCardNumber", payload)
        self.assertIn("transferAmount", payload)
        self.assertEqual(frames[-2].get("node_type"), "End")
        self.assertEqual(envelopes[-1].get("event"), "end")

    def test_product_buy_final_output(self) -> None:
        query = "理财产品：产品名称：测试，产品代码：XLT1801，金额：300元"
        envelopes = asyncio.run(collect_sse("product_buy", query))
        frames = [_unwrap_sse_envelope(item) for item in envelopes]
        questioner = next(f for f in frames if f.get("node_type") == "Questioner")
        self.assertEqual(questioner.get("node_name"), "提问器-理财摸高购买")
        gxz_frames = [f for f in frames if f.get("node_name") == "GXZQAResponseNode"]
        payload = json.loads(gxz_frames[-1]["summary"])
        self.assertEqual(payload["productBuyResponse"]["buyStatus"], "1")
        self.assertEqual(frames[-2].get("node_type"), "End")
        self.assertEqual(envelopes[-1].get("event"), "end")


if __name__ == "__main__":
    unittest.main()
