# coding: utf-8

"""框架无关执行输入 ServeRequest（洋葱最内层）。

A2A / HTTP 等协议对象不得穿透至此——inbound adapter 负责去协议化后构造本对象
（L2-overview §2.1 / §3.3 依赖方向）。

权威 7 字段（对齐 version-scope `CL-8da90dd80935`/60 + java `spec/dto/ServeRequest`）：
    conversation_id（兼任 session key）· tenant_id · user_id · space_id
    · messages · stream · metadata

`task` / `agent` / `state_key` / `memory_scope` 属「执行状态作用域」簇，当前**不承载**
（`CL-8da90dd80935`「由后续特性补齐」；本版决定：整簇一起补，不单加半成品字段）。
框架会话标识当前直接用 `conversation_id`（`CL-07ad0ea879f7`；跨租户碰撞的已知缺陷见
Feat-Func-002b §4.3 缺陷登记，正确修法=独立 stateKey 派生，上游未立项）。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Optional

#: 调用方首跳 URL 查询串在 `metadata` 里的键。
#:
#: **两端共用一个常量**：入站写、南向出站读。各写各的字面量时，改一处就断一条链路，
#: 而两边各自看都正常——本仓已因「传 A 落 B」这类不对称踩过。
CALLER_PARAMS_KEY = "caller_params"


@dataclass(frozen=True)
class ServeRequest:
    """一次 Agent 调用的框架中立输入（协议中立执行请求）。

    每请求独立、不共享可变状态（L2-overview §9.2 横切并发不变量）。
    """

    conversation_id: str = ""
    tenant_id: str = ""
    user_id: str = ""
    space_id: str = ""
    messages: list[dict[str, Any]] = field(default_factory=list)
    stream: bool = True
    metadata: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        """构造期把两个可变容器**换成本对象自己的副本**。

        ## 为什么 `frozen=True` 不够

        冻结数据类只挡住「给字段重新赋值」，**不挡容器内改**——实测：

            request = ServeRequest(messages=[...], metadata={"k": 1})
            request.metadata["k"] = 999      # 成功，frozen 拦不住

        更实际的一条是**构造方还握着传进来的那个对象**：
        `ServeRequest(messages=lst)` 之后 `lst.append(...)` 会改到请求里。
        本类的文档与根设计 §9.2 的横切并发不变量都写着「每请求独立、
        **不共享可变状态**」——那句话此前靠约定成立，而约定挡不住上面两条路径。

        ## 与 `ports/secret.py` 是同一手法

        那里的判词是「原先靠字段注释约束调用方，**约定挡不住四条真实路径**，
        故把掩码做进类型」。这里同理：把「不共享」做进构造，不留给调用方自觉。

        ## 为什么不换成不可变容器

        换成元组与只读映射能连「拿到之后再改」也挡掉，但那会**改变对外的类型契约**——
        消费方与既有判据都按 `list` / `dict` 写。当前没有任何消费方就地改这两个容器，
        构造期切断共享已消除全部**实际**的共享路径；深冻属更强的形态，
        要改类型契约时再一并做。
        """
        object.__setattr__(self, "messages", list(self.messages))
        object.__setattr__(self, "metadata", dict(self.metadata))

    # ── 派生便利（不新增字段，只读投影）────────────────────────────────
    def _last_message_with_content(self) -> Optional[dict[str, Any]]:
        """本轮主消息的选择器。语义逐步照上游 `lastMessageWithContent()`
        （`openJiuwen/agent-runtime-java/service/agent-service-spec/src/main/java/
        com/openjiuwen/service/spec/dto/ServeRequest.java` 的 `lastMessageWithContent`）。

        **三步，顺序不可换**：

        1. 倒序找 `role` 非空、等于 `user`（忽略大小写）且 `content` 非 `None` 的第一条
        2. 找不到才回退——**只看最后一条**，且仅当它的 `content` 非 `None`
        3. 再不成返回 `None`

        ## 此前三处与上游不同，每一处都改变取值

        | 我方原实现 | 上游 | 差异何时显现 |
        |---|---|---|
        | **不看 `role`** | 先按 `user` 过滤 | messages 带对话历史且最后一条是 assistant 时，取到智能体自己上一轮的回复 |
        | 判 `if content`（空串跳过） | 判 `content != null` | 最后一条 user 消息内容为空串时，我方继续往前找、上游就取它 |
        | 回退时倒序找**第一条有内容的** | 回退**只看最后一条** | 无任何 user 消息且末条无内容时，我方仍会往前捞 |

        第一处后果最重：`adapters/outbound/agentcore/handler.py` 用本方法的返回值组装喂给
        框架的 `query`——取错即把智能体自己上一轮的回复当成用户本轮输入送回框架。
        另有三处把它用作续接的 `user_supplement`（两条 REST 路径与 A2A 执行器）。

        **本方法是两个公开投影的唯一选择器**，与上游同构——上游的
        `lastUserQuery()` 与 `lastUserMessageMetadata()` 同样都走它。
        此前我方两个方法各自倒序扫描、判据还不同（一个找有内容的、一个找带映射
        metadata 的），于是**同一次调用里正文与元数据可能来自两条不同的消息**。
        """
        for message in reversed(self.messages):
            if not isinstance(message, dict):
                continue
            role = message.get("role")
            content = message.get("content")
            if role is not None and str(role).lower() == "user" and content is not None:
                return message
        if self.messages:
            last = self.messages[-1]
            if isinstance(last, dict) and last.get("content") is not None:
                return last
        return None

    def last_user_query(self) -> str:
        """当前主输入文本（对齐 java `lastUserQuery()`）。

        adapter 以此取「本轮用户输入」，避免各处自行解析 messages。
        选择规则见 `_last_message_with_content`。
        """
        message = self._last_message_with_content()
        return "" if message is None else str(message.get("content"))

    def last_user_message_metadata(self) -> dict[str, Any]:
        """`last_user_query()` 选中的**那一条**消息的 metadata（对齐 java
        `lastUserMessageMetadata()`）。

        **返回防御性副本、且只保留字符串键**——与上游同（其实现构造新的
        `LinkedHashMap` 并过滤非 String 键）。返回原字典的引用会让调用方
        改一下就污染了请求对象，而本类是冻结数据类，不可变是它的承诺。
        """
        message = self._last_message_with_content()
        if message is None:
            return {}
        raw = message.get("metadata")
        if not isinstance(raw, dict):
            return {}
        return {key: value for key, value in raw.items() if isinstance(key, str)}

    # ── 便利构造（inbound adapter 去协议化用）──────────────────────────
    #: 任务标识在 `metadata` 中的键。协议侧已有该标识，去协议化时以**关联事实**的形态
    #: 透传（与 `agent` 同构），不作为「状态作用域」簇的 `task` 字段提前落地——
    #: 那一簇要整簇补齐、不单加半成品字段（FEAT-002 §2 能力表）。
    TASK_ID_META_KEY = "task_id"
    #: 层级路径在 `metadata` 中的键。**其长度即本节点的当前调用深度**
    #: （Feat-Func-004b §6.3.1.1）——深度不是运行时自己维护的计数器，
    #: 而是每一跳从入站报文的会话上下文读出来的。
    SUB_TASK_PATH_META_KEY = "sub_task_path"
    #: 入站原始请求体在 `metadata` 中的键。存量把它原样交给宿主 Agent
    #: （`context["body"]`），宿主 Agent 按自己的字段约定从中取业务载荷；本版同样以
    #: 关联事实的形态透传（FEAT-002 §2「metadata 透传 Map」），领域层不解释其内容。
    REQUEST_BODY_META_KEY = "request_body"

    # ── 续接投影（续接不新增 SPI 方法，走 stream_query）────────────
    RESUME_META_KEY = "_resume"

    def with_task_id(self, task_id: str) -> "ServeRequest":
        """派生一份带任务标识的请求。标识为空时返回本对象，不做无谓的复制。

        ## 为什么是派生而不是就地写

        本类 `frozen=True`，且「每请求独立、不共享可变状态」是横切并发不变量
        （L2-overview §9.2）。就地改 `metadata` 会让同一个字典被两条执行流看见。

        ## 谁用它

        入站适配层在**本轮 Task 落定之后**调用：自定义 REST 入口的 Task 由会话绑定件
        在 `begin_turn` 里建立或复用，而执行上下文在那之前就构造好了。协议入口那一侧
        不需要它——`protocol_adapter` 在去协议化时标识就已在手
        （`adapters/inbound/a2a/protocol_adapter.py` 的 `to_execution_context`）。

        没有它时，这条入口的 `task_id` 恒空，远端委派的
        `agentEvent.source.taskId`（权威 `CL-e49b448ba3bb`）无从取值，
        整条投射轨在私有出口上一路建不出来。
        """
        if not task_id:
            return self
        meta = dict(self.metadata)
        meta[self.TASK_ID_META_KEY] = task_id
        return ServeRequest(
            conversation_id=self.conversation_id,
            tenant_id=self.tenant_id,
            user_id=self.user_id,
            space_id=self.space_id,
            messages=self.messages,
            stream=self.stream,
            metadata=meta,
        )

    def for_resume(
        self,
        *,
        user_supplement: str = "",
        recovery_point_id: str = "",
        keyed_results: dict | None = None,
    ) -> "ServeRequest":
        """派生**续接请求**：把续接输入投影进 metadata，重走 `stream_query`。

        复用 `ServeRequest`、不新增 `AgentHandler.resume` SPI（上位规格 FEAT-008 §6
        明禁在该特性名义下新增或修改 SPI）。批量回灌形态对齐上游
        `A2AEnabledServeOrchestrator.buildBatchResumeRequest`。
        adapter 在 `stream_query` 内识别本标记，译为框架原生续接输入
        （agent-core `InteractiveInput`：有 recovery_point_id 走 user_inputs[id]，
        否则 raw_inputs）。
        """
        meta = dict(self.metadata)
        meta[self.RESUME_META_KEY] = {
            "user_supplement": user_supplement,
            "recovery_point_id": recovery_point_id,
            # 多键回灌（FEAT-004 §4.3 单次批量回灌）：全批一个映射，一次恢复
            "keyed_results": dict(keyed_results or {}),
        }
        # 无补充文本时此处写作 `messages = self.messages`，传出去的是同一个列表对象——
        # **但派生出的请求不会与本请求共享它**：`__post_init__` 在构造期复制两个可变容器，
        # 那是本类「不共享可变状态」的**唯一一道防线**。
        #
        # 此处曾加过一次 `list(...)` 作第二道。变异验证读出它是冗余的——把它删掉时
        # 判据一条不红，因为构造期那道已经兜住。**冗余的防线比没有防线更坏**：
        # 它让读代码的人以为这里承重，于是构造期那道被改掉时没人觉得有风险。
        messages = self.messages
        if user_supplement:
            messages = [*self.messages, {"role": "user", "content": user_supplement}]
        return ServeRequest(
            conversation_id=self.conversation_id,
            tenant_id=self.tenant_id,
            user_id=self.user_id,
            space_id=self.space_id,
            messages=messages,
            stream=self.stream,
            metadata=meta,
        )

    @property
    def task_id(self) -> str:
        """本次请求关联的任务标识；未关联任务时为空串。

        远端委派批次据此定位承载批次快照的影子任务（Feat-Func-004b §5.1）。
        取自 `metadata`，不是本对象的字段——见 `TASK_ID_META_KEY` 的说明。
        """
        value = self.metadata.get(self.TASK_ID_META_KEY, "")
        return str(value) if value else ""

    @property
    def sub_task_path(self) -> tuple[str, ...]:
        """本节点的入站层级路径；未携带时为空元组。

        取自 `metadata`，与任务标识同构——它是协议侧带来的关联事实，
        不是「状态作用域」簇的字段（那一簇要整簇补齐，见 `TASK_ID_META_KEY` 的说明）。

        **取不到按首跳处理，不阻断**：路径缺失既可能是纯本版部署（无人写入），
        也可能确实是首跳。两者都不该让调用失败。
        """
        raw = self.metadata.get(self.SUB_TASK_PATH_META_KEY)
        if isinstance(raw, (list, tuple)):
            return tuple(str(p) for p in raw)
        return ()

    @property
    def request_body(self) -> dict:
        """入站原始请求体；未携带时为空映射。

        与 `sub_task_path` 同构：协议侧带来的关联事实，不是状态字段。取不到不阻断——
        纯本版部署里没有人写它，宿主 Agent 按空处理。
        """
        raw = self.metadata.get(self.REQUEST_BODY_META_KEY)
        return dict(raw) if isinstance(raw, dict) else {}

    @property
    def is_resume(self) -> bool:
        """是否续接请求（adapter 据此走框架原生续接路径）。"""
        return isinstance(self.metadata.get(self.RESUME_META_KEY), dict)

    @property
    def resume_keyed_results(self) -> dict:
        """批量回灌映射（远端委派批次结果）；空=非批次续接。"""
        r = self.metadata.get(self.RESUME_META_KEY)
        value = r.get("keyed_results") if isinstance(r, dict) else None
        return dict(value) if isinstance(value, dict) else {}

    @property
    def resume_user_supplement(self) -> str:
        r = self.metadata.get(self.RESUME_META_KEY)
        return str(r.get("user_supplement", "")) if isinstance(r, dict) else ""

    @property
    def resume_recovery_point_id(self) -> str:
        r = self.metadata.get(self.RESUME_META_KEY)
        return str(r.get("recovery_point_id", "")) if isinstance(r, dict) else ""

    @classmethod
    def of_text(
        cls,
        text: str,
        *,
        conversation_id: str = "",
        tenant_id: str = "",
        user_id: str = "",
        space_id: str = "",
        stream: bool = True,
        metadata: dict[str, Any] | None = None,
    ) -> "ServeRequest":
        """单条文本输入 → ServeRequest（messages 归一为 [{role:user, content:text}]）。"""
        return cls(
            conversation_id=conversation_id,
            tenant_id=tenant_id,
            user_id=user_id,
            space_id=space_id,
            messages=[{"role": "user", "content": text}] if text else [],
            stream=stream,
            metadata=dict(metadata) if metadata else {},
        )
