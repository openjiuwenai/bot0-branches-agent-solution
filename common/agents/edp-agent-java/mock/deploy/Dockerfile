# Streaming-Agent-Testing-Evaluation mock runtime image.
# Build context must be the mock project root:
#   docker build -f deploy/Dockerfile -t versatile-mock:1.0.0 .
FROM python:3.11-slim

ARG PIP_INDEX_URL=https://pypi.org/simple
ENV PIP_INDEX_URL=${PIP_INDEX_URL} \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONUTF8=1 \
    MOCK_SERVER_HOST=0.0.0.0 \
    MOCK_SERVER_PORT=30001

WORKDIR /app

COPY deploy/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

COPY versatile_main.py /app/versatile_main.py
COPY engine/ /app/engine/
COPY config/ /app/config/
COPY workflows/ /app/workflows/

EXPOSE 30001

# /health always returns HTTP 200, so also reject workflow loading errors and an empty/default-less store.
HEALTHCHECK --interval=10s --timeout=5s --retries=6 --start-period=10s \
  CMD python -c "import json,urllib.request; d=json.load(urllib.request.urlopen('http://127.0.0.1:30001/health',timeout=3)); assert d.get('status')=='healthy'; assert 'default' in d.get('workflows_loaded',[]); assert not d.get('load_errors')"

# The mock only reads bundled files and keeps scenario state in memory.
USER 65532:65532
ENTRYPOINT ["python", "-u", "/app/versatile_main.py"]

