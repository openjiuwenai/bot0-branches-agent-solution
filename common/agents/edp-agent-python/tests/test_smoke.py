"""Smoke test: confirms pytest + conftest import hooks work."""


def test_smoke_pytest_runs():
    assert 1 + 1 == 2


def test_smoke_can_import_local_events():
    """EDPAgent owns its event schema locally."""
    from EDPAgent.events import TodoListItemEvent  # noqa: F401
