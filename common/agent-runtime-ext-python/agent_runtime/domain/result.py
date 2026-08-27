# coding: utf-8
# 超长行全在注释与文档串：权威路径必须连写才可复制跳转，Markdown 表格断行即损坏。
# 对齐上游 checkstyle 对 Javadoc 续行的同类排除；代码行宽由 ruff formatter 保证。
# pylint: disable=line-too-long


"""领域结果表面 QueryResponse / QueryChunk（洋葱最内层，零框架依赖）。

adapter 把各框架的原生输出归一为本模型，application/inbound 只认本模型，不认
任何框架私有类型（依赖倒置：外层依赖内层抽象）。

权威（L2-overview §2.1 · Feat-Func-002b §2.4 · java `spec/dto`）：
- `QueryChunk{type, data}` —— **仅两字段**；
  `type ∈ {chunk, interrupt, error, remote_agent_output}` 为 **String 常量**
  （非 enum，对齐 java `TYPE_CHUNK/TYPE_INTERRUPT/TYPE_REMOTE_AGENT_OUTPUT/TYPE_ERROR`，
  见 `openJiuwen/agent-runtime-java/service/agent-service-spec/src/main/java/
  com/openjiuwen/service/spec/dto/QueryChunk.java` 的 `QueryChunk`）。
  **此前本文档与下方常量都只写三值，并把「三值」当成红线全仓传播**——而我方根设计
  `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/L2-overview.md` 的 `§2.1` 早已把第四项登记为 active，同节明写
  「该取值不得删除」。缺它意味着本模型无法表达上游能表达的一类事实
  （远端智能体的业务输出及其来源出处）。
- `QueryResponse{result, conversation_id}` —— 非流式 query 的聚合响应。
- **成功完成不是 chunk 类型，而是流正常结束**（`StopAsyncIteration` ≡ java
  `onComplete()`）——不得臆造 `COMPLETED` 类型（002 §2.4 红线）。终答作为
  **内容 chunk** 投递后结束（002 §4.1；用终止标记吞掉终答是部署级 E2E 抓出过的
  线级缺陷）。
- **对外信封兼容锚**：QueryChunk 不承载存量信封字段；存量信封
  （`event_type`/`content`/`plugin`/`data`）由 access-layer 的 wire 投影从
  `data` 载荷重建（002 §2.4）。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Optional

from agent_runtime.domain.remote.delegation import RemoteDelegation


@dataclass(frozen=True)
class QueryResponse:
    """非流式 query 的聚合响应（对齐 java `spec/dto/QueryResponse`）。"""

    result: Any = None
    conversation_id: str = ""


@dataclass(frozen=True)
class QueryChunk:
    """流式增量帧：仅 `{type, data}`（对齐 java `spec/dto/QueryChunk`）。

    - `chunk`：原生增量输出（含**终答**内容帧）；
    - `interrupt`：需用户输入 / 远端继续（→ Task INPUT_REQUIRED）；
    - `error`：异常 / 不可恢复（→ Task FAILED）；
    - `remote_agent_output`：远端智能体的业务输出，携带来源出处。**状态语义与
      `chunk` 相同**——根设计 `L2-overview.md` 的 `§2.1` 的第 1 条附带约束：实测上游流式分支
      只对 `error` 与 `interrupt` 开特殊处理，其余类型走同一兜底路径、保持 WORKING
      （`openJiuwen/agent-runtime-java/service/agent-service-app/src/main/java/
      com/openjiuwen/service/app/controller/a2a/A2AAgentExecutor.java` 的 `handleStreamingChunk`、
      同文 `streamChunkFailure`）。

    载荷一律进 `data`：内容帧放 `event_type`/`content`/`plugin`/`data` 等存量
    信封重建所需字段；错误帧放 `message`/`code`/`kind`；中断帧放 `content`/
    `interaction_id`。顶层不新增字段（兼容锚）。
    """

    TYPE_CHUNK = "chunk"
    TYPE_INTERRUPT = "interrupt"
    #: 远端智能体的业务输出（上游 `QueryChunk.java` 的 `TYPE_REMOTE_AGENT_OUTPUT`）。
    #: 载荷两段：`content` 为业务输出正文，`projection` 携带批次标识、委托关联键与目标名。
    TYPE_REMOTE_AGENT_OUTPUT = "remote_agent_output"
    TYPE_ERROR = "error"

    #: 终态语义的两个事件名（Feat-Func-002b §4.4）。**终答与完成信号是两种块**，靠事件名
    #: 区分——与上游同法（Java 在消费端按信封类型判终答，`QueryChunk` 无终答字段）、
    #: 与存量同名（存量的终答事件是 `final_answer_chunk`、完成信号是 `completed`）。
    #: 翻译件与投影件都从这里取，不各自写死。
    EVENT_FINAL_ANSWER_CHUNK = "final_answer_chunk"
    EVENT_COMPLETED = "completed"

    type: str = TYPE_CHUNK
    data: dict[str, Any] = field(default_factory=dict)

    # ── 构造器（意图清晰、防错配 type 与载荷）──────────────────────────
    @classmethod
    def of_chunk(cls, data: dict[str, Any] | None = None) -> "QueryChunk":
        """原生增量输出帧。data 承载存量信封重建所需字段。"""
        return cls(type=cls.TYPE_CHUNK, data=dict(data or {}))

    @classmethod
    def of_event(
        cls,
        event_type: str,
        *,
        content: str = "",
        data: dict[str, Any] | None = None,
        plugin: str = "",
    ) -> "QueryChunk":
        """语义事件内容帧（存量 `format_event` 信封的上游）。

        `event_type` 是 Agent 原生语义事件名（thought / tool_start /
        final_answer_chunk 等），由 adapter 内部翻译件从框架原生流归一而来；
        access-layer channel 据此投影为对外 wire 形态。
        """
        payload: dict[str, Any] = {"event_type": event_type, "content": content, "plugin": plugin}
        if data:
            payload["data"] = dict(data)
        return cls(type=cls.TYPE_CHUNK, data=payload)

    @classmethod
    def of_final_answer(
        cls, content: str = "", *, event_type: str = EVENT_FINAL_ANSWER_CHUNK
    ) -> "QueryChunk":
        """**终答块**：普通内容块，事件名默认为存量的 `final_answer_chunk`。

        默认事件名是终答（`EVENT_FINAL_ANSWER_CHUNK`）：调用方绝大多数就是在说「这是最终答案」
        ——参考宿主、E2E 服务端、判据替身皆然。**完成信号必须显式传 `event_type=EVENT_COMPLETED`**
        （宿主 completed 事件、远端 COMPLETED 状态正文、终态查询回落这四处）。
        旧默认名 `final_answer` 两个新谓词都不认，留着它等于给每个漏改的调用点埋一个
        「A2A 完成态突然没正文」的坑——2026-08-25 切 A2A 消费方时实测踩到。

        002 §4.1 红线：终答必须作为内容 chunk 投递，**不得**被终止标记吞掉；
        完成语义由「流正常结束」表达，而非某种 COMPLETED 帧。
        """
        return cls(
            type=cls.TYPE_CHUNK,
            data={"event_type": event_type, "content": content, "plugin": ""},
        )

    @classmethod
    def of_completion(cls, content: str = "") -> "QueryChunk":
        """**完成信号**：宿主 `completed` 事件、远端 COMPLETED 状态正文、终态查询回落。

        它是普通内容块（事件名 `completed`），不是终态——完成仍由流正常结束表达
        （`CL-2d5412e99179`：COMPLETED 不在 `QueryChunk` 数据模型中）。对外：REST 流式不出帧
        （存量 `completed → return None`），阻塞式与 A2A 只把正文当最低优先级回落。
        """
        return cls(
            type=cls.TYPE_CHUNK,
            data={"event_type": cls.EVENT_COMPLETED, "content": content, "plugin": ""},
        )

    @classmethod
    def of_interrupt(
        cls, *, content: str = "", interaction_id: str = "",
        delegation: Optional[RemoteDelegation] = None,
    ) -> "QueryChunk":
        """交互式中断帧（→ Task INPUT_REQUIRED）；runtime 不解释业务语义（FEAT-008）。

        `interaction_id`：框架续跑锚点（agent-core `InteractionOutput.id`）——续接时作
        `ResumeInput.recovery_point_id` 交回；空=非 id 绑定（raw_inputs 路径）。

        `delegation`：远端委派载荷（FEAT-004 §4.2）。**仍是 interrupt 帧**——编排层据此
        把委派拦下自己处理，不投影给用户；纯用户交互中断此字段为空，照常上线。

        **不要把这一句读成「类型集合只有三值」**：此处说的是「委派载荷走 interrupt，
        不为它另立类型」，与 `remote_agent_output` 是两件事——后者承载的是远端智能体
        **执行中产出的业务输出**，不是「请求发起一次委派」这个信号。此前这行写作
        「不新增第四种类型（三值是红线）」，把一条局部约束升格成了全局红线，
        并与根设计 `L2-overview.md` 的 `§2.1` 登记的四值集合直接对立。
        """
        # 值类型有两种（文本与委派载荷），不标注时静态检查按首次赋值锁成字符串字典。
        data: dict[str, Any] = {"content": content, "interaction_id": interaction_id}
        if delegation is not None:
            data["delegation"] = delegation
        return cls(type=cls.TYPE_INTERRUPT, data=data)

    @property
    def delegation(self) -> Optional[RemoteDelegation]:
        """远端委派载荷；非委派中断为 ``None``（编排层据此分流）。"""
        return self.data.get("delegation")

    @classmethod
    def of_remote_agent_output(
        cls,
        content: Any,
        *,
        batch_id: str = "",
        tool_call_id: str = "",
        target: str = "",
        source: dict[str, Any] | None = None,
        sub_task_path: tuple[str, ...] | list[str] = (),
        agent_event: dict[str, Any] | None = None,
        artifact_id: str = "",
    ) -> "QueryChunk":
        """远端智能体的业务输出帧（上游 `TYPE_REMOTE_AGENT_OUTPUT`）。

        参数 agent_event：FEAT-027 的生产者标签，记「这一帧由哪个下游产出、
            经了哪些跳」。成员帧上的标签由投射轨贴，编排层重建对外帧时
            不透传就在那一跳丢掉——对外表现是「帧到了，只是看不出是谁产的」。
        参数 artifact_id：投射轨改写过的产物标识，多跳链路上上游凭它把这一帧
            对回下游那次产出。

        **两者并在载荷顶层而非塞进 `source`**：`source` 是下游原始帧的完整副本，
        往里塞我方加的键会让「原始帧」这个语义失真。

        **用具名参数而非一个泛用的附加字典**：后者什么键都能塞，
        调用方拼错键名不会有任何东西报警。

        **载荷形态照上游产出点**（`openJiuwen/agent-runtime-java/service/agent-service-app/
        src/main/java/com/openjiuwen/service/app/orchestrator/RemoteInvocationBatchCoordinator.java` 的 `MemberEventObserver`
        的 `forwardRemoteOutput`）：`data` 两段——`content` 是业务输出正文，
        `projection` 是来源出处，含 `kind` / `batchId` / `toolCallId` / `target` 四键。

        **内部取值对齐上游、对外形态对齐存量，二者分开判定**（根设计
        `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/L2-overview.md` 的 `§2.1` 的第 2 条附带约束）：上游把
        `projection` 挂进片段元数据（`ChunkMapper.java` 的 `ChunkMapper`），而存量对远端进度
        走它自己的归一事件体系（`applications/a2a_service/orchestrator/handlers/
        remote_agent_handler.py` 的 `sub_task` 事件）。**本工厂只定领域载荷，
        不定对外投射**——投射由各入站适配器按存量形态各自决定。

        **与「完成」区分**：成功由流正常结束表达，不存在「完成」类型的结果块
        （根设计同处第 3 条附带约束）。

        ## `source` 与 `sub_task_path`：领域层持完整语义，投射由适配层各自决定

        上游的产出点只放 `content` 与 `projection` 两段——**它的对外出口不需要更多**
        （`ChunkMapper.java` 的 `ChunkMapper` 只读这两键，把 `projection` 挂进片段元数据）。
        而存量对外的 `sub_task` 帧把**远端原始帧整个**作为内层载荷，带着事件名、
        其余键、插件位（`applications/a2a_service/orchestrator/handlers/
        remote_agent_handler.py` 的 `_run_one_sub_agent` 逐帧转发 `frame`；内层字段的读法见
        `.legacy-oracle/applications/a2a_service/channels/mobile_bank_channel.py` 的 `_extract_inner_meta`），
        路径则是「父路径 + 本跳标识」（`openJiuwen/agent-runtime/applications/a2a_service/orchestrator/handlers/remote_agent_handler.py` 的 `_emit_sub_task`）。

        两侧要的东西不同，而**领域层可以同时承载**——按根设计
        `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/L2-overview.md` 的 `§1.1` 的冲突判定第 3 项，
        「领域层可持有完整语义、由适配层按出口分别投射」者不构成原则冲突。
        故此处收下这两项，A2A 出口照上游只投 `content` + `projection`，
        自定义 REST 出口照存量投完整内层。

        **它们不改上游那两键的形态**：上游消费方只读 `content` 与 `projection`，
        多出的键被忽略。丢掉它们才是有损——远端帧的事件名与其余载荷
        在 `adapters/outbound/remote/client.py` 已经解析出来，
        不带进来就只能在投射时落到兜底值。
        """
        projection = {
            "kind": cls.TYPE_REMOTE_AGENT_OUTPUT,
            "batchId": batch_id,
            "toolCallId": tool_call_id,
            "target": target,
        }
        payload: dict[str, Any] = {
            "content": content if content is not None else "",
            "projection": projection,
        }
        if source:
            payload["source"] = dict(source)
        if sub_task_path:
            payload["sub_task_path"] = [str(p) for p in sub_task_path]
        if agent_event:
            payload["agentEvent"] = agent_event
        if artifact_id:
            payload["artifactId"] = artifact_id
        return cls(type=cls.TYPE_REMOTE_AGENT_OUTPUT, data=payload)

    @classmethod
    def of_error(cls, message: str, *, code: str = "", kind: str = "") -> "QueryChunk":
        """错误帧（→ Task FAILED）。`code` 保留框架/远端原生错误码（002 §2.1）。"""
        payload: dict[str, Any] = {"message": message, "code": code}
        if kind:
            payload["kind"] = kind
        return cls(type=cls.TYPE_ERROR, data=payload)

    # ── 只读投影（消费侧便利，不新增字段）──────────────────────────────
    @property
    def is_final_answer(self) -> bool:
        """`is_answer` 的旧名，**同义**。

        此前它读一个 `final` 标记，而那个标记同时盖着完成信号与终答内容——投影层区分不开，
        只能一刀切吞掉（社区 issue #151）。标记已删；完成信号现在**不再**算终答。
        旧名保留只为不动 15 份冻结的差分判据，语义与 `is_answer` 完全相同。
        """
        return self.is_answer

    @property
    def is_answer(self) -> bool:
        """终答块：有正文的最终回答，对外出帧（REST 流式走兜底、A2A 作带终态标记的 artifact）。"""
        return self.type == self.TYPE_CHUNK and self.event_type == self.EVENT_FINAL_ANSWER_CHUNK

    @property
    def is_completion(self) -> bool:
        """完成信号：宿主 `completed` 事件或远端 COMPLETED 状态正文，对外不出帧，正文只作回落。"""
        return self.type == self.TYPE_CHUNK and self.event_type == self.EVENT_COMPLETED

    @property
    def event_type(self) -> str:
        return str(self.data.get("event_type", "")) if self.type == self.TYPE_CHUNK else ""

    @property
    def content(self) -> str:
        return str(self.data.get("content", ""))

    @property
    def plugin(self) -> str:
        return str(self.data.get("plugin", ""))

    @property
    def message(self) -> str:
        """错误帧诊断消息（type=error 时有效）。"""
        return str(self.data.get("message", "")) if self.type == self.TYPE_ERROR else ""

    @property
    def code(self) -> str:
        """错误帧原生错误码（type=error 时有效）。"""
        return str(self.data.get("code", "")) if self.type == self.TYPE_ERROR else ""

    @property
    def interaction_id(self) -> str:
        """中断帧续跑锚点（type=interrupt 时有效）。"""
        return str(self.data.get("interaction_id", "")) if self.type == self.TYPE_INTERRUPT else ""
