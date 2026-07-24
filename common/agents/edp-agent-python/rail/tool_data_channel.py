"""
工具间数据通道：管理 Session 中 tool_data_channel 的存入、读取、清除。

存储结构（单字段设计，详见 ADR-006）：
    session["tool_data_channel"] = {
        "fund_recommend_result": {"products": [...], "bankCardNumber": "6605"},
        "credit_report_123": {"creditScore": 750, "riskLevel": "low"},
    }

设计决策：不单独维护 key 索引列表。
原因：dict.keys() 天然提供 key 枚举，单独维护索引列表是冗余的
非规范化副本，存在数据不一致风险，且增加 store/remove/clear 的维护负担。
"""
from __future__ import annotations

from typing import Any, Dict, Optional

from loguru import logger


class ToolDataChannel:
    """工具间数据通道：管理 Session 中 tool_data_channel 的存入、读取、清除。

    存储结构（单字段设计，详见 ADR-006）：
        session["tool_data_channel"] = {
            "fund_recommend_result": {"products": [...], "bankCardNumber": "6605"},
            "credit_report_123": {"creditScore": 750, "riskLevel": "low"},
        }

    设计决策：不单独维护 key 索引列表。
    原因：dict.keys() 天然提供 key 枚举，单独维护索引列表是冗余的
    非规范化副本，存在数据不一致风险，且增加 store/remove/clear 的维护负担。
    """

    SESSION_DATA_KEY = "tool_data_channel"

    def __init__(self, session):
        self._session = session

    def _get_channel(self) -> dict:
        """获取当前通道数据字典。"""
        return self._session.get_state(self.SESSION_DATA_KEY) or {}

    def _save_channel(self, channel: dict) -> None:
        """持久化通道数据到 Session。"""
        self._session.update_state({self.SESSION_DATA_KEY: channel})

    def store(self, result_key: str, data: dict) -> None:
        """存入数据到 tool_data_channel。

        Args:
            result_key: 数据在通道中的唯一键名，对应前序脚本输出的 result_key。
            data: 要存储的业务数据字典。

        Raises:
            无。数据为空或不合法时仅 warning，不中断流程。
        """
        if not isinstance(data, dict) or not data:
            logger.warning(
                f"[ToolDataChannel] store 跳过："
                f"result_key={result_key!r}, data_type={type(data)}, data_empty={not data}"
            )
            return

        channel = self._get_channel()
        is_overwrite = result_key in channel

        channel[result_key] = data
        self._save_channel(channel)

        logger.info(
            f"[ToolDataChannel] store: result_key={result_key}, "
            f"overwrite={is_overwrite}, total_keys={len(channel)}"
        )

    def get(self, result_key: str) -> Optional[dict]:
        """从 tool_data_channel 读取指定 key 的数据。

        Args:
            result_key: 要读取的数据键名。

        Returns:
            对应的数据字典，不存在则返回 None。
        """
        return self._get_channel().get(result_key)

    def get_all(self) -> dict:
        """获取 tool_data_channel 全部数据。"""
        return self._get_channel()

    def get_keys(self) -> list:
        """获取所有已存储的 result_key 列表。

        注意：直接从 dict.keys() 派生，不单独维护索引列表。
        """
        return list(self._get_channel().keys())

    def remove(self, result_key: str) -> None:
        """移除指定 key 的数据。"""
        channel = self._get_channel()
        channel.pop(result_key, None)
        self._save_channel(channel)

        logger.info(
            f"[ToolDataChannel] remove: result_key={result_key}, "
            f"remaining_keys={len(channel)}"
        )

    def clear(self) -> None:
        """清除 tool_data_channel 全部数据。"""
        self._session.update_state({self.SESSION_DATA_KEY: None})
        logger.info("[ToolDataChannel] clear: all data removed")
