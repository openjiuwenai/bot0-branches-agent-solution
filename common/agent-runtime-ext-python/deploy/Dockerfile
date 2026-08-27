# ──────────────────────────────────────────────────────────────────────
# 黑盒测试用镜像：agent-runtime 参考宿主装配
#
# 与端到端验证镜像（deploy-e2e/Dockerfile）的区别：那个装的是各专项验证入口，
# 本镜像装的是可替换存量服务的参考装配，供测试人员照测试方案执行。
#
# 构建上下文 = 仓根
#   docker build -f deploy/Dockerfile -t agent-runtime:blackbox .
# ──────────────────────────────────────────────────────────────────────
FROM python:3.11-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/deploy

# 依赖清单单独先拷：它比源码稳定得多，改一行代码不必重装整棵依赖树。
COPY agent_runtime/requirements.txt /tmp/requirements.txt

# 版本由清单精确钉死，容器与本地环境共用同一份，杜绝两边装到不同版本。
# 清单里有三个显式钉死的预发布版，缺 --pre 时解析器会直接拒绝——
# 钉死的语义不受影响，版本号已经写定。
RUN pip install --no-cache-dir --pre -r /tmp/requirements.txt

COPY agent_runtime /app/agent_runtime
COPY deploy /app/deploy

EXPOSE 8090

# 健康探测走服务自己的健康端点，与测试方案的冒烟第一条同一条路径。
HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=3 \
  CMD curl -fs http://127.0.0.1:8090/health || exit 1

CMD ["python", "-m", "uvicorn", "host_app:app", "--host", "0.0.0.0", "--port", "8090"]
