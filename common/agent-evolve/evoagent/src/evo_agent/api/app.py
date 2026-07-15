"""FastAPI app 实例 + 路由注册。"""

import logging
from typing import Any

from fastapi import FastAPI, Request, Response

from evo_agent.api.routes import evaluate, evaluate_dataset, optimize, scenarios

logger = logging.getLogger(__name__)


def create_app() -> FastAPI:
    """创建并配置 FastAPI 应用。"""
    app = FastAPI(
        title="EvoAgent API",
        description="Skill 文档自动优化服务",
        version="0.1.0",
    )
    app.include_router(scenarios.router)
    app.include_router(optimize.router)
    app.include_router(evaluate.router)
    app.include_router(evaluate_dataset.router)

    @app.middleware("http")
    async def log_request_body(request: Request, call_next: Any) -> Response:
        if request.method == "POST":
            # multipart/form-data 上传由 Starlette 流式处理，读 body 会全量缓冲
            # 进内存（大文件 OOM）并可能干扰流；跳过日志。
            content_type = request.headers.get("content-type", "")
            if not content_type.startswith("multipart/form-data"):
                body = await request.body()
                if body:
                    decoded = body.decode("utf-8", errors="replace")[:2000]
                    print(f"POST {request.url.path} body: {decoded}", flush=True)
        return await call_next(request)  # type: ignore[no-any-return]

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    return app


app = create_app()
