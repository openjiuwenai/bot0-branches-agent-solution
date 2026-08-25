"""RAG 知识质检核心库（由 DataQualCheck/dataqualcheck 迁入平台 backend）。"""

from backend.knowledge_qc.config import load_settings, save_rules

__all__ = ["load_settings", "save_rules"]
