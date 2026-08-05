"""Unit tests for the graceful-shutdown helpers in EDPAgent.memory_engine.

Covers the three functions added to fix the DFR (resource leak):
  - _close_resource      : close a single async resource, isolating errors
  - _close_all_resources : close all tracked connections (ES / DB / Redis)
  - close_memory_engine  : public shutdown entry, drops engine ref + closes all

These tests never touch real Redis / ES / DB; they use lightweight async mock
resources that record close calls so we can assert ordering, isolation and
state reset.
"""
from __future__ import annotations

import pytest

import EDPAgent.memory_engine as mem_engine
from EDPAgent.memory_engine import (
    _close_all_resources,
    _close_resource,
    close_memory_engine,
    get_memory_engine,
    init_memory_engine,
)


# ---------------------------------------------------------------------------
# Test doubles
# ---------------------------------------------------------------------------
class _MockResource:
    """Async resource exposing close/aclose/dispose, all delegating to one impl.

    Each public method records its own name in ``called_methods`` *before*
    delegating to ``_close_impl``, so tests can verify that ``getattr``
    dispatched to the correct method (not just that *some* close ran).

    Also records invocation order into an optional shared list and whether it
    should raise to simulate a failing close.
    """

    def __init__(self, name="resource", *, fail=False, order_log=None):
        self.name = name
        self.fail = fail
        self.order_log = order_log
        self.closed = False
        self.close_count = 0
        self.called_methods: list[str] = []

    async def _close_impl(self):
        self.close_count += 1
        if self.order_log is not None:
            self.order_log.append(self.name)
        if self.fail:
            raise RuntimeError("close failed: " + self.name)
        self.closed = True

    async def close(self):
        self.called_methods.append("close")
        await self._close_impl()

    async def aclose(self):
        self.called_methods.append("aclose")
        await self._close_impl()

    async def dispose(self):
        self.called_methods.append("dispose")
        await self._close_impl()


class _EngineAwareResource(_MockResource):
    """Records the value of the module-level _memory_engine at close time.

    Used to verify that close_memory_engine drops the engine reference *before*
    it starts releasing the underlying connections.
    """

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.engine_ref_at_close = "not-closed-yet"

    async def _close_impl(self):
        self.engine_ref_at_close = mem_engine._memory_engine
        await super()._close_impl()


# ---------------------------------------------------------------------------
# Shared fixture: reset module globals between tests
# ---------------------------------------------------------------------------
@pytest.fixture(autouse=True)
def _reset_memory_engine_globals():
    mem_engine._memory_engine = None
    mem_engine._redis_client = None
    mem_engine._es_client = None
    mem_engine._db_engine = None
    yield
    mem_engine._memory_engine = None
    mem_engine._redis_client = None
    mem_engine._es_client = None
    mem_engine._db_engine = None


# ---------------------------------------------------------------------------
# _close_resource
# ---------------------------------------------------------------------------
class TestCloseResource:
    """_close_resource: close a single async resource, isolating errors."""

    @pytest.mark.asyncio
    async def test_none_resource_is_noop(self):
        """A None resource must not raise and must do nothing."""
        # Should not raise even though there is nothing to close.
        await _close_resource(None, "close", "nothing")

    @pytest.mark.asyncio
    async def test_calls_specified_close_method(self):
        """The close_method parameter selects which method is actually invoked.

        Each method name (close / aclose / dispose) must dispatch to the
        CORRESPONDING method on the resource, not just any close-like method.
        We verify this by checking ``called_methods`` records the exact name
        passed as ``close_method``.
        """
        for method_name in ("close", "aclose", "dispose"):
            res = _MockResource(name=method_name)
            await _close_resource(res, method_name, method_name)
            assert res.closed is True
            assert res.close_count == 1
            # The correct method was dispatched -- not just any method.
            assert res.called_methods == [method_name]

    @pytest.mark.asyncio
    async def test_swallows_exception_and_does_not_propagate(self):
        """A failing close must be swallowed (logged as warning), not raised."""
        res = _MockResource(name="boom", fail=True)
        # No exception should escape.
        await _close_resource(res, "close", "boom")
        # The close was still attempted (counted) even though it raised.
        assert res.close_count == 1
        assert res.closed is False  # failed before setting closed=True

    @pytest.mark.asyncio
    async def test_actually_awaits_coroutine(self):
        """The close coroutine must be awaited, not just scheduled.

        We assert the async side-effect took effect, proving the coroutine ran
        to completion rather than being discarded.
        """
        awaited = []

        class _AwaitableResource:
            async def aclose(self):
                awaited.append("done")

        await _close_resource(_AwaitableResource(), "aclose", "awaitable")
        assert awaited == ["done"]

    @pytest.mark.asyncio
    async def test_handles_synchronous_close_method(self):
        """A non-async close method (returns None) is handled without TypeError.

        Some close methods may be synchronous (e.g. an older SQLAlchemy engine
        whose dispose() is not a coroutine). The helper must detect this via
        inspect.isawaitable and not attempt to await a non-awaitable result,
        otherwise a misleading ``Failed to close`` warning is logged even though
        the resource was released.
        """
        from unittest.mock import patch

        closed = []

        class _SyncCloseResource:
            def dispose(self):  # sync, returns None
                closed.append("done")

        with patch.object(mem_engine, "logger") as mock_logger:
            await _close_resource(_SyncCloseResource(), "dispose", "sync-db")

        assert closed == ["done"]
        # No warning should be logged -- the sync close succeeded cleanly.
        mock_logger.warning.assert_not_called()
        # The success debug message should be logged.
        mock_logger.debug.assert_called_once()

    @pytest.mark.asyncio
    async def test_swallows_cancelled_error(self):
        """asyncio.CancelledError is caught, not propagated.

        On Python 3.9+ CancelledError inherits from BaseException, not
        Exception, so a plain 'except Exception' would miss it. The helper
        must explicitly catch it to maintain the isolation guarantee.
        """
        import asyncio

        class _CancelResource:
            async def close(self):
                raise asyncio.CancelledError()

        # Must not raise -- CancelledError is swallowed like any other error.
        await _close_resource(_CancelResource(), "close", "cancellable")


# ---------------------------------------------------------------------------
# _close_all_resources
# ---------------------------------------------------------------------------
class TestCloseAllResources:
    """_close_all_resources: close all tracked connections in reverse order."""

    @pytest.mark.asyncio
    async def test_closes_all_three_resources(self):
        """All three resources are closed and module refs reset to None."""
        redis = _MockResource(name="redis")
        db = _MockResource(name="db")
        es = _MockResource(name="es")
        mem_engine._redis_client = redis
        mem_engine._db_engine = db
        mem_engine._es_client = es

        await _close_all_resources()

        assert redis.closed is True
        assert db.closed is True
        assert es.closed is True
        assert mem_engine._redis_client is None
        assert mem_engine._db_engine is None
        assert mem_engine._es_client is None

    @pytest.mark.asyncio
    async def test_closes_in_reverse_creation_order(self):
        """Resources are released ES -> DB -> Redis (reverse of creation)."""
        order = []
        redis = _MockResource(name="redis", order_log=order)
        db = _MockResource(name="db", order_log=order)
        es = _MockResource(name="es", order_log=order)
        mem_engine._redis_client = redis
        mem_engine._db_engine = db
        mem_engine._es_client = es

        await _close_all_resources()

        assert order == ["es", "db", "redis"]

    @pytest.mark.asyncio
    async def test_all_none_is_noop(self):
        """When nothing is tracked, closing is a safe no-op."""
        mem_engine._redis_client = None
        mem_engine._db_engine = None
        mem_engine._es_client = None

        await _close_all_resources()  # must not raise

        assert mem_engine._redis_client is None
        assert mem_engine._db_engine is None
        assert mem_engine._es_client is None

    @pytest.mark.asyncio
    async def test_partial_creation_only_closes_existing(self):
        """Only resources that were actually created get closed.

        Simulates an init that failed after creating Redis + DB but before ES.
        """
        redis = _MockResource(name="redis")
        db = _MockResource(name="db")
        mem_engine._redis_client = redis
        mem_engine._db_engine = db
        mem_engine._es_client = None  # never created

        await _close_all_resources()

        assert redis.closed is True
        assert db.closed is True
        # All refs reset regardless of what was set.
        assert mem_engine._redis_client is None
        assert mem_engine._db_engine is None
        assert mem_engine._es_client is None

    @pytest.mark.asyncio
    async def test_one_failure_does_not_block_others(self):
        """If one resource fails to close, the others are still released."""
        redis = _MockResource(name="redis", fail=True)  # will raise
        db = _MockResource(name="db")
        es = _MockResource(name="es")
        mem_engine._redis_client = redis
        mem_engine._db_engine = db
        mem_engine._es_client = es

        await _close_all_resources()  # must not raise

        # The non-failing resources were still closed.
        assert es.closed is True
        assert db.closed is True
        assert redis.closed is False  # it raised before marking closed
        # Refs are reset even when a close failed.
        assert mem_engine._redis_client is None
        assert mem_engine._db_engine is None
        assert mem_engine._es_client is None

    @pytest.mark.asyncio
    async def test_multiple_failures_still_isolated(self):
        """Every failing close is isolated; none propagates."""
        redis = _MockResource(name="redis", fail=True)
        db = _MockResource(name="db", fail=True)
        es = _MockResource(name="es", fail=True)
        mem_engine._redis_client = redis
        mem_engine._db_engine = db
        mem_engine._es_client = es

        await _close_all_resources()  # must not raise despite 3 failures

        assert redis.close_count == 1
        assert db.close_count == 1
        assert es.close_count == 1
        assert mem_engine._redis_client is None
        assert mem_engine._db_engine is None
        assert mem_engine._es_client is None

    @pytest.mark.asyncio
    async def test_cancelled_error_does_not_block_others(self):
        """A CancelledError in one close does not prevent the others.

        On Python 3.9+ CancelledError is a BaseException subclass. If
        _close_resource did not catch it, _close_all_resources would stop
        at the first CancelledError, leaving remaining resources open.
        """
        import asyncio

        class _CancelResource(_MockResource):
            async def _close_impl(self):
                self.close_count += 1
                raise asyncio.CancelledError()

        redis = _MockResource(name="redis")
        db = _CancelResource(name="db")  # raises CancelledError
        es = _MockResource(name="es")
        mem_engine._redis_client = redis
        mem_engine._db_engine = db
        mem_engine._es_client = es

        await _close_all_resources()  # must not raise despite CancelledError

        # ES (closed before DB) and Redis (closed after DB) both closed.
        assert es.closed is True
        assert redis.closed is True
        # DB close was attempted but raised CancelledError.
        assert db.close_count == 1
        assert db.closed is False
        # Refs reset regardless.
        assert mem_engine._redis_client is None
        assert mem_engine._db_engine is None
        assert mem_engine._es_client is None


# ---------------------------------------------------------------------------
# close_memory_engine
# ---------------------------------------------------------------------------
class TestCloseMemoryEngine:
    """close_memory_engine: public shutdown entry point."""

    @pytest.mark.asyncio
    async def test_noop_when_completely_uninitialized(self):
        """Nothing initialized -> early return, no error."""
        # All globals are None (set by the autouse fixture).
        await close_memory_engine()  # must not raise
        assert mem_engine._memory_engine is None

    @pytest.mark.asyncio
    async def test_closes_engine_and_all_resources(self):
        """A fully-initialized engine is torn down completely."""
        redis = _MockResource(name="redis")
        db = _MockResource(name="db")
        es = _MockResource(name="es")
        mem_engine._memory_engine = object()  # pretend initialized
        mem_engine._redis_client = redis
        mem_engine._db_engine = db
        mem_engine._es_client = es

        await close_memory_engine()

        assert redis.closed is True
        assert db.closed is True
        assert es.closed is True
        assert mem_engine._memory_engine is None
        assert mem_engine._redis_client is None
        assert mem_engine._db_engine is None
        assert mem_engine._es_client is None

    @pytest.mark.asyncio
    async def test_drops_engine_ref_before_closing_resources(self):
        """The engine reference is cleared *before* connections are released.

        This prevents concurrent callers from using a half-closed engine.
        """
        es = _EngineAwareResource(name="es")
        mem_engine._memory_engine = object()
        mem_engine._es_client = es
        # redis/db stay None.

        await close_memory_engine()

        # ES was closed, and at the moment it was closed the engine ref was
        # already None.
        assert es.closed is True
        assert es.engine_ref_at_close is None

    @pytest.mark.asyncio
    async def test_closes_resources_when_engine_ref_already_none(self):
        """Resources are cleaned up even if only the engine init half-failed.

        Simulates: init created connections but threw before assigning
        _memory_engine (the except branch in init_memory_engine already calls
        _close_all_resources, but close_memory_engine must also handle this
        state for external callers / re-entrancy).
        """
        redis = _MockResource(name="redis")
        db = _MockResource(name="db")
        es = _MockResource(name="es")
        mem_engine._memory_engine = None  # engine never assigned
        mem_engine._redis_client = redis
        mem_engine._db_engine = db
        mem_engine._es_client = es

        await close_memory_engine()

        assert redis.closed is True
        assert db.closed is True
        assert es.closed is True
        assert mem_engine._memory_engine is None
        assert mem_engine._redis_client is None
        assert mem_engine._db_engine is None
        assert mem_engine._es_client is None

    @pytest.mark.asyncio
    async def test_idempotent_double_close(self):
        """Calling close twice is safe; the second call is a no-op."""
        redis = _MockResource(name="redis")
        db = _MockResource(name="db")
        es = _MockResource(name="es")
        mem_engine._memory_engine = object()
        mem_engine._redis_client = redis
        mem_engine._db_engine = db
        mem_engine._es_client = es

        await close_memory_engine()
        # Second call: everything is already None -> early return, no raise.
        await close_memory_engine()

        # Each resource was closed exactly once (not twice).
        assert redis.close_count == 1
        assert db.close_count == 1
        assert es.close_count == 1

    @pytest.mark.asyncio
    async def test_get_memory_engine_raises_after_close(self):
        """After shutdown, get_memory_engine must raise until re-init."""
        mem_engine._memory_engine = object()  # initialized
        assert get_memory_engine() is not None  # works before close

        await close_memory_engine()

        with pytest.raises(RuntimeError, match="not initialized"):
            get_memory_engine()

    @pytest.mark.asyncio
    async def test_close_survives_resource_failure(self):
        """A failing resource does not prevent the engine from closing."""
        redis = _MockResource(name="redis", fail=True)
        db = _MockResource(name="db")
        es = _MockResource(name="es")
        mem_engine._memory_engine = object()
        mem_engine._redis_client = redis
        mem_engine._db_engine = db
        mem_engine._es_client = es

        await close_memory_engine()  # must not raise

        # Engine and refs still cleared despite the failing close.
        assert mem_engine._memory_engine is None
        assert mem_engine._redis_client is None
        assert mem_engine._db_engine is None
        assert mem_engine._es_client is None
        # Non-failing resources still closed.
        assert db.closed is True
        assert es.closed is True

    @pytest.mark.asyncio
    async def test_close_is_safe_when_only_engine_ref_set(self):
        """Engine ref set but no connections (edge state) still closes cleanly."""
        mem_engine._memory_engine = object()
        mem_engine._redis_client = None
        mem_engine._db_engine = None
        mem_engine._es_client = None

        await close_memory_engine()

        assert mem_engine._memory_engine is None


# ---------------------------------------------------------------------------
# init_memory_engine -- CancelledError cleanup
# ---------------------------------------------------------------------------
class TestInitMemoryEngineCancelledCleanup:
    """init_memory_engine must clean up resources when CancelledError is raised.

    The try block has two await points (register_store, set_scope_config) at
    which all three connection globals are already assigned. On Python 3.9+
    CancelledError is a BaseException subclass, so 'except Exception' would
    miss it and skip _close_all_resources, leaking connections. The except
    clause must catch (Exception, asyncio.CancelledError) to stay consistent
    with _close_resource.
    """

    @pytest.mark.asyncio
    async def test_cancelled_at_register_store_triggers_cleanup(self):
        """CancelledError at register_store await triggers _close_all_resources.

        At this point Redis, DB, and ES globals are all assigned (lines 145 /
        153 / 161). The except clause must catch CancelledError so cleanup runs.
        """
        import asyncio
        import sys
        import types
        from unittest.mock import AsyncMock, MagicMock, patch

        # Mock resources that track close calls.
        redis_mock = _MockResource(name="redis")
        db_mock = _MockResource(name="db")
        es_mock = _MockResource(name="es")

        # elasticsearch is not installed in the test venv; inject a stub.
        es_stub = types.ModuleType("elasticsearch")
        es_stub.AsyncElasticsearch = MagicMock(return_value=es_mock)

        settings = MagicMock()

        with patch.dict(sys.modules, {"elasticsearch": es_stub}), \
                patch("redis.asyncio.Redis") as mock_redis_cls, \
                patch("sqlalchemy.ext.asyncio.create_async_engine") as mock_engine_fn, \
                patch("openjiuwen.core.memory.long_term_memory.LongTermMemory") as mock_ltm_cls:
            mock_redis_cls.from_url.return_value = redis_mock
            mock_engine_fn.return_value = db_mock

            memory_instance = MagicMock()
            # register_store is the first await point inside the try block.
            memory_instance.register_store = AsyncMock(
                side_effect=asyncio.CancelledError()
            )
            mock_ltm_cls.return_value = memory_instance

            # CancelledError must propagate AFTER cleanup.
            with pytest.raises(asyncio.CancelledError):
                await init_memory_engine(settings)

            # _close_all_resources was called: all three resources closed.
            assert redis_mock.close_count == 1
            assert db_mock.close_count == 1
            assert es_mock.close_count == 1
            # Module globals reset after cleanup.
            assert mem_engine._memory_engine is None
            assert mem_engine._redis_client is None
            assert mem_engine._db_engine is None
            assert mem_engine._es_client is None

    @pytest.mark.asyncio
    async def test_cancelled_at_set_scope_config_triggers_cleanup(self):
        """CancelledError at set_scope_config await triggers cleanup.

        This is the second await point. register_store and set_config both
        succeed, but set_scope_config is cancelled. All three connection
        globals are assigned, so cleanup must still run.
        """
        import asyncio
        import sys
        import types
        from unittest.mock import AsyncMock, MagicMock, patch

        redis_mock = _MockResource(name="redis")
        db_mock = _MockResource(name="db")
        es_mock = _MockResource(name="es")

        es_stub = types.ModuleType("elasticsearch")
        es_stub.AsyncElasticsearch = MagicMock(return_value=es_mock)

        settings = MagicMock()
        # Avoid the embedding-custom-headers branch which calls list() on
        # a MagicMock and would raise TypeError -- though that line is after
        # set_scope_config, keeping it clean avoids surprises if the mock
        # flow changes.
        settings.memory_embedding_custom_headers = None

        with patch.dict(sys.modules, {"elasticsearch": es_stub}), \
                patch("redis.asyncio.Redis") as mock_redis_cls, \
                patch("sqlalchemy.ext.asyncio.create_async_engine") as mock_engine_fn, \
                patch("openjiuwen.core.memory.long_term_memory.LongTermMemory") as mock_ltm_cls, \
                patch("openjiuwen.core.memory.config.config.MemoryEngineConfig"), \
                patch("openjiuwen.core.memory.config.config.MemoryScopeConfig"), \
                patch("openjiuwen.core.memory.config.config.EmbeddingConfig"), \
                patch("openjiuwen.core.foundation.llm.schema.config.ModelRequestConfig"), \
                patch("openjiuwen.core.foundation.llm.schema.config.ModelClientConfig"):
            mock_redis_cls.from_url.return_value = redis_mock
            mock_engine_fn.return_value = db_mock

            memory_instance = MagicMock()
            memory_instance.register_store = AsyncMock()
            memory_instance.set_config = MagicMock()
            # set_scope_config is the second await point.
            memory_instance.set_scope_config = AsyncMock(
                side_effect=asyncio.CancelledError()
            )
            mock_ltm_cls.return_value = memory_instance

            with pytest.raises(asyncio.CancelledError):
                await init_memory_engine(settings)

            # Cleanup ran despite CancelledError.
            assert redis_mock.close_count == 1
            assert db_mock.close_count == 1
            assert es_mock.close_count == 1
            assert mem_engine._memory_engine is None
            assert mem_engine._redis_client is None
            assert mem_engine._db_engine is None
            assert mem_engine._es_client is None
