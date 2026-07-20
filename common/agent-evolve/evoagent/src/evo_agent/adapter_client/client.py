"""AdapterClient — 与 Adapter sidecar 的 HTTP 通信层。

Adapter 部署模型（所有接口均为 1:N）：
- Skill 操作: agent_name 在请求体中指定
- 对话执行: agent_name 在路径中指定
- 轨迹收集: agent_name 在路径中指定

API 契约：docs/api/adapter-api-contract.md
"""

from __future__ import annotations

import asyncio
from typing import Any

import httpx


class AdapterError(Exception):
    """Adapter sidecar 返回的错误。

    两种来源：
    - Adapter 路由错误（HTTP 4xx）：detail 字段
    - 业务 Agent 调用失败（HTTP 200 + success=false）：error 字段
    """

    def __init__(self, message: str, *, status_code: int) -> None:
        self.status_code = status_code
        super().__init__(message)


class AdapterClient:
    """与 Adapter sidecar 的 HTTP 通信层。

    Parameters
    ----------
    adapter_url:
        Adapter sidecar 的 base URL。
    agent_name:
        目标 Agent 名称（Adapter 1:N 路由）。
        Skill 操作放在请求体中，对话/轨迹放在路径中。
    timeout:
        HTTP 请求超时时间（秒），默认 300s（适配长时间 rollout）。
    max_retries:
        最大重试次数，默认 2。仅对 5xx 和网络错误重试。
    """

    def __init__(
        self,
        adapter_url: str,
        *,
        agent_name: str = "",
        timeout: float = 300.0,
        max_retries: int = 2,
    ) -> None:
        if not agent_name:
            raise ValueError("agent_name must be a non-empty string")
        self._base_url = adapter_url.rstrip("/")
        self._agent_name = agent_name
        self._timeout = timeout
        self._max_retries = max_retries
        # async client — 延迟创建，绑定到首次使用时的 event loop。
        # 避免跨 event loop 使用（main thread → worker thread via asyncio.to_thread）。
        self._async_http: httpx.AsyncClient | None = None
        self._async_http_loop: asyncio.AbstractEventLoop | None = None
        # sync client — 仅用于 update_skill（operator callback 是同步调用链）
        # trust_env=False: 忽略 HTTP_PROXY 等环境变量，Adapter 走直连
        self._sync_http = httpx.Client(
            base_url=self._base_url,
            timeout=httpx.Timeout(timeout),
            trust_env=False,
        )

    @property
    def _async_client(self) -> httpx.AsyncClient:
        """延迟创建 async HTTP client，确保绑定到当前 event loop。

        AdapterClient 通常在 main thread 的 event loop 中构造，
        但实际使用发生在 worker thread 的 event loop 中
        （via ``asyncio.to_thread(trainer.train)`` → ``asyncio.run()``）。
        如果在 ``__init__`` 中创建 ``httpx.AsyncClient``，其内部锁
        会绑定到 main thread 的 event loop，导致
        ``<asyncio.locks.Event> is bound to a different event loop`` 错误。
        """
        try:
            current_loop = asyncio.get_running_loop()
        except RuntimeError:
            current_loop = None

        if (
            self._async_http is None
            or self._async_http.is_closed
            or (self._async_http_loop is not None and self._async_http_loop is not current_loop)
        ):
            self._async_http = httpx.AsyncClient(
                base_url=self._base_url,
                timeout=httpx.Timeout(self._timeout),
                trust_env=False,
            )
            self._async_http_loop = current_loop
        return self._async_http

    # ── 对话 ──

    async def invoke(
        self,
        *,
        case_id: str,
        query: str,
        extra_data: dict[str, Any] | None = None,
        run_id: str,
    ) -> dict[str, Any]:
        """POST /api/v1/agents/{agent_name}/conversations/{conversation_id}。

        同步触发一次对话，Adapter 消费完整 SSE 流后返回结果。

        Parameters
        ----------
        case_id:
            对话 ID（同时作为 URL path 参数 conversation_id）。
        query:
            本轮用户输入。
        extra_data:
            额外键值对，将被合并到 custom_data.inputs 中转发给业务 Agent。
        run_id:
            优化运行 ID（用于日志关联，放入 extra_data）。

        Returns
        -------
        dict
            完整响应体，包含 success, answer, interrupted, events 等字段。

        Raises
        ------
        AdapterError
            Adapter 路由错误（HTTP 4xx）或业务 Agent 调用失败（success=false）。
        """
        merged_extra = {**(extra_data or {}), "run_id": run_id}
        body: dict[str, Any] = {"query": query, "extra_data": merged_extra}
        path = f"/api/v1/agents/{self._agent_name}/conversations/{case_id}"
        data = await self._request_with_retry("POST", path, json=body)

        # 业务 Agent 调用失败：HTTP 200 + success=false
        if not data.get("success", True):
            raise AdapterError(
                data.get("error", "unknown error"),
                status_code=200,
            )

        return data

    # ── 轨迹 ──

    async def get_traces(
        self,
        *,
        case_id: str,
    ) -> dict[str, Any]:
        """GET /api/v1/agents/{agent_name}/cleaned-traces/{conversation_id}。

        获取清洗后的对话轨迹。

        Returns
        -------
        dict
            包含 session_id, agent_name, task_input, trajectory, messages 的完整响应。
            无轨迹数据时返回空 dict ``{}``。
        """
        path = f"/api/v1/agents/{self._agent_name}/cleaned-traces/{case_id}"
        return await self._request_with_retry("GET", path)

    # ── Skill 操作 ──

    def update_skill(
        self,
        *,
        skill_name: str,
        skill_content: str,
    ) -> dict[str, Any]:
        """推送 skill 文档给业务 Agent（同步 httpx）。

        POST /api/v1/skills  action=update_skill

        使用同步 httpx.Client（非 async），因为此方法在 operator callback
        （on_parameter_updated）中被同步调用。
        与 async 方法保持一致，对 502/503 和网络错误自动重试。

        Returns
        -------
        dict
            Adapter 成功响应，含 ``skill_name`` / ``revision``（内容哈希），
            便于调用方在热更后强制使用新 conversation_id。

        Raises
        ------
        AdapterError
            Adapter 返回 success=false 或 HTTP 错误时抛出。
        """
        body = {
            "agent_name": self._agent_name,
            "action": "update_skill",
            "skill_name": skill_name,
            "skill_content": skill_content,
        }
        last_error: Exception | None = None
        for attempt in range(self._max_retries + 1):
            try:
                response = self._sync_http.post("/api/v1/skills", json=body)
                data = self._handle_response(response)
                if not data.get("success", False):
                    raise AdapterError(
                        data.get("message", "update_skill failed"),
                        status_code=response.status_code,
                    )
                return data
            except AdapterError as e:
                if e.status_code in (502, 503) and attempt < self._max_retries:
                    last_error = e
                    continue
                raise
            except httpx.TransportError as e:
                if attempt < self._max_retries:
                    last_error = e
                    continue
                raise AdapterError(str(e), status_code=0) from e
        raise last_error  # type: ignore[misc]

    async def skill_list(self) -> list[dict[str, Any]]:
        """POST /api/v1/skills  action=skill_list

        Returns
        -------
        list[dict]
            Skill 列表：``[{"name": "skill_a"}, ...]``
        """
        body = {"agent_name": self._agent_name, "action": "skill_list"}
        data = await self._request_with_retry("POST", "/api/v1/skills", json=body)
        return data.get("skills", [])  # type: ignore[no-any-return]

    async def skill_content(self, skill_name: str) -> str:
        """POST /api/v1/skills  action=skill_content

        Returns
        -------
        str
            Skill 文档的完整 Markdown 内容。
        """
        body = {
            "agent_name": self._agent_name,
            "action": "skill_content",
            "skill_name": skill_name,
        }
        data = await self._request_with_retry("POST", "/api/v1/skills", json=body)
        return data.get("content", "")  # type: ignore[no-any-return]

    async def restore_skill(self, skill_names: list[str]) -> list[dict[str, Any]]:
        """POST /api/v1/skills  action=restore_skill

        将指定 Skill 恢复到优化前的快照内容。批量操作，支持一次恢复多个 Skill。
        幂等：对同一 Skill 多次调用产生相同结果。

        Parameters
        ----------
        skill_names:
            待恢复的 Skill 名称列表。

        Returns
        -------
        list[dict]
            每个 Skill 的恢复结果：
            ``[{"skill_name": "...", "success": true/false, "message": "..."}]``

        Raises
        ------
        AdapterError
            Adapter 返回 HTTP 错误时抛出。
            单个 Skill 恢复失败不抛异常，通过 ``success=false`` 标识。
        """
        body: dict[str, Any] = {
            "agent_name": self._agent_name,
            "action": "restore_skill",
            "skill_names": skill_names,
        }
        data = await self._request_with_retry("POST", "/api/v1/skills", json=body)
        return data.get("restored", [])  # type: ignore[no-any-return]

    # ── 内部方法 ──

    def _handle_response(self, response: httpx.Response) -> dict[str, Any]:
        """统一响应处理：成功返回 JSON body，失败抛出 AdapterError。

        错误格式优先读契约 ``{"error":{"code","message"}}``，
        并兼容旧版 FastAPI ``{"detail": "..."}``。
        """
        if response.status_code >= 400:
            try:
                body = response.json()
                err = body.get("error")
                if isinstance(err, dict) and err.get("message"):
                    message = str(err["message"])
                else:
                    message = body.get("detail", response.text)
            except (ValueError, KeyError, TypeError):
                message = response.text
            raise AdapterError(message, status_code=response.status_code)

        return response.json()  # type: ignore[no-any-return]

    async def _request_with_retry(
        self,
        method: str,
        path: str,
        *,
        json: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """带 retry 的 async 请求。仅对 502/503/网络错误重试。"""
        last_error: Exception | None = None
        for attempt in range(self._max_retries + 1):
            try:
                response = await self._async_client.request(method, path, json=json)
                return self._handle_response(response)
            except AdapterError as e:
                if e.status_code in (502, 503) and attempt < self._max_retries:
                    last_error = e
                    continue
                raise
            except httpx.TransportError as e:
                if attempt < self._max_retries:
                    last_error = e
                    continue
                raise AdapterError(str(e), status_code=0) from e
        raise last_error  # type: ignore[misc]

    # ── 生命周期 ──

    async def close(self) -> None:
        """关闭 async 和 sync HTTP clients。

        容错处理：当 EvoTrainer.evaluate() 在 asyncio.to_thread() 内调用
        asyncio.run() 导致原始 event loop 被关闭时，aclose() 可能抛出
        RuntimeError。此处捕获并忽略，确保清理不阻塞。
        """
        if self._async_http is not None and not self._async_http.is_closed:
            try:
                await self._async_http.aclose()
            except RuntimeError:
                pass  # event loop closed — connections will be GC'd
        self._sync_http.close()

    async def __aenter__(self) -> AdapterClient:
        return self

    async def __aexit__(self, *args: Any) -> None:
        await self.close()
