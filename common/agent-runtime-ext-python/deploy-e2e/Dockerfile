# ──────────────────────────────────────────────────────────────────────
# 容器级部署 E2E 镜像：在 Docker 里跑 agent_runtime(洋葱) + 真实 agent-core
# 底座对齐 agent-store@EDP-agent 的 deployment/Dockerfile（openjiuwen==0.1.16 + a2a-sdk==1.0.0），
# 但服务面装的是我的 agent_runtime（非存量 patch a2a_service）。
# build 上下文 = 仓根 agent-runtime-mvp/
# ──────────────────────────────────────────────────────────────────────
FROM python:3.11-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN pip install --no-cache-dir uv

ENV VIRTUAL_ENV=/opt/venv \
    PATH="/opt/venv/bin:$PATH" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# 依赖清单单独先 COPY：它比源码稳定得多，这样改一行代码不会触发整棵依赖树重装。
COPY agent_runtime/requirements.txt /tmp/requirements.txt

# 版本由 requirements.txt 精确钉死，容器与本地 .venv 共用同一份，杜绝两边装到不同版本。
# --prerelease=allow 仍需保留：清单里有三个显式钉死的预发布版（protobuf rc、pydantic
# alpha、starlette 1.x），uv 在无此标志时会拒绝解析。钉死的语义不受影响——版本号已写死。
RUN uv venv $VIRTUAL_ENV && \
    uv pip install --no-cache --prerelease=allow -r /tmp/requirements.txt

# 我的 runtime 洋葱包 + 全部 E2E 入口
COPY agent_runtime /app/agent_runtime
# **按模式拷贝，不逐个列举**：逐个列举时新增一个入口就漏一个——
# 对等比对服务与回调服务都曾因此不在镜像里，容器后端跑不起来而本机后端能跑，
# 差异只在这一行。模式拷贝让「新增入口」不再需要同步改 Dockerfile。
COPY deploy-e2e/e2e_*.py /app/

EXPOSE 8090

# E2E_MODULE 选变体：默认 e2e_server（无 LLM）；questioner+LLM 传 e2e_questioner_server。
CMD ["sh", "-c", "uvicorn ${E2E_MODULE:-e2e_server}:app --host 0.0.0.0 --port 8090"]
