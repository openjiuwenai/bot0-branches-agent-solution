"""SqlGuard 单元测试 — 危险语句拦截。"""

import pytest

from openjiuwen.tools.db_connector.config import SecurityConfig
from openjiuwen.tools.db_connector.security.sql_guard import SqlGuard


@pytest.fixture
def guard():
    return SqlGuard(SecurityConfig())


class TestSqlGuard:
    def test_select_allowed_in_readonly(self, guard):
        result = guard.check("SELECT * FROM traffic_flow WHERE station_id = %s", "readonly")
        assert result.allowed is True

    def test_insert_blocked_in_readonly(self, guard):
        result = guard.check("INSERT INTO traffic_flow (station_id) VALUES (%s)", "readonly")
        assert result.allowed is False
        assert "只读模式" in result.reason

    def test_insert_allowed_in_readwrite(self, guard):
        result = guard.check("INSERT INTO traffic_flow (station_id) VALUES (%s)", "readwrite")
        assert result.allowed is True

    def test_drop_blocked(self, guard):
        result = guard.check("DROP TABLE traffic_flow", "ddl")
        assert result.allowed is False
        assert "黑名单" in result.reason

    def test_truncate_blocked(self, guard):
        result = guard.check("TRUNCATE TABLE traffic_flow", "ddl")
        assert result.allowed is False

    def test_update_without_where_blocked(self, guard):
        result = guard.check("UPDATE traffic_flow SET flow = 100", "readwrite")
        assert result.allowed is False
        assert "WHERE" in result.reason

    def test_delete_without_where_blocked(self, guard):
        result = guard.check("DELETE FROM traffic_flow", "readwrite")
        assert result.allowed is False

    def test_update_with_where_allowed(self, guard):
        result = guard.check(
            "UPDATE traffic_flow SET flow = %s WHERE station_id = %s", "readwrite"
        )
        assert result.allowed is True

    def test_comment_injection_blocked(self, guard):
        result = guard.check("SELECT * FROM traffic_flow -- WHERE 1=1", "readonly")
        assert result.allowed is False
        assert "注释" in result.reason

    def test_stacked_injection_blocked(self, guard):
        result = guard.check(
            "SELECT * FROM traffic_flow; DROP TABLE users", "readonly"
        )
        assert result.allowed is False

    def test_ddl_blocked_without_allow_ddl(self):
        g = SqlGuard(SecurityConfig(allow_ddl=False))
        result = g.check("CREATE TABLE test (id INT)", "ddl")
        assert result.allowed is False

    def test_ddl_allowed_with_config(self):
        g = SqlGuard(SecurityConfig(allow_ddl=True))
        result = g.check("CREATE TABLE test (id INT)", "ddl")
        assert result.allowed is True
