.PHONY: install lint fix test test-unit dev serve clean

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

dev:
	uv run python -m evo_agent

serve:
	uv run uvicorn evo_agent.api.app:app --host 0.0.0.0 --port 8001

clean:
	rm -rf workspace/ .pytest_cache/ .mypy_cache/
