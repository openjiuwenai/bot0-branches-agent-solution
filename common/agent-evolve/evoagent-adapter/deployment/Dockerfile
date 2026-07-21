# ============================================================
# Agent Adapter - 独立部署的日志采集与结构化提取服务
# 独立构建，不依赖 backend/frontend
# ============================================================

# Stage 1: Install adapter dependencies
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/python:3.12-slim AS builder

# 配置华为云镜像加速
RUN sed -i 's/deb.debian.org/repo.huaweicloud.com/g' /etc/apt/sources.list.d/debian.sources \
    && sed -i 's/security.debian.org/repo.huaweicloud.com/g' /etc/apt/sources.list.d/debian.sources

WORKDIR /build/adapter

COPY pyproject.toml ./

# 尝试多个 PyPI 镜像源安装依赖
RUN for idx in \
    "https://repo.huaweicloud.com/repository/pypi/simple" \
    "https://mirrors.aliyun.com/pypi/simple/" \
    "https://pypi.org/simple"; \
    do \
    echo "Trying PyPI index: $idx"; \
    pip install --no-cache-dir -i "$idx" --trusted-host $(echo $idx | awk -F/ '{print $3}') \
    aiofiles aiokafka asyncpg cramjam fastapi httpx pydantic-settings pyyaml rich structlog typer uvicorn && \
    echo "pip install succeeded with: $idx" && \
    break; \
    done

# Stage 2: Runtime
FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/python:3.12-slim

# 配置华为云镜像加速
RUN sed -i 's/deb.debian.org/repo.huaweicloud.com/g' /etc/apt/sources.list.d/debian.sources \
    && sed -i 's/security.debian.org/repo.huaweicloud.com/g' /etc/apt/sources.list.d/debian.sources

WORKDIR /app

# 安装系统依赖
# patch：客户现场打补丁用（离线环境无网安装）
# docker-cli：仅提供 managed-doc restart_cmd 所需客户端；未挂 socket 时无宿主权限
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl ca-certificates patch docker-cli \
    && rm -rf /var/lib/apt/lists/*

# 从 builder 复制已安装的 Python 包
COPY --from=builder /usr/local/lib/python3.12/site-packages /usr/local/lib/python3.12/site-packages
COPY --from=builder /usr/local/bin /usr/local/bin

# 复制 adapter 应用代码
COPY src/ ./src/
COPY pyproject.toml ./
COPY deployment/config/agent_adapter_config.yaml ./

# 创建运行时数据目录 + 挂载点目录
# /data/logs   — 业务 Agent 日志挂载点（docker-compose 只读挂载）
# /data/skills — 业务 Agent skills 挂载点（docker-compose 读写挂载）
# /data/agents — AgentRule.md 等 managed-doc 挂载点（读写）
RUN mkdir -p data/output data/offsets data/skills /data/logs /data/skills /data/agents

ENV PYTHONPATH="/app/src"

EXPOSE 8900

# 默认命令：通过 __main__ 启动（已调用 configure_logging，日志正常输出）
CMD ["python", "-m", "agent_adapter"]
