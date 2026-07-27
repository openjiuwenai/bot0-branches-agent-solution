"""Python-repr → JSON 规整器 (需求 4 兼容层)。

EDPAgent 上报的 gen_ai.prompt / gen_ai.completion / openjiuwen.agent.outputs 字段值是
Python 对象的 repr (非合法 JSON), 例如:
  - gen_ai.prompt:      {"messages": "[SystemMessage(role='system', content='...'), HumanMessage(role='user', content='...')]"}
  - gen_ai.completion:  {"outputs": "role='assistant' content='' name=None metadata={} tool_calls=[ToolCall(id='...', type='function', name='...', arguments='...')] usage_metadata=UsageMetadata(code=0, ...)"}
  - openjiuwen.agent.outputs: {"outputs": "ExecuteCmdResult(code=0, message='success', data=ExecuteCmdData(command='...'))"}
                        或 {"outputs": "<agents...._ToolOutput object at 0x7cdd1f560450>"}  (不可解析)

parse_repr 把这类字符串解析成原生 Python 类型 (str/int/float/bool/None/list/dict);
解析失败或遇 <...object at...> 时返回原字符串 (决策 E, 不抛)。

需求 4: 携带类名 (SystemMessage / ReadFileResult / ToolCall 等) 的原始字符串信息
提取成 JSON 结构再拼接回原 JSON, 由 spans_to_records 在读取侧调用。
"""

from __future__ import annotations

from typing import Any


class _ReprParseError(Exception):
    pass


# =============================================================================
# tokenizer
# =============================================================================

def _tokenize(s: str):
    """repr 字符串 → token 流。每个 token 为 (kind, value)。"""
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if c in " \t\n,":
            i += 1
            continue
        if c == "'" or c == '"':            # 单/双引号字符串
            quote = c
            j = i + 1
            buf: list[str] = []
            while j < n:
                if s[j] == "\\" and j + 1 < n:
                    nxt = s[j + 1]
                    if nxt == "n":
                        buf.append("\n")
                    elif nxt == "t":
                        buf.append("\t")
                    elif nxt == "r":
                        buf.append("\r")
                    elif nxt == "\\":
                        buf.append("\\")
                    elif nxt == quote:
                        buf.append(quote)
                    elif nxt == '"':
                        buf.append('"')
                    elif nxt == "'":
                        buf.append("'")
                    else:
                        buf.append(nxt)
                    j += 2
                    continue
                if s[j] == quote:
                    break
                buf.append(s[j])
                j += 1
            if j >= n:
                raise _ReprParseError("unterminated string")
            yield ("STR", "".join(buf))
            i = j + 1
            continue
        if c.isdigit() or (c == "-" and i + 1 < n and s[i + 1].isdigit()):
            j = i + 1
            while j < n and (s[j].isdigit() or s[j] == "."):
                j += 1
            tok = s[i:j]
            yield ("NUM", float(tok) if "." in tok else int(tok))
            i = j
            continue
        if c.isalpha() or c == "_":
            j = i + 1
            while j < n and (s[j].isalnum() or s[j] in "_."):
                j += 1
            word = s[i:j]
            if word == "True":
                yield ("BOOL", True)
            elif word == "False":
                yield ("BOOL", False)
            elif word == "None":
                yield ("NONE", None)
            else:
                yield ("IDENT", word)
            i = j
            continue
        if c == "<":                        # <... object at 0x...> 整段当垃圾
            j = s.find(">", i)
            if j == -1:
                raise _ReprParseError("unterminated <")
            yield ("JUNK", s[i:j + 1])
            i = j + 1
            continue
        if c in "()[]{}=:->":              # 单字符 token ('->' 在 list/dict 起始不出现, '->'罕用, 此处忽略)
            yield (c, c)
            i += 1
            continue
        raise _ReprParseError(f"unexpected char {c!r} at {i}")
    yield ("EOF", None)


# =============================================================================
# parser (recursive descent)
# =============================================================================

class _Parser:
    def __init__(self, tokens):
        self.toks = list(tokens)
        self.pos = 0

    def _peek(self, ahead: int = 0):
        idx = self.pos + ahead
        return self.toks[idx] if idx < len(self.toks) else ("EOF", None)

    def _next(self):
        t = self._peek()
        self.pos += 1
        return t

    def _expect(self, kind: str):
        t = self._next()
        if t[0] != kind:
            raise _ReprParseError(f"expected {kind}, got {t}")
        return t

    def parse_value(self) -> Any:
        t = self._peek()
        kind = t[0]
        if kind == "STR":
            self._next()
            return t[1]
        if kind == "NUM":
            self._next()
            return t[1]
        if kind == "BOOL":
            self._next()
            return t[1]
        if kind == "NONE":
            self._next()
            return None
        if kind == "JUNK":
            self._next()
            return t[1]
        if kind == "[":
            return self._parse_list()
        if kind == "{":
            return self._parse_dict()
        if kind == "IDENT":
            nxt = self._peek(1)
            if nxt[0] == "(":
                return self._parse_class_call()
            if nxt[0] == "=":
                return self._parse_bare_fields()
            self._next()
            return t[1]
        raise _ReprParseError(f"unexpected token {t}")

    def _parse_list(self) -> list:
        self._expect("[")
        items: list[Any] = []
        while self._peek()[0] != "]":
            items.append(self.parse_value())
        self._expect("]")
        return items

    def _parse_dict(self) -> dict:
        self._expect("{")
        out: dict[Any, Any] = {}
        while self._peek()[0] != "}":
            key = self.parse_value()
            self._expect(":")  # Python dict repr 用 ':' 分隔 (区别于 class/bare 字段的 '=')
            out[key] = self.parse_value()
        self._expect("}")
        return out

    def _parse_class_call(self) -> dict:
        name = self._expect("IDENT")[1]
        self._expect("(")
        fields: dict[str, Any] = {"_class": name}
        while self._peek()[0] != ")":
            t = self._peek()
            nxt = self._peek(1)
            if t[0] == "IDENT" and nxt[0] == "=":
                fname = self._next()[1]
                self._expect("=")
                fields[fname] = self.parse_value()
            else:
                fields.setdefault("_args", []).append(self.parse_value())
        self._expect(")")
        return fields

    def _parse_bare_fields(self) -> dict:
        out: dict[str, Any] = {}
        while True:
            t = self._peek()
            if t[0] != "IDENT":
                break
            if self._peek(1)[0] != "=":
                break
            fname = self._next()[1]
            self._expect("=")
            out[fname] = self.parse_value()
        if not out:
            raise _ReprParseError("empty bare fields")
        return out


# =============================================================================
# 公开 API
# =============================================================================

def parse_repr(s: Any) -> Any:
    """把 Python repr 字符串解析成原生类型; 非字符串原样返回; 解析失败返回原字符串 (决策 E)。"""
    if not isinstance(s, str):
        return s
    try:
        p = _Parser(_tokenize(s))
        return p.parse_value()
    except (_ReprParseError, IndexError):
        return s


def extract_value(v: Any) -> Any:
    """规整一个字段值: 字符串 → parse_repr (含类名则提取成结构, 不可解析则原样); 已是结构原样返回。"""
    if isinstance(v, str):
        return parse_repr(v)
    return v
