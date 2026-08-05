"""SqlSanitizer 单元测试 — 标识符白名单校验。"""

import pytest

from openjiuwen.tools.db_connector.security.sql_sanitizer import (
    validate_identifier,
    SqlSanitizer,
)


class TestValidateIdentifier:
    def test_valid_identifier(self):
        assert validate_identifier("traffic_flow") == "traffic_flow"
        assert validate_identifier("station_id") == "station_id"
        assert validate_identifier("_test") == "_test"
        assert validate_identifier("a") == "a"

    def test_invalid_identifier_special_chars(self):
        with pytest.raises(ValueError):
            validate_identifier("traffic;flow")
        with pytest.raises(ValueError):
            validate_identifier("traffic'--")
        with pytest.raises(ValueError):
            validate_identifier("table$")

    def test_invalid_identifier_empty(self):
        with pytest.raises(ValueError):
            validate_identifier("")

    def test_invalid_identifier_too_long(self):
        with pytest.raises(ValueError):
            validate_identifier("a" * 65)

    def test_injection_patterns_rejected(self):
        with pytest.raises(ValueError):
            validate_identifier("UNION")
        with pytest.raises(ValueError):
            validate_identifier("DROP")
        with pytest.raises(ValueError):
            validate_identifier("xp_cmdshell")


class TestSqlSanitizer:
    def test_table_whitelist_pass(self):
        s = SqlSanitizer(allowed_tables=["traffic_flow", "toll_station"])
        assert s.validate_table("traffic_flow") == "traffic_flow"

    def test_table_whitelist_reject(self):
        s = SqlSanitizer(allowed_tables=["traffic_flow"])
        with pytest.raises(ValueError, match="不在白名单"):
            s.validate_table("users")

    def test_table_no_whitelist_allows_valid(self):
        s = SqlSanitizer(allowed_tables=None)
        assert s.validate_table("any_valid_table") == "any_valid_table"

    def test_column_validation(self):
        s = SqlSanitizer()
        assert s.validate_column("station_id") == "station_id"
        with pytest.raises(ValueError):
            s.validate_column("col;drop")

    def test_is_safe_identifier(self):
        s = SqlSanitizer()
        assert s.is_safe_identifier("valid_col") is True
        assert s.is_safe_identifier("invalid;col") is False
