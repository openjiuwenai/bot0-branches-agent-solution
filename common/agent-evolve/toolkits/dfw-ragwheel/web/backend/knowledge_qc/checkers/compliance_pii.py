from __future__ import annotations

import re
from typing import List, Tuple

# 个人隐私：银行卡、手机、身份证等
PII_PATTERNS: List[Tuple[str, str, str]] = [
    ("COMPLIANCE_PII_CARD", r"\b[1-9]\d{15,18}\b", "银行卡号"),
    ("COMPLIANCE_PII_PHONE", r"1[3-9]\d{9}", "手机号"),
    ("COMPLIANCE_PII_ID", r"\d{17}[\dXx]", "身份证号"),
]

NAME_PATTERN = re.compile(
    r"(?<![\u4e00-\u9fff])([\u4e00-\u9fff]{2,4})(?![\u4e00-\u9fff])"
)
AMOUNT_PATTERN = re.compile(r"\d+(?:\.\d+)?(?:元|块|万)")


def desensitize(text: str) -> str:
    out = text
    for _, pattern, _ in PII_PATTERNS:
        out = re.sub(pattern, "[敏感信息]", out)
    out = AMOUNT_PATTERN.sub("[金额]", out)
    out = re.sub(r"给[\u4e00-\u9fff]{2,3}(?![\u4e00-\u9fff])", "给[收款人]", out)
    return out
