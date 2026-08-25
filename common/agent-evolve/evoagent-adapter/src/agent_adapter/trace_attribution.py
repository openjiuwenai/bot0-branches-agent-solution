"""轨迹 Skill 归属算法 (纯函数, 无 I/O)。

AttributionRunner 拉一条 trace 的全部 spans + 该 agent 的 skill 文档, 经本模块算出
每个 span 的归属 ``Attribution`` (skill/source/confidence/candidates/misuse),
批量写回 ``spans.attribution`` 列。

归属是 **per-span + trace 级上下文** 的判定 (L1/L2 需 parent 链与时间前缀的 spans),
不能在逐 span 入库时算 —— 故由 AttributionRunner 在 trace 完整后整条算一次。

优先级树 (证据从强到弱, 命中即停; 评审稿 §3.1 + Phase 2 语义模型):
  L0 ingress:           传输层/入口 span (``http.request`` 等, 见 cfg.ingress_span_names)
                        与空名 ``tool.`` 空壳 span → ``ingress`` (非业务行为, confidence=1.0)。
  LS skill_selection:   read_file 读 ``<skill>/SKILL.md`` 的 span 自身 → 归 fallback (Agent.md),
                        candidates 记被读 skill (选型是 agent 规划职责, 不是该 skill 的执行)。
  L1 parent_skill_span: span 的祖先链上有 ``skill.<skill>`` span → 该 skill
                        (业务 agent 自声明, 最可信, confidence=1.0)。
  L2 active_context:    本 span 之前 **已 commit** 的 skill 非空 → 最近 commit 直归
                        (last-wins, confidence=0.8)。commit 规则: 选型 (read_file 命中) 后
                        的执行动作 (非 read_file 的 ``tool.*``) 触发 —— 工具被某候选文档
                        own 认领 → commit 认领者 (区分性动作); 通用动作 → commit 最早读的
                        未转正候选。读后放弃 / 仅审视 (llm/chain) 不激活。
  L3 tool_name_match:   TOOL span 的工具名在 skill 文档 ``own`` 集命中 → 该 skill
                        (prose own/forbid 分类, confidence=0.7); 多 owner 留 candidates。
  L4 residual:          兜底 ``fallback_skill`` (默认 ``Agent.md``, confidence=0.5)。

prose own/forbid 分类 (避免 "禁止使用 X" 反归给该 skill; 评审稿 §3.1.1):
  工具名 t 在 skill S 文本里每次出现按所在句分 own/forbid/neutral 三类;
  全文聚合: 有任一 own 句 → own (own 优先); 否则任一 forbid 句 → forbid; 否则 neutral。
  own 优先于 forbid —— "禁止递归再次调用 X" 这类条件性告诫不应否决 own 句。
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any

from agent_adapter.config import AttributionConfig

# attribution.source 取值 (跨 agent 归一; 契约见 实现计划 §零)
SOURCE_INGRESS = "ingress"
SOURCE_SKILL_SELECTION = "skill_selection"
SOURCE_PARENT_SKILL_SPAN = "parent_skill_span"
SOURCE_ACTIVE_CONTEXT = "active_context"
SOURCE_TOOL_NAME_MATCH = "tool_name_match"
SOURCE_PROSE_MATCH = "prose_match"
SOURCE_RESIDUAL = "residual"

# ingress 归属的 skill 字段值 (传输层/入口, 非业务行为)
INGRESS_SKILL = "ingress"

# 句子分隔符 (中英标点 + 换行); 同句判定 own/forbid 的边界
_SENTENCE_SPLIT = re.compile(r"[。.!！?？\n]+")


@dataclass(frozen=True)
class Attribution:
    """单个 span 的归属结果 (跨 agent 统一形状; to_dict 落 spans.attribution jsonb)。"""

    skill: str
    source: str
    confidence: float
    candidates: list[str] = field(default_factory=list)
    misuse: bool = False

    def to_dict(self) -> dict[str, Any]:
        """转 jsonb 落库形状 (对齐生产方契约)。"""
        return {
            "skill": self.skill,
            "source": self.source,
            "confidence": self.confidence,
            "candidates": list(self.candidates),
            "misuse": self.misuse,
        }


@dataclass(frozen=True)
class SkillToolTable:
    """skill → {owns, forbids} 工具集 (建表时一次性, 按 skill 内容 hash 缓存)。

    L3 tool_name_match 的反查表: TOOL span 的工具名在哪些 skill 的 owns 集命中。
    """

    owns: dict[str, frozenset[str]]
    forbids: dict[str, frozenset[str]]

    @staticmethod
    def build(
        skill_docs: dict[str, str],
        tool_universe: list[str],
        cfg: AttributionConfig,
    ) -> SkillToolTable:
        """从 skill 文档正文 + 工具名全集 建 owns/forbids 表。

        skill_docs: skill 名 → SKILL.md 正文。tool_universe: 待归类工具名 (如 trace 内
        TOOL span 的 name 集合)。prose_matching.enabled=False 时返回空表 (L3 不可用)。
        """
        if not cfg.prose_matching.enabled:
            return SkillToolTable(owns={}, forbids={})
        verbs = cfg.prose_matching.ownership_verbs
        cues = cfg.prose_matching.negation_cues
        owns: dict[str, set[str]] = {}
        forbids: dict[str, set[str]] = {}
        for skill, text in skill_docs.items():
            own_set: set[str] = set()
            forbid_set: set[str] = set()
            for tool in tool_universe:
                if not tool or tool not in text:
                    continue
                classification = SkillToolTable._classify_occurrences(text, tool, verbs, cues)
                if classification == "forbid":
                    forbid_set.add(tool)
                elif classification == "own":
                    own_set.add(tool)
            if own_set:
                owns[skill] = frozenset(own_set)
            if forbid_set:
                forbids[skill] = frozenset(forbid_set)
        return SkillToolTable(owns=owns, forbids=forbids)

    @staticmethod
    def _classify_occurrences(
        text: str,
        tool: str,
        verbs: list[str],
        cues: list[str],
    ) -> str:
        """工具名 tool 在 text 里各出现的 own/forbid/neutral 分类; own 优先于 forbid。

        逐句扫描: 对含 tool 的句, 看 tool 之前文本里有无 negation_cue / ownership_verb:
          有 negation → 该句 forbid (按禁用);
          无 negation 有 verb → 该句 own;
          都无 → neutral。
        全文聚合: 有任一 own 句 → own (own 优先); 否则有 forbid 句 → forbid; 否则 neutral。
        own 优先于 forbid 的理由: "禁止递归再次调用 X" 这类**条件性告诫** (使用约束,
        非真禁用) 不应否决同 skill 对 X 的 own 句 —— 真实数据 multi_skill 文档里
        call_multiagent 10 句 own 曾被 1 句告诫误判 forbid, 致 ATTR101 multi=0。
        """
        sentences = [s for s in _SENTENCE_SPLIT.split(text) if s]
        has_own = False
        has_forbid = False
        for sentence in sentences:
            idx = sentence.find(tool)
            if idx < 0:
                continue
            before = sentence[:idx]
            has_negation = any(cue in before for cue in cues)
            has_verb = any(verb in before for verb in verbs)
            if has_negation:
                has_forbid = True
            elif has_verb:
                has_own = True
        if has_own:
            return "own"
        if has_forbid:
            return "forbid"
        return "neutral"

    def owners_of(self, tool: str) -> list[str]:
        """反查: 哪些 skill 的 owns 集含 tool (L3 用)。"""
        return [skill for skill, tools in self.owns.items() if tool in tools]


class SkillAttributionMapper:
    """逐 trace 跑优先级树, 给每个 span 产 Attribution。

    构造期收 (cfg, skill_names, skill_table) —— 这些 per-agent 全 trace 不变, 一条 trace
    算一次。infer(spans, context_spans) 纯函数 (spans 含 parent_span_id/name/kind/
    attributes/start_time; context_spans 为同 session 前序 trace 的 spans, 供 session 级
    激活携带)。
    """

    def __init__(
        self,
        cfg: AttributionConfig,
        skill_names: set[str],
        skill_table: SkillToolTable,
    ) -> None:
        self._cfg = cfg
        self._skill_names = skill_names
        self._skill_table = skill_table
        self._skill_span_prefix = self._skill_span_prefix(cfg)
        # read_file_skill 识别器: [(tool_name, path_field)] —— 从 read_file 的
        # attributes[path_field] 找 <skill>/SKILL.md 提 skill 名 (选型候选, commit 后喂
        # L2)。path_field 须 per-agent 配(EDPAgent: openjiuwen.agent.inputs); 未配的跳过。
        self._read_file_recognizers: list[tuple[str, str]] = [
            (rec.tool_name or "read_file", rec.path_field)
            for rec in cfg.recognizers
            if rec.kind == "read_file_skill" and rec.path_field
        ]

    @staticmethod
    def _skill_span_prefix(cfg: AttributionConfig) -> str:
        """从 recognizers 取 skill_span 识别器的前缀 (默认 "skill.")。"""
        for rec in cfg.recognizers:
            if rec.kind == "skill_span":
                return rec.span_name_prefix or "skill."
        return "skill."

    def infer(
        self,
        spans: list[dict[str, Any]],
        context_spans: list[dict[str, Any]] | None = None,
    ) -> dict[str, Attribution]:
        """一条 trace 的全部 spans → ``span_id -> Attribution``。

        spans 须按 start_time 升序 (get_spans_by_trace 已排)。逐 span 跑 L0→L4 命中即停。
        context_spans: 同 session 前序 trace 的 spans (session 级激活携带) —— 参与建
        激活时间线但**不产出归属** (只写回本 trace 的 spans)。调用方须按时间截断
        (只给 <= 本 trace 结束的), 避免未来 trace 的激活污染。
        """
        if not spans:
            return {}
        all_spans = sorted(
            [*(context_spans or []), *spans], key=lambda s: s.get("start_time") or ""
        )
        by_id = {s.get("span_id"): s for s in all_spans if s.get("span_id")}
        activations, selections = self._build_activation_timeline(all_spans)
        result: dict[str, Attribution] = {}
        own_ids = {s.get("span_id") for s in spans}
        for span in all_spans:
            sid = span.get("span_id")
            if sid is None or sid not in own_ids:
                continue
            result[sid] = self._infer_one(span, by_id, activations, selections)
        return result

    def _build_activation_timeline(
        self, spans: list[dict[str, Any]]
    ) -> tuple[list[tuple[str, str]], dict[str, str]]:
        """扫 spans 按 start_time, 产 (committed 激活事件, read_file 选型记录)。

        返回:
          - activations: ``[(start_time, skill)]`` committed 激活 (喂 L2 last-wins);
          - selections: ``span_id -> skill`` read_file 选型 (喂 LS 自我归属 + candidates)。

        两类信号:
          - skill_span recognizer: ``skill.<skill>`` span —— 业务 agent 自声明, 直接激活
            (L1 ground truth, 不经 commit)。
          - read_file_skill recognizer: ``tool.read_file`` 读 ``<skill>/SKILL.md`` ——
            **选型候选** (按读序进 pending), 不立即激活; 执行动作 (非 read_file 的非空
            ``tool.*``) 出现时按两级规则 commit 一个候选:
              1. 该工具被某候选 skill 的文档 own 认领 (SkillToolTable, 区分性动作如
                 ask_user→customer_confirm) → commit 认领者 (多个取最早读的),
                 总是允许换岗;
              2. 通用动作 (无人认领, 如 lite_todo_write/call_multiagent) → 只在
                 "没人在岗" 或 "在岗的是认领动作换上的" 时 commit 最早读的未转正候选
                 (接班一次); 通用动作上岗后, 后续通用动作不再换人 (防候选轮流反超,
                 ATTR103 实证)。假设: 读说明书顺序 = 计划使用顺序。
            pending 为空则不产生激活。读后未等到执行动作的候选永不 commit (读了不用
            不错染)。commit 后 last-wins 持续到下次 commit (不去激活)。
        """
        prefix = self._skill_span_prefix
        activations: list[tuple[str, str]] = []
        selections: dict[str, str] = {}
        pending: list[str] = []  # 待 commit 的候选, 按读序
        active_via_owned = False  # 在岗 skill 是否靠文档认领动作换上的
        for span in spans:
            skill = SkillAttributionMapper._skill_from_span_name(span.get("name") or "", prefix)
            if skill is not None and skill in self._skill_names:
                activations.append((span.get("start_time") or "", skill))
                active_via_owned = False
                continue
            read_skill = None
            if self._read_file_recognizers:
                read_skill = SkillAttributionMapper._skill_from_read_file(
                    span, self._read_file_recognizers, self._skill_names
                )
            if read_skill is not None:
                selections[span.get("span_id") or ""] = read_skill
                if read_skill not in pending:
                    pending.append(read_skill)
                continue
            if self._is_execution_span(span):
                active = activations[-1][1] if activations else None
                commit, active_via_owned = self._pick_commit(
                    span, pending, active, active_via_owned
                )
                if commit is not None:
                    activations.append((span.get("start_time") or "", commit))
                    pending.remove(commit)
        return activations, selections

    def _pick_commit(
        self,
        span: dict[str, Any],
        pending: list[str],
        active: str | None,
        active_via_owned: bool,
    ) -> tuple[str | None, bool]:
        """执行动作触发 commit 时选谁。返回 (候选, 新的 via_owned 标记), commit=None 不动。

        规则 (ATTR101/102/103 实证迭代):
          1. 工具被候选队列里的 skill 文档 own 认领 → 扶正认领者, 标记 via_owned
             (区分性动作, 如 ask_user→confirm; 总是允许换岗);
          2. 工具被**当前在岗** skill 认领 (如 sub_skill own call_multiversatile) → 锚定
             不动, 且**消耗掉接班机会** (via_owned 复位) —— 防无关候选被扶正
             (ATTR103 子 trace 实证);
          3. 通用动作 (无人认领) → 没人在岗, 或在岗的是认领换上的 (有一次接班机会),
             才扶正最早读的候选; 通用上岗后不再换人 (防轮流反超)。
        """
        name = span.get("name") or ""
        tool = name.removeprefix("tool.")
        owned = {s for s in self._skill_table.owners_of(tool)}
        for cand in pending:
            if cand in owned:
                return cand, True
        if active is not None and active in owned:
            return None, False  # 锚定在岗 skill, 消耗接班机会
        if pending and (active is None or active_via_owned):
            return pending[0], False
        return None, active_via_owned

    def _is_execution_span(self, span: dict[str, Any]) -> bool:
        """是否"执行动作" span (触发选型 commit): 非空 ``tool.*`` 且非 read_file 识别器工具。"""
        name = span.get("name") or ""
        if not name.startswith("tool."):
            return False
        tool = name.removeprefix("tool.")
        if not tool:
            return False
        return not any(
            name == tool_name or name == f"tool.{tool_name}"
            for tool_name, _ in self._read_file_recognizers
        )

    @staticmethod
    def _skill_from_span_name(name: str, prefix: str) -> str | None:
        """span name 形如 ``skill.product_recommend`` → ``product_recommend``。"""
        if prefix and name.startswith(prefix):
            return name.removeprefix(prefix)
        return None

    @staticmethod
    def _skill_from_read_file(
        span: dict[str, Any],
        recognizers: list[tuple[str, str]],
        skill_names: set[str],
    ) -> str | None:
        """read_file span 的 attributes[path_field] 里找 ``<skill>/SKILL.md`` → skill 名。

        跨 agent 通用: 只匹配 ``<已知skill名>/SKILL.md`` (路径前缀无关, EDPAgent 的
        ``/app/.../skills/<skill>/SKILL.md`` 和别的布局都能认)。path_field 值可能是
        Python repr / JSON / 裸串, 统一 stringify 后 regex 找, 不依赖容器格式。
        """
        name = span.get("name") or ""
        attrs = span.get("attributes") or {}
        for tool_name, path_field in recognizers:
            if name != tool_name and name != f"tool.{tool_name}":
                continue
            val = attrs.get(path_field)
            if val is None:
                continue
            s = val if isinstance(val, str) else json.dumps(val, ensure_ascii=False)
            for m in re.finditer(r"([^/\\]+)/SKILL\.md", s):
                if m.group(1) in skill_names:
                    return m.group(1)
        return None

    def _infer_one(
        self,
        span: dict[str, Any],
        by_id: dict[str, Any],
        activations: list[tuple[str, str]],
        selections: dict[str, str],
    ) -> Attribution:
        """单 span 跑优先级树 L0→L4。"""
        attr = self._match_ingress(span)
        if attr is not None:
            return attr
        attr = self._match_selection(span, selections)
        if attr is not None:
            return attr
        attr = self._match_parent_skill_span(span, by_id)
        if attr is not None:
            return attr
        attr = self._match_active_context(span, activations)
        if attr is not None:
            return attr
        attr = self._match_tool_name(span)
        if attr is not None:
            return attr
        return self._fallback()

    def _match_ingress(self, span: dict[str, Any]) -> Attribution | None:
        """L0: 传输层/入口 span (``http.request`` 等) 与空名 ``tool.`` 空壳 → ingress。

        非业务行为, 主动分类 (confidence=1.0) 而非 L4 兜底 —— 让 "没归上" 与
        "本就不涉及 skill" 在统计里分开。
        """
        name = span.get("name") or ""
        is_ingress = name in self._cfg.ingress_span_names
        if not is_ingress and name.startswith("tool."):
            is_ingress = not name.removeprefix("tool.")  # "tool." 空壳
        if is_ingress:
            return Attribution(skill=INGRESS_SKILL, source=SOURCE_INGRESS, confidence=1.0)
        return None

    def _match_selection(
        self,
        span: dict[str, Any],
        selections: dict[str, str],
    ) -> Attribution | None:
        """LS: read_file 读 SKILL.md 的 span 自身 → 归 fallback (选型是规划职责),
        candidates 记被读 skill。归属切换发生在 commit (见 _build_activation_timeline)。"""
        skill = selections.get(span.get("span_id") or "")
        if skill is None:
            return None
        return Attribution(
            skill=self._cfg.fallback_skill,
            source=SOURCE_SKILL_SELECTION,
            confidence=0.5,
            candidates=[skill],
        )

    def _match_parent_skill_span(
        self,
        span: dict[str, Any],
        by_id: dict[str, Any],
    ) -> Attribution | None:
        """L1: 沿 parent 链回溯, 祖先 (或自身) 命中 skill.<skill> → 该 skill。"""
        prefix = self._skill_span_prefix
        # 自身若是 skill span: 它就是该 skill 的执行 span, 自归
        own_skill = SkillAttributionMapper._skill_from_span_name(span.get("name") or "", prefix)
        if own_skill is not None and own_skill in self._skill_names:
            return Attribution(skill=own_skill, source=SOURCE_PARENT_SKILL_SPAN, confidence=1.0)
        # 沿 parent 链向上找最近的 skill span (包裹关系)
        current = span
        seen: set[str] = set()
        while True:
            parent_id = (current.get("parent_span_id") or "").strip()
            if not parent_id or parent_id in seen:
                return None
            seen.add(parent_id)
            parent = by_id.get(parent_id)
            if parent is None:
                return None
            skill = SkillAttributionMapper._skill_from_span_name(parent.get("name") or "", prefix)
            if skill is not None and skill in self._skill_names:
                return Attribution(skill=skill, source=SOURCE_PARENT_SKILL_SPAN, confidence=1.0)
            current = parent

    @staticmethod
    def _match_active_context(
        span: dict[str, Any],
        activations: list[tuple[str, str]],
    ) -> Attribution | None:
        """L2: 最近一次 **committed** 激活(<= 本 span 时间)的 skill → 该 skill (last-wins)。

        activations 已是 commit 后的 (_build_activation_timeline): read_file 选型不直接进,
        首个执行动作才激活; EDPAgent 顺序读多个 skill 时最近 commit 的 = 当前 skill。
        activations 按 start_time 升序 (含 context_spans 的 session 级携带)。
        """
        t = span.get("start_time") or ""
        recent: str | None = None
        for act_t, skill in activations:
            if act_t <= t:
                recent = skill
        if recent is not None:
            return Attribution(skill=recent, source=SOURCE_ACTIVE_CONTEXT, confidence=0.8)
        return None

    def _match_tool_name(self, span: dict[str, Any]) -> Attribution | None:
        """L3: TOOL span 工具名反查 skill_table.owns。

        单 owner 直归该 skill; 多 owner 置空 skill 留 candidates。
        """
        name = span.get("name") or ""
        if not name.startswith("tool."):
            return None
        tool = name.removeprefix("tool.")
        owners = self._skill_table.owners_of(tool)
        if not owners:
            return None
        if len(owners) == 1:
            return Attribution(skill=owners[0], source=SOURCE_TOOL_NAME_MATCH, confidence=0.7)
        return Attribution(
            skill="",
            source=SOURCE_TOOL_NAME_MATCH,
            confidence=0.5,
            candidates=owners,
        )

    def _fallback(self) -> Attribution:
        """L4: 兜底 fallback_skill (默认 Agent.md)。"""
        return Attribution(
            skill=self._cfg.fallback_skill,
            source=SOURCE_RESIDUAL,
            confidence=0.5,
        )
