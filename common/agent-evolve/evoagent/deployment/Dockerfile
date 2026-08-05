# ──────────────────────────────────────────────────────────────────────
# EvoAgent — 自进化元 Agent 运行镜像
#   基于 python:3.12-slim，单阶段构建
#   使用 pip 直接安装（跳过 uv 下载，减少网络消耗）
#   通过 uvicorn 暴露 FastAPI 服务
# ──────────────────────────────────────────────────────────────────────

FROM python:3.12-slim

ENV PIP_DEFAULT_TIMEOUT=300 \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_INDEX_URL=https://mirrors.huaweicloud.com/repository/pypi/simple \
    PIP_TRUSTED_HOST=mirrors.huaweicloud.com \
    PYTHONPATH=/app/src

WORKDIR /app

# 安装系统依赖：patch（客户现场打补丁用，离线环境无网安装）
# 配华为云 apt 源加速（与 adapter 一致）
RUN sed -i 's/deb.debian.org/repo.huaweicloud.com/g' /etc/apt/sources.list.d/debian.sources \
    && sed -i 's/security.debian.org/repo.huaweicloud.com/g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update && apt-get install -y --no-install-recommends patch \
    && rm -rf /var/lib/apt/lists/*

# 复制依赖清单和 vendored wheel
COPY pyproject.toml ./
COPY vendor/ ./vendor/

# 安装依赖并清理临时文件
RUN pip install --no-cache-dir vendor/openjiuwen-*.whl && \
    pip install --no-cache-dir \
        'pydantic-settings>=2.0' \
        'structlog>=24.0' \
        'httpx>=0.27' \
        'pyyaml>=6.0' \
        'fastapi>=0.115' \
        'uvicorn[standard]>=0.30' \
        'python-multipart>=0.0.9' \
        'openpyxl>=3.1' && \
    rm -rf vendor/ && \
    rm -rf /root/.cache/pip/ && \
    find /app -type d -name "__pycache__" -exec rm -rf {} +

# 复制源码
COPY src/ ./src/
COPY examples/scenarios/ ./examples/scenarios/
COPY skills/ ./skills/

EXPOSE 8000

CMD ["python", "-m", "uvicorn", "evo_agent.api.app:app", "--host", "0.0.0.0", "--port", "8000"]
