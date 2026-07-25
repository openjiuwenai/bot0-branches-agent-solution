"""Unit tests for ToolDataChannel."""
from __future__ import annotations

import pytest

from EDPAgent.rail.tool_data_channel import ToolDataChannel


class MockSession:
    """Mock session for testing."""
    
    def __init__(self):
        self._state = {}
    
    def get_state(self, key):
        return self._state.get(key)
    
    def update_state(self, state_dict):
        self._state.update(state_dict)


class TestToolDataChannel:
    """Test ToolDataChannel CRUD operations."""
    
    @pytest.fixture
    def mock_session(self):
        return MockSession()
    
    @pytest.fixture
    def channel(self, mock_session):
        return ToolDataChannel(mock_session)
    
    def test_store_and_get(self, channel):
        """Test storing and retrieving data."""
        test_data = {"products": ["WM001", "WM002"], "bankCardNumber": "6605", "total": 1000.0}
        channel.store("test_key", test_data)
        
        retrieved = channel.get("test_key")
        assert retrieved == test_data
    
    def test_store_empty_data(self, channel):
        """Test storing empty data is skipped."""
        channel.store("empty_key", {})
        assert channel.get("empty_key") is None
        
        channel.store("none_key", None)
        assert channel.get("none_key") is None
        
        channel.store("invalid_key", "not_a_dict")
        assert channel.get("invalid_key") is None
    
    def test_store_overwrite(self, channel):
        """Test overwriting existing key."""
        channel.store("key", {"value": 1})
        channel.store("key", {"value": 2})
        
        retrieved = channel.get("key")
        assert retrieved == {"value": 2}
    
    def test_get_nonexistent_key(self, channel):
        """Test getting non-existent key returns None."""
        assert channel.get("nonexistent") is None
    
    def test_get_all(self, channel):
        """Test getting all stored data."""
        channel.store("key1", {"a": 1})
        channel.store("key2", {"b": 2})
        
        all_data = channel.get_all()
        assert all_data == {"key1": {"a": 1}, "key2": {"b": 2}}
    
    def test_get_keys(self, channel):
        """Test getting all keys."""
        channel.store("key1", {"a": 1})
        channel.store("key2", {"b": 2})
        
        keys = channel.get_keys()
        assert set(keys) == {"key1", "key2"}
    
    def test_remove(self, channel):
        """Test removing a key."""
        channel.store("key", {"value": 1})
        channel.remove("key")
        
        assert channel.get("key") is None
        assert channel.get_all() == {}
    
    def test_remove_nonexistent(self, channel):
        """Test removing non-existent key does nothing."""
        channel.remove("nonexistent")  # Should not raise
    
    def test_clear(self, channel):
        """Test clearing all data."""
        channel.store("key1", {"a": 1})
        channel.store("key2", {"b": 2})
        channel.clear()
        
        assert channel.get_all() == {}
        assert channel.get_keys() == []