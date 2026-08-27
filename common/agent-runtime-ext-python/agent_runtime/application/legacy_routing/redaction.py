# coding: utf-8

"""结构化数据的递归脱敏：落日志前把凭据类字段的值换掉。

## 为什么按键名而不是按值

值没有可靠特征——令牌可以长得像普通字符串。键名是调用方自己写的，
它知道那一项是什么。**代价是键名拼错就漏脱敏**，故键名集合要覆盖常见写法
（连字符与下划线、带不带前缀）。

## 只换值，不删键

删键会让日志里看不出「这里本来有个凭据」，排查时无从判断是没传还是被脱掉了。
"""
from __future__ import annotations

#: 命中即脱敏的键名（小写比较）。**覆盖同一含义的多种写法**。
SENSITIVE_KEYS = frozenset(
    {
        "api_key",
        "apikey",
        "token",
        "access_token",
        "refresh_token",
        "password",
        "secret",
        "authorization",
        "cust-token",
    }
)

#: 脱敏后的占位值，与存量一致。
MASK = "***"


def mask_sensitive_fields(payload: object) -> object:
    """递归脱敏字典与列表。**其它类型原样返回**——标量本身不带键名，无从判定。"""
    if isinstance(payload, dict):
        return {
            key: (
                MASK
                if isinstance(key, str) and key.lower() in SENSITIVE_KEYS
                else mask_sensitive_fields(value)
            )
            for key, value in payload.items()
        }
    if isinstance(payload, list):
        return [mask_sensitive_fields(item) for item in payload]
    return payload
