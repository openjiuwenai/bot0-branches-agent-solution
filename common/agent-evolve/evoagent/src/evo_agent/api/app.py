"""FastAPI app 实例 + 路由注册。"""

import logging
from typing import Any

from fastapi import FastAPI, Request, Response
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from evo_agent.api.routes import (
    capabilities,
    evaluate,
    evaluate_agent_judge,
    evaluate_dataset,
    golden_data,
    optimize,
    scenarios,
)

logger = logging.getLogger(__name__)


def create_app() -> FastAPI:
    """创建并配置 FastAPI 应用。"""
    from evo_agent.stdio_utf8 import ensure_utf8_stdio

    ensure_utf8_stdio()
    application = FastAPI(
        title="EvoAgent API",
        description="Skill 文档自动优化服务",
        version="0.1.0",
    )
    application.include_router(scenarios.router)
    application.include_router(optimize.router)
    application.include_router(evaluate.router)
    application.include_router(evaluate_dataset.router)
    application.include_router(evaluate_agent_judge.router)
    application.include_router(capabilities.router)
    application.include_router(golden_data.router)

    @application.exception_handler(RequestValidationError)
    async def stable_request_validation_error(
        request: Request, exc: RequestValidationError
    ) -> Response:
        target_errors = [
            error for error in exc.errors() if error["type"] == "optimization_target_invalid"
        ]
        if target_errors:
            return JSONResponse(
                status_code=422,
                content={
                    "detail": {
                        "code": "OPTIMIZATION_TARGET_INVALID",
                        "message": target_errors[0]["msg"],
                    }
                },
            )
        return await request_validation_exception_handler(request, exc)

    @application.middleware("http")
    async def log_request_body(request: Request, call_next: Any) -> Response:
        if request.method == "POST":
            # multipart/form-data 上传由 Starlette 流式处理，读 body 会全量缓冲
            # 进内存（大文件 OOM）并可能干扰流；跳过日志。
            content_type = request.headers.get("content-type", "")
            if not content_type.startswith("multipart/form-data"):
                body = await request.body()
                if body:
                    decoded = body.decode("utf-8", errors="replace")[:2000]
                    logger.info("POST %s body: %s", request.url.path, decoded)
        return await call_next(request)  # type: ignore[no-any-return]

    @application.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    return application


app = create_app()
