"""Stateful and dynamic response hooks migrated from mock_workflow_server_v6."""
from __future__ import annotations

import ast
import json
import logging
import os
import re
from datetime import datetime
from typing import Any, Optional

logger = logging.getLogger("mock_versatile.hooks")

_transfer_counters: dict[str, int] = {}
_balance_states: dict[str, dict[str, Any]] = {}
_transfer_detail_cache: dict[str, dict[str, Any]] = {}

WEALTH_WORKFLOW_ID = "b2c3d4e5-f6a7-8901-bcde-f23456789012"
BALANCE_WORKFLOW_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
TRANSFER_WORKFLOW_ID = "45c08bf2-b591-44e2-9d7c-57dd0bd8f760"
WEALTH_FIN_CARD_LAST_NO = "6605"

MMI_BASE_PAYLOAD: dict[str, Any] = {
    "SPTRANSRETCODE": "00009",
    "SSTANDARDQUESTION": "",
    "SSTANDARDANSWER": "",
    "SPDISPLAYINFOL": "",
    "CURSOR": [],
    "SNEWVERSIONANSWER": "{}",
    "TRANSID": "",
    "SISENDNODE": "1",
    "INTERRUPTABLE": "false",
    "STASKID": "",
    "STASKNAME": "",
    "SSTANDARDQUESTIONKEY": "",
    "INSTRUCTIONTYPE": "1",
    "CONTENTTYPE": "0",
    "RECOMMENDSCRIPT": "{}",
    "LLM_otherfield": "LLM_sessionid=123456789&intent=LATEST&LLMAgentVersion=0",
}


def _merge_mmi(overrides: dict[str, Any]) -> dict[str, Any]:
    return {**MMI_BASE_PAYLOAD, **overrides}


def _sse_message_frame(
    workflow_id: str,
    node_id: str,
    node_type: str,
    node_name: str,
    **extra: Any,
) -> dict[str, Any]:
    frame: dict[str, Any] = {
        "_event": "message",
        "index": "0",
        "node_id": node_id,
        "node_type": node_type,
        "node_name": node_name,
        "workflow_id": workflow_id,
    }
    frame.update(extra)
    return frame


def _sse_query_end_frame(workflow_id: str) -> dict[str, Any]:
    return _sse_message_frame(
        workflow_id,
        "node_end",
        "End",
        "结束",
        text="",
        summary="",
        is_finished=True,
    )


def _sse_event_end_frame() -> dict[str, Any]:
    return {"_event": "end"}


def _json_text(payload: dict[str, Any]) -> str:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def _build_card_number(tail: str) -> str:
    prefix = "622202"
    padded_tail = tail[-4:].zfill(4)
    middle_len = max(0, 19 - len(prefix) - len(padded_tail))
    return prefix + ("0" * middle_len) + padded_tail

# 与 AgentEnvExplorer scenarios/datasets/products/wealth_products.yaml 保持一致
WEALTH_PRODUCTS_FULL: list[dict[str, Any]] = [
    {
        "prodCode": "DXXJ1339",
        "prodName": "339现管DXXJ-1339",
        "prodType": "固定收益类",
        "profitValue": "3.25%",
        "riskLevel": "1",
    },
    {
        "prodCode": "BGA0088",
        "prodName": "招银日盈BGA-0088",
        "prodType": "浮动收益类",
        "profitValue": "4.10%",
        "riskLevel": "2",
    },
    {
        "prodCode": "RJZ2016",
        "prodName": "聚宝盆RJZ-2016",
        "prodType": "浮动收益类",
        "profitValue": "5.20%",
        "riskLevel": "2",
    },
]


def _wealth_products_brief() -> list[dict[str, str]]:
    return [
        {
            "productCode": p["prodCode"],
            "productName": p["prodName"],
            "productType": p["prodType"],
            "profitValue": p["profitValue"],
            "riskLevel": p["riskLevel"],
        }
        for p in WEALTH_PRODUCTS_FULL
    ]


MOCK_PRODUCT_FILTER: dict[str, Any] = {
    "bankCardNumber": WEALTH_FIN_CARD_LAST_NO,
    "productList": _wealth_products_brief(),
}


def get_initial_balances() -> tuple[float, float]:
    licai = float(os.environ.get("MOCK_LICAI_BALANCE", "1000.0"))
    chuxu = float(os.environ.get("MOCK_CHUXU_BALANCE", "125680.5"))
    return licai, chuxu


def get_or_create_balance_state(
    session_key: str,
    licai_tail: str = "6605",
    chuxu_tail: str = "3344",
) -> dict[str, Any]:
    same_card_mode = os.environ.get("MOCK_SAME_CARD_MODE", "false").lower() == "true"
    if same_card_mode:
        chuxu_tail = licai_tail

    if session_key not in _balance_states:
        licai_balance, chuxu_balance = get_initial_balances()
        _balance_states[session_key] = {
            "licai_balance": licai_balance,
            "chuxu_balance": chuxu_balance,
            "licai_tail": licai_tail,
            "chuxu_tail": chuxu_tail,
        }
    return _balance_states[session_key]


def update_balance_after_transfer(session_key: str, transfer_amount: float) -> None:
    if session_key not in _balance_states:
        return
    state = _balance_states[session_key]
    state["chuxu_balance"] = max(0, state["chuxu_balance"] - transfer_amount)
    state["licai_balance"] = state["licai_balance"] + transfer_amount


def get_transfer_amount_for_session(
    session_key: str,
    requested_amount: float,
) -> tuple[float, bool, str]:
    amounts_config = os.environ.get("MOCK_TRANSFER_AMOUNTS", "")
    transfer_mode = os.environ.get("MOCK_TRANSFER_MODE", "cycle").lower()

    if not amounts_config:
        return requested_amount, True, ""

    amounts = [float(x.strip()) for x in amounts_config.split(",") if x.strip()]
    if not amounts:
        return requested_amount, True, ""

    counter = _transfer_counters.get(session_key, 0)

    if counter >= len(amounts):
        if transfer_mode == "cycle":
            idx = counter % len(amounts)
        elif transfer_mode == "last":
            idx = len(amounts) - 1
        elif transfer_mode == "full":
            _transfer_counters[session_key] = counter + 1
            return requested_amount, True, ""
        elif transfer_mode == "fail":
            _transfer_counters[session_key] = counter + 1
            return 0.0, False, "转账次数超限"
        else:
            idx = len(amounts) - 1
    else:
        idx = counter

    actual_amount = amounts[idx]
    _transfer_counters[session_key] = counter + 1
    return actual_amount, True, ""


def reset_transfer_counter(session_key: str | None = None) -> None:
    if session_key:
        _transfer_counters.pop(session_key, None)
        _balance_states.pop(session_key, None)
        _transfer_detail_cache.pop(session_key, None)
    else:
        _transfer_counters.clear()
        _balance_states.clear()
        _transfer_detail_cache.clear()


def _mock_cny_balance_with_thousands(amount: float) -> str:
    return f"{amount:,.2f}"


def _build_mock_fund_products() -> list[dict]:
    products: list[dict] = []
    risk_levels = ["R1", "R2", "R3", "R4", "R5"]
    holding_periods = [3, 12, 30]
    min_amounts = [1000, 10000, 100000]
    type_by_risk = {
        "R1": "货币型",
        "R2": "债券型",
        "R3": "混合型",
        "R4": "股票型",
        "R5": "QDII",
    }
    base_yield_by_risk = {"R1": 2.1, "R2": 3.2, "R3": 5.2, "R4": 8.6, "R5": 12.0}
    idx = 1
    for rl in risk_levels:
        for hp in holding_periods:
            for ma in min_amounts:
                code = f"FUND{idx:03d}"
                products.append(
                    {
                        "productCode": code,
                        "productName": f"基金推荐产品-{rl}-{hp}M-{ma}元({code})",
                        "productType": type_by_risk[rl],
                        "profitValue": (
                            f"{(base_yield_by_risk[rl] + (hp / 100) + (0.05 if ma == 1000 else (0.15 if ma == 10000 else 0.25))):.2f}%"
                        ),
                        "riskLevel": rl,
                        "holdingPeriodMonths": hp,
                        "minAmount": ma,
                    }
                )
                idx += 1
    return products


MOCK_FUND_PRODUCTS = _build_mock_fund_products()


def _parse_fund_constraints_from_query(query: str) -> dict[str, Any]:
    q = (query or "").strip()
    risk = None
    m = re.search(r"R([1-5])(?![0-9])", q, flags=re.IGNORECASE)
    if m:
        risk = f"R{m.group(1)}"

    term = None
    if "短期" in q:
        term = "短期"
    elif "中期" in q:
        term = "中期"
    elif "长期" in q:
        term = "长期"

    amount = None
    m2 = re.search(r"(\d+)\s*元", q)
    if m2:
        try:
            amount = int(m2.group(1))
        except Exception:
            amount = None

    return {"risk_level": risk, "invest_term": term, "invest_amount": amount}


def _filter_funds_for_query(query: str) -> list[dict]:
    c = _parse_fund_constraints_from_query(query)
    risk = c.get("risk_level")
    term = c.get("invest_term")
    amount = c.get("invest_amount")

    allowed_holding: Optional[set[int]] = None
    if term == "短期":
        allowed_holding = {3}
    elif term == "中期":
        allowed_holding = {12}
    elif term == "长期":
        allowed_holding = {30}

    filtered: list[dict] = []
    for p in MOCK_FUND_PRODUCTS:
        if risk and p.get("riskLevel") != risk:
            continue
        if allowed_holding and p.get("holdingPeriodMonths") not in allowed_holding:
            continue
        if isinstance(amount, int):
            ma = p.get("minAmount")
            if isinstance(ma, int) and ma > amount:
                continue
        filtered.append(p)
    return filtered


def wealth_product_filter_json(ctx: dict[str, Any]) -> str:
    return json.dumps(MOCK_PRODUCT_FILTER, ensure_ascii=False, separators=(",", ":"))


def _wealth_rec_mmi_payload() -> dict[str, Any]:
    """对齐 AgentEnvExplorer winvest_rec_05_qa.yaml 的 MMI payload。"""
    return _merge_mmi({
        "SPTRANSRETCODE": "LLMU0002",
        "TRANSID": "remit_confirm_sign",
        "STASKID": "WAPB_remitAI_easy",
        "STASKNAME": "快速转账",
        "CURRENTNODE": "理财-筛选结果",
        "responseData": [
            {
                "answer": "为您找到以下符合条件的理财产品",
                "readme": "",
                "pageData": {"supportVoice": "true"},
                "type": "1",
            },
            {
                "answer": "产品筛选结果",
                "readme": "",
                "pageData": {
                    "path": "llm_invest_filterresult",
                    "showData": {"prodList": WEALTH_PRODUCTS_FULL},
                    "role": "bot",
                    "appName": "",
                    "needCustomStyle": "true",
                    "url": "finance_mode",
                    "cardStyle": "fixContent",
                },
                "type": "8",
            },
        ],
    })


def _wealth_rec_gxz_payload() -> dict[str, Any]:
    """对齐 AgentEnvExplorer winvest_rec_05_qaz.yaml 的 GXZQAResponseNode payload。"""
    return {
        "bankCardNumber": WEALTH_FIN_CARD_LAST_NO,
        "productList": _wealth_products_brief(),
    }


def _wealth_rec_qa_frame_base(node_name: str, **extra: Any) -> dict[str, Any]:
    return _sse_message_frame(
        WEALTH_WORKFLOW_ID,
        "node_1234567123456",
        "QA",
        node_name,
        **extra,
    )


def wealth_rec_qa_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    payload = _wealth_rec_mmi_payload()
    return _wealth_rec_qa_frame_base(
        "问答-产品列表展示",
        text=json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
    )


def wealth_rec_qa_summary_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    payload = _wealth_rec_mmi_payload()
    return _wealth_rec_qa_frame_base(
        "问答-产品列表展示",
        text="",
        summary=json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        is_finished=True,
    )


def wealth_rec_gxz_qa_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    payload = _wealth_rec_gxz_payload()
    return _wealth_rec_qa_frame_base(
        "GXZQAResponseNode",
        text=json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
    )


def wealth_rec_gxz_summary_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    payload = _wealth_rec_gxz_payload()
    return _wealth_rec_qa_frame_base(
        "GXZQAResponseNode",
        text="",
        summary=json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        is_finished=True,
    )


def wealth_rec_query_end_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    return _sse_query_end_frame(WEALTH_WORKFLOW_ID)


def wealth_rec_event_end_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    return _sse_event_end_frame()


def fund_product_filter_json(ctx: dict[str, Any]) -> str:
    query = str(ctx.get("query", "") or "")
    filtered = _filter_funds_for_query(query)
    payload = {
        "bankCardNumber": "6605",
        "funds": filtered,
        "products": filtered,
        "productList": str(filtered),
    }
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def product_buy_response_json(ctx: dict[str, Any]) -> str:
    query = str(ctx.get("query", "") or "")
    product_code_match = re.search(r"产品代码：([^，,\s]+)", query)
    product_name_match = re.search(r"产品名称：([^，]+)", query)
    amount_match = re.search(r"金额：(\d+(?:\.\d+)?)元", query)

    product_code = product_code_match.group(1) if product_code_match else "25G5793A"
    product_name = (
        product_name_match.group(1)
        if product_name_match
        else "工银理财·核心优选目标止盈策略固收增强封闭产品（25G5793A)"
    )
    buy_amount = (
        f"{float(amount_match.group(1)):.2f}元"
        if amount_match
        else "55550.00元"
    )

    buy_success = os.environ.get("MOCK_PRODUCT_BUY_SUCCESS", "true").lower() == "true"
    buy_status = "1" if buy_success else "购买理财失败"
    fail_cause = "" if buy_success else "余额不足或风控拦截"

    buy_data = {
        "productBuyResponse": {
            "productCode": product_code,
            "productName": product_name,
            "buyAmount": buy_amount,
            "buyStatus": buy_status,
            "failCause": fail_cause,
        }
    }
    return json.dumps(buy_data, ensure_ascii=False, separators=(",", ":"))


def _resolve_balance_card(ctx: dict[str, Any]) -> tuple[str, float]:
    query = str(ctx.get("query", "") or "")
    conversation_id = str(ctx.get("conversation_id", "") or "default")
    tail_match = re.search(r"尾号为?(\d{4})", query)
    requested_tail = tail_match.group(1) if tail_match else ""
    same_card_mode = os.environ.get("MOCK_BALANCE_SAME_CARD", "false").lower() == "true"

    balance_state = get_or_create_balance_state(conversation_id)

    if requested_tail:
        card_tail = requested_tail
        if requested_tail == balance_state.get("licai_tail", "6605"):
            cny_balance = balance_state["licai_balance"]
        elif requested_tail == balance_state.get("chuxu_tail", "3344"):
            cny_balance = balance_state["chuxu_balance"]
        else:
            cny_balance = 50000.0
    else:
        card_tail = balance_state.get("chuxu_tail", "3344")
        cny_balance = balance_state["chuxu_balance"]
        if same_card_mode:
            card_tail = balance_state.get("licai_tail", "6605")
            cny_balance = balance_state["licai_balance"]

    return card_tail, cny_balance


def balance_gxz_payload(ctx: dict[str, Any]) -> dict[str, Any]:
    """对齐 AgentEnvExplorer balance-query-amount bamount_03 的 GXZQAResponseNode payload。"""
    card_tail, cny_balance = _resolve_balance_card(ctx)
    return {
        "bankCardBalanceList": [
            {
                "bankCardNumber": _build_card_number(card_tail),
                "mediumStatus": "0",
                "currencyBalanceList": [
                    {
                        "currencyCode": "001",
                        "currencyName": "人民币可用余额",
                        "balance": f"{cny_balance:.2f}",
                    }
                ],
            }
        ],
        "queryStatus": "成功",
        "failCause": "",
    }


def balance_qa_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    payload = balance_gxz_payload(ctx)
    return _sse_message_frame(
        BALANCE_WORKFLOW_ID,
        "node_1234512345123",
        "QA",
        "GXZQAResponseNode",
        text=_json_text(payload),
    )


def balance_qa_summary_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    payload = balance_gxz_payload(ctx)
    return _sse_message_frame(
        BALANCE_WORKFLOW_ID,
        "node_1234512345123",
        "QA",
        "GXZQAResponseNode",
        text="",
        summary=_json_text(payload),
        is_finished=True,
    )


def balance_query_end_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    return _sse_query_end_frame(BALANCE_WORKFLOW_ID)


def balance_event_end_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    return _sse_event_end_frame()


def _transfer_07_mmi_payload() -> dict[str, Any]:
    return _merge_mmi({
        "SSTANDARDANSWER": "转账信息已处理成功",
        "INSTRUCTIONBODY": "{}",
        "CURRENTNODE": "转账成功-确认流程成功_发送消息给中控",
    })


def _parse_transfer_details(ctx: dict[str, Any]) -> dict[str, Any]:
    query = str(ctx.get("query", "") or "")
    conversation_id = str(ctx.get("conversation_id", "") or "default")

    # 续传时 query 可能为空，从缓存获取首轮的转账描述
    cached = _get_cached_transfer_detail(conversation_id)

    if query and not cached:
        # 首轮：从 query 解析（与 transfer_confirm_menu_frame 逻辑一致）
        amount_match = re.search(r"转账(\d+(?:\.\d+)?)元", query)
        requested_amount = float(amount_match.group(1)) if amount_match else 1000.0

        payer_m = re.search(r"从尾号(\d+)的卡转账", query)
        payee_m = re.search(r"元到尾号为(\d+)的卡", query)
        payer_tail = (payer_m.group(1) if payer_m else "1234")[-4:].zfill(4)
        payee_tail = (payee_m.group(1) if payee_m else "5678")[-4:].zfill(4)
    elif cached:
        # 续传：使用缓存的转账描述
        requested_amount = cached.get("requested_amount", 1000.0)
        payer_tail = cached.get("payer_tail", "1234")
        payee_tail = cached.get("payee_tail", "5678")
    else:
        # 兜底
        requested_amount = 1000.0
        payer_tail = "1234"
        payee_tail = "5678"

    actual, success, fail_cause = get_transfer_amount_for_session(conversation_id, requested_amount)

    if success:
        update_balance_after_transfer(conversation_id, actual)
        transfer_status = "success"
        transfer_amount_str = str(int(actual)) if actual == int(actual) else f"{actual:.2f}".rstrip("0").rstrip(".")
    else:
        transfer_status = "fail"
        transfer_amount_str = "0"

    return {
        "transferStatus": transfer_status,
        "payerCardNumber": _build_card_number(payer_tail),
        "payeeCardNumber": _build_card_number(payee_tail),
        "transferAmount": transfer_amount_str,
        "failureMsg": fail_cause if not success else "",
    }


def _parse_transfer_description_for_confirm(ctx: dict[str, Any]) -> dict[str, Any]:
    """从首轮 query 中解析转账描述信息并缓存到 conversation_id 级别，供续传使用。"""
    query = str(ctx.get("query", "") or "")
    conversation_id = str(ctx.get("conversation_id", "") or "default")

    amount_match = re.search(r"转账(\d+(?:\.\d+)?)元", query)
    requested_amount = float(amount_match.group(1)) if amount_match else 1000.0

    payer_m = re.search(r"从尾号(\d+)的卡转账", query)
    payee_m = re.search(r"元到尾号为(\d+)的卡", query)
    payer_tail = (payer_m.group(1) if payer_m else "1234")[-4:].zfill(4)
    payee_tail = (payee_m.group(1) if payee_m else "5678")[-4:].zfill(4)

    detail = {
        "payer_tail": payer_tail,
        "payee_tail": payee_tail,
        "requested_amount": requested_amount,
    }
    _transfer_detail_cache[conversation_id] = detail
    logger.info("transfer_confirm: cached detail for conversation_id=%s detail=%s", conversation_id, detail)
    return detail


def _get_cached_transfer_detail(conversation_id: str) -> dict[str, Any] | None:
    """获取缓存的转账描述信息（续传时 query 可能为空）。"""
    return _transfer_detail_cache.get(conversation_id)


def transfer_confirm_menu_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    """转账确认提示卡片（首轮），触发 adapter input_required 中断。
    
    使用 node_type=Custom（前端可见）+ menu_type=TRANSFER_MENU（触发确认 UI）。
    不含 End 节点和 GXZQAResponseNode，使 adapter finish() 返回 TYPE_INTERRUPT。
    """
    detail = _parse_transfer_description_for_confirm(ctx)
    payer_tail = detail.get("payer_tail", "1234")
    payee_tail = detail.get("payee_tail", "5678")
    amount = detail.get("requested_amount", 1000.0)
    amount_str = str(int(amount)) if amount == int(amount) else f"{amount:.2f}".rstrip("0").rstrip(".")

    payload = _merge_mmi({
        "CURRENTNODE": "交易验签-弹出确认卡片",
        "TRANSID": "remit_confirm_confirm",
        "INSTRUCTIONTYPE": "1",
        "INTERRUPTABLE": "true",
        "responseData": [
            {
                "type": "8",
                "readme": "paySummaryVerbose",
                "pageData": {
                    "path": "llm_tranconfrim",
                    "showData": {
                        "amount": amount_str,
                        "cardNum_show": f"尾号{payer_tail}",
                        "acctTypeDesc": "储蓄卡",
                        "payeeName_show": f"尾号{payee_tail}",
                        "remitType": "etrans",
                    },
                },
            },
            {
                "type": "1",
                "answer": f"请确认转账信息：从尾号{payer_tail}的卡转账{amount_str}元到尾号{payee_tail}的卡",
            },
        ],
    })

    confirm_text = f"请确认从尾号{payer_tail}的卡转账{amount_str}元到尾号{payee_tail}的卡"

    return _sse_message_frame(
        TRANSFER_WORKFLOW_ID,
        "node_confirm_menu",
        "Custom",
        "交易验签-弹出确认卡片",
        text=confirm_text,
        menu_type="TRANSFER_MENU",
        summary=_json_text(payload),
    )


def transfer_gxz_payload(ctx: dict[str, Any]) -> dict[str, Any]:
    """对齐 AgentEnvExplorer transfer_07_qaz.yaml 的 GXZQAResponseNode payload。"""
    return _parse_transfer_details(ctx)


def transfer_qa_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    payload = _transfer_07_mmi_payload()
    return _sse_message_frame(
        TRANSFER_WORKFLOW_ID,
        "node_1234567891234",
        "QA",
        "转账成功-确认流程成功_发送消息给中控",
        text=_json_text(payload),
    )


def transfer_qa_summary_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    payload = _transfer_07_mmi_payload()
    return _sse_message_frame(
        TRANSFER_WORKFLOW_ID,
        "node_1234567891234",
        "QA",
        "转账成功-确认流程成功_发送消息给中控",
        text=_json_text(payload),
    )


def transfer_gxz_qa_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    payload = transfer_gxz_payload(ctx)
    return _sse_message_frame(
        TRANSFER_WORKFLOW_ID,
        "node_1234567123456",
        "QA",
        "GXZQAResponseNode",
        text=_json_text(payload),
    )


def transfer_gxz_summary_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    payload = transfer_gxz_payload(ctx)
    return _sse_message_frame(
        TRANSFER_WORKFLOW_ID,
        "node_1234567123456",
        "QA",
        "GXZQAResponseNode",
        text="",
        summary=_json_text(payload),
        is_finished=True,
    )


def transfer_query_end_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    return _sse_query_end_frame(TRANSFER_WORKFLOW_ID)


def transfer_event_end_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    return _sse_event_end_frame()


def _product_buy_questioner_payload() -> dict[str, Any]:
    return _merge_mmi({
        "CURRENTNODE": "理财摸高购买场景",
        "responseData": [
            {
                "type": "2",
                "answer": "理财购买",
                "readme": "",
                "pageData": {
                    "menu": {
                        "jumpType": "1",
                        "menuId": "finance_detail",
                        "needLogin": "true",
                        "param": "abcdef",
                    }
                },
            }
        ],
    })


def _product_buy_gxz_payload(ctx: dict[str, Any]) -> dict[str, Any]:
    buy_success = os.environ.get("MOCK_PRODUCT_BUY_SUCCESS", "true").lower() == "true"
    return {
        "productBuyResponse": {
            "failCause": "" if buy_success else "余额不足或风控拦截",
            "buyStatus": "1" if buy_success else "购买理财失败",
        }
    }


def product_buy_questioner_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    payload = _product_buy_questioner_payload()
    return _sse_message_frame(
        WEALTH_WORKFLOW_ID,
        "node_1253512345123",
        "Questioner",
        "提问器-理财摸高购买",
        text=_json_text(payload),
    )


def product_buy_gxz_qa_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    payload = _product_buy_gxz_payload(ctx)
    return _sse_message_frame(
        WEALTH_WORKFLOW_ID,
        "node_1233567123456",
        "QA",
        "GXZQAResponseNode",
        text=_json_text(payload),
    )


def product_buy_gxz_summary_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    payload = _product_buy_gxz_payload(ctx)
    return _sse_message_frame(
        WEALTH_WORKFLOW_ID,
        "node_1233567123456",
        "QA",
        "GXZQAResponseNode",
        text="",
        summary=_json_text(payload),
        is_finished=True,
    )


def product_buy_query_end_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    return _sse_query_end_frame(WEALTH_WORKFLOW_ID)


def product_buy_event_end_frame(ctx: dict[str, Any]) -> dict[str, Any]:
    return _sse_event_end_frame()


def balance_business_result(ctx: dict[str, Any]) -> dict[str, Any]:
    """Legacy balance payload kept for compatibility with older callers."""
    card_tail, cny_balance = _resolve_balance_card(ctx)
    balance_display = _mock_cny_balance_with_thousands(cny_balance)

    return {
        "SPTRANSRETCODE": "LLMU0002",
        "bankCardBalanceList": [
            {
                "bankCardNumber": f"6222****{card_tail}",
                "queryStatus": "成功",
                "currencyBalanceList": [
                    {"currencyCode": "CNY", "balance": balance_display}
                ],
            }
        ],
        "responseData": [
            {
                "type": "1",
                "answer": "已为您查询账户余额",
                "readme": "已为您查询账户余额",
                "pageData": "",
            },
            {
                "type": "7",
                "answer": "",
                "readme": "",
                "pageData": {
                    "id": "queryBalance",
                    "bankBalanceData": [
                        {
                            "layouttype": "1",
                            "actionFun_click": {
                                "menu": {
                                    "param": "returnFlag=3",
                                    "needLogin": "false",
                                    "menuId": "account_1",
                                }
                            },
                            "actionType_click": "menu",
                            "balanceList": [
                                {
                                    "balanceTitle": {
                                        "titleValueColor": "C3B9A1",
                                        "titleValue": "人民币余额",
                                        "type": "text",
                                        "actionFun_click": "",
                                        "actionType_click": "",
                                    },
                                    "balance": {
                                        "titleValueColor": "F4E1B3",
                                        "titleValue": balance_display,
                                        "type": "text",
                                        "actionFun_click": "",
                                        "actionType_click": "",
                                    },
                                },
                                {
                                    "balanceTitle": {
                                        "titleValueColor": "C3B9A1",
                                        "titleValue": "人民币可用余额",
                                        "type": "text",
                                        "actionFun_click": "",
                                        "actionType_click": "",
                                    },
                                    "balance": {
                                        "titleValueColor": "F4E1B3",
                                        "titleValue": balance_display,
                                        "type": "text",
                                        "actionFun_click": "",
                                        "actionType_click": "",
                                    },
                                },
                            ],
                        }
                    ],
                },
            },
        ],
    }


def balance_business_result_json(ctx: dict[str, Any]) -> str:
    """v6 balance payload as QA result-node text (8191 adapter GXZQAResponseNode contract)."""
    return json.dumps(balance_business_result(ctx), ensure_ascii=False, separators=(",", ":"))


def balance_simple_qa_json(ctx: dict[str, Any]) -> str:
    """Interrupt-style simplified balance QA."""
    conversation_id = str(ctx.get("conversation_id", "") or "default")
    balance_state = get_or_create_balance_state(conversation_id)
    return json.dumps(
        {
            "status": "success",
            "card_no": balance_state.get("chuxu_tail", "3344"),
            "balance": balance_state["chuxu_balance"],
        },
        ensure_ascii=False,
    )


def transfer_response_json(ctx: dict[str, Any]) -> str:
    return _json_text(transfer_gxz_payload(ctx))


def transfer_confirmed_simple_json(ctx: dict[str, Any]) -> str:
    return _json_text(transfer_gxz_payload(ctx))


def default_error_qa_json(ctx: dict[str, Any]) -> str:
    query = str(ctx.get("query", "") or "")
    return json.dumps(
        {"error": "未知工作流类型", "query": query},
        ensure_ascii=False,
        separators=(",", ":"),
    )


def default_answer_qa_json(ctx: dict[str, Any]) -> str:
    query = str(ctx.get("query", "") or "")
    return json.dumps(
        {"status": "success", "answer": f"已为您完成处理（mock 兜底）：{query}"},
        ensure_ascii=False,
    )


HOOK_REGISTRY: dict[str, Any] = {
    "wealth_rec_qa_frame": wealth_rec_qa_frame,
    "wealth_rec_qa_summary_frame": wealth_rec_qa_summary_frame,
    "wealth_rec_gxz_qa_frame": wealth_rec_gxz_qa_frame,
    "wealth_rec_gxz_summary_frame": wealth_rec_gxz_summary_frame,
    "wealth_rec_query_end_frame": wealth_rec_query_end_frame,
    "wealth_rec_event_end_frame": wealth_rec_event_end_frame,
    "balance_qa_frame": balance_qa_frame,
    "balance_qa_summary_frame": balance_qa_summary_frame,
    "balance_query_end_frame": balance_query_end_frame,
    "balance_event_end_frame": balance_event_end_frame,
    "transfer_qa_frame": transfer_qa_frame,
    "transfer_qa_summary_frame": transfer_qa_summary_frame,
    "transfer_gxz_qa_frame": transfer_gxz_qa_frame,
    "transfer_gxz_summary_frame": transfer_gxz_summary_frame,
    "transfer_query_end_frame": transfer_query_end_frame,
    "transfer_event_end_frame": transfer_event_end_frame,
    "transfer_confirm_menu_frame": transfer_confirm_menu_frame,
    "product_buy_questioner_frame": product_buy_questioner_frame,
    "product_buy_gxz_qa_frame": product_buy_gxz_qa_frame,
    "product_buy_gxz_summary_frame": product_buy_gxz_summary_frame,
    "product_buy_query_end_frame": product_buy_query_end_frame,
    "product_buy_event_end_frame": product_buy_event_end_frame,
    "wealth_product_filter_json": wealth_product_filter_json,
    "fund_product_filter_json": fund_product_filter_json,
    "product_buy_response_json": product_buy_response_json,
    "balance_business_result": balance_business_result,
    "balance_business_result_json": balance_business_result_json,
    "balance_simple_qa_json": balance_simple_qa_json,
    "transfer_response_json": transfer_response_json,
    "transfer_confirmed_simple_json": transfer_confirmed_simple_json,
    "default_error_qa_json": default_error_qa_json,
    "default_answer_qa_json": default_answer_qa_json,
}


def call_hook(name: str, ctx: dict[str, Any]) -> Any:
    fn = HOOK_REGISTRY.get(name)
    if fn is None:
        raise KeyError(f"unknown hook: {name}")
    return fn(ctx)


def get_transfer_counters() -> dict[str, int]:
    return dict(_transfer_counters)


def get_balance_states() -> dict[str, dict[str, Any]]:
    return {
        k: {
            "licai_balance": v["licai_balance"],
            "chuxu_balance": v["chuxu_balance"],
            "licai_tail": v["licai_tail"],
            "chuxu_tail": v["chuxu_tail"],
        }
        for k, v in _balance_states.items()
    }


def count_product_list_entries(raw: Any) -> int:
    if isinstance(raw, list):
        return sum(1 for item in raw if isinstance(item, dict))
    if not isinstance(raw, str):
        return 0
    s = raw.strip()
    if not s:
        return 0
    try:
        parsed = ast.literal_eval(s)
        if isinstance(parsed, list):
            return sum(1 for item in parsed if isinstance(item, dict))
    except Exception:
        pass
    try:
        parsed = json.loads(s)
        if isinstance(parsed, list):
            return sum(1 for item in parsed if isinstance(item, dict))
    except Exception:
        pass
    return 0
