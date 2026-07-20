.PHONY: install lint fix test test-unit test-managed-doc-e2e dev serve clean

install:
	uv sync --all-extras

lint:
	uv run ruff check src/ tests/
	uv run ruff format --check src/ tests/
	uv run mypy src/

fix:
	uv run ruff check --fix src/ tests/
	uv run ruff format src/ tests/

test:
	uv run pytest tests/ -v

test-unit:
	uv run pytest tests/unit/ -v

test-managed-doc-e2e:
	EVO_RUN_MANAGED_DOC_E2E=1 uv run pytest tests/e2e/test_managed_doc_optimize.py -s -vv

dev:
	uv run python -m evo_agent

serve:
	uv run uvicorn evo_agent.api.app:app --host 0.0.0.0 --port 8001

clean:
	rm -rf workspace/ .pytest_cache/ .mypy_cache/
