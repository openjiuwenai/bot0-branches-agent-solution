#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Agent Studio 一键导入脚本
=========================
把历史工程里的预置数据
(插件 marketplace / 智能体模板 / 工作流模板) 转换为当前工程
(Agent Studio, Java/Spring) 的 JSONL 格式，并通过导入 API 落库。

用法:
    # 完整导入全部预置数据
    python import-presets.py

    # 仅导入少量样本验证(1个插件 + 1个agent + 1个workflow)
    python import-presets.py --sample

    # 仅导入某一类
    python import-presets.py --only plugins
    python import-presets.py --only agents
    python import-presets.py --only workflows

    # 指定模型配置(填好 models.json 后)
    python import-presets.py --models models.json

配置: BASE_URL / AUTH_TOKEN / PROJECT_ID 读取自同目录
      agent-studio.env (与 start-all.ps1 共用)，缺失则用默认值。
      预置数据源默认用工程内打包的 preset-data/ 目录(自包含)，
      可用 PLUGINS_DIR / EXAMPLES_ZH 覆盖指向其他目录。
前置条件: studio-manager (31111) 已启动。模型数据无法从历史工程自动
提取(API key 加密、旧 DB 不存在)；如需让 agent/workflow 绑定真实模型，
请编辑同目录 models.json 填入 API key/endpoint，本脚本会据此创建模型
服务并把 model_deployment_id 回填到 agent/workflow。
"""
import json
import os
import sys
import re
import uuid
import argparse
import time
from pathlib import Path

try:
    import requests
except ImportError:
    print("[FATAL] 缺少 requests 库，请用 agent-runtime 的 venv 运行:\n"
          r"  ~\agent-runtime\.venv\Scripts\python.exe import-presets.py")
    sys.exit(2)

# ============================== 配置 ==============================
# 优先读取同目录 agent-studio.env (与 start-all.ps1 共用)，缺失则用下方默认值

def _load_env(path):
    cfg = {}
    if not os.path.exists(path):
        return cfg
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                continue
            k, _, v = line.partition("=")
            k, v = k.strip(), v.strip()
            if len(v) >= 2 and v[0] == v[-1] and v[0] in ('"', "'"):
                v = v[1:-1]
            # 展开 %VAR%
            for ev in re.findall(r"%(\w+)%", v):
                v = v.replace("%" + ev + "%", os.environ.get(ev, ""))
            cfg[k] = v
    return cfg

# 优先读同目录 importer.env(独立模式)，回退 agent-studio.env(工程内模式)
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_CFG = _load_env(os.path.join(_SCRIPT_DIR, "importer.env"))
if not _CFG:
    _CFG = _load_env(os.path.join(_SCRIPT_DIR, "agent-studio.env"))

def _cfg(key, default):
    return _CFG.get(key, default)

MANAGER_PORT = _cfg("MANAGER_PORT", "31111")
BASE_URL   = _cfg("BASE_URL", f"http://127.0.0.1:{MANAGER_PORT}")
TOKEN      = _cfg("AUTH_TOKEN", " ")          # 本地 simple 模式鉴权 token
PROJECT_ID = _cfg("PROJECT_ID", " ")
# DB 配置 (app-library 直连写 t_app 用)
DB_HOST = _cfg("DB_HOST", "127.0.0.1")
DB_PORT = int(_cfg("DB_PORT", "3306"))
DB_USER = _cfg("DB_USER", " ")
DB_PASSWORD = _cfg("DB_PASSWORD", " ")
DB_NAME = _cfg("DB_NAME", "agent-builder")
# MinIO/OBS 配置（改 agent IR/DSL 用）
OBS_HOST = _cfg("OBS_HOST", "127.0.0.1")
OBS_PORT = int(_cfg("OBS_PORT", "9000"))
MINIO_AK = _cfg("MINIO_AK", " ")
MINIO_SK = _cfg("MINIO_SK", " ")
MINIO_BUCKET = _cfg("MINIO_BUCKET", "agent-builder")
REDIS_HOST = _cfg("REDIS_HOST", "127.0.0.1")
REDIS_PORT = _cfg("REDIS_PORT", "6379")
SCRIPT_DIR = Path(__file__).resolve().parent
WORK_DIR   = SCRIPT_DIR / ".preset-import-tmp"

# 预置数据源：默认用工程内打包的 preset-data（自包含，不依赖外部路径）；
# 可在 agent-studio.env 用 PLUGINS_DIR / EXAMPLES_ZH 覆盖指向其他目录。
PRESET_DATA = SCRIPT_DIR / "preset-data"
PLUGINS_DIR = _cfg("PLUGINS_DIR", str(PRESET_DATA / "plugins"))
EXAMPLES_ZH = _cfg("EXAMPLES_ZH", str(PRESET_DATA / "examples" / "zh"))
# 预置插件的 project_id：需匹配 manager 的 opSvcProjectId（OFFICIAL 标签查 WHERE project_id = opSvcProjectId）
# 本地(OP_SVC_PROJECT_ID=0)：manager 的 opSvcProjectId=0，project_id 设为 '0'
# 远程(未设 op_svc_project_id)：manager 的 opSvcProjectId=''，需在 env 设 OP_SVC_PROJECT_ID= (空)
OP_SVC_PROJECT_ID = _cfg("OP_SVC_PROJECT_ID", "")

# 一个 1x1 占位 PNG(避免 base64 大图标)，导入后在 UI 里改
DEFAULT_ICON = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC"

NS = uuid.UUID("a3f2c1b0-0000-0000-0000-000000000001")  # 稳定命名空间，使同名预置每次生成同一 id

# ============================== HTTP ==============================
class Api:
    def __init__(self):
        self.base = BASE_URL
        self.h = {"X-Auth-Token": TOKEN}
        self._ws = None

    def _post(self, path, params=None, files=None, json_body=None, timeout=120):
        url = self.base + path
        for attempt in range(3):
            try:
                if files is not None:
                    r = requests.post(url, headers=self.h, params=params, files=files, timeout=timeout)
                else:
                    r = requests.post(url, headers=self.h, params=params, json=json_body, timeout=timeout)
                return r
            except requests.RequestException as e:
                if attempt == 2:
                    raise
                time.sleep(2)

    def workspace_id(self):
        if self._ws:
            return self._ws
        r = self._post(f"/v1/{PROJECT_ID}/agent-manager/workspace/init", timeout=60)
        data = r.json()
        if r.status_code >= 300 or not data.get("workspaceList"):
            # 可能已存在，查一次
            g = requests.get(f"{self.base}/v1/{PROJECT_ID}/agent-manager/workspace?offset=0&limit=10",
                             headers=self.h, timeout=30)
            wl = g.json().get("workspaceList", [])
            if not wl:
                raise RuntimeError(f"workspace/init 失败: {r.status_code} {r.text[:300]}")
            self._ws = wl[0]["id"]
        else:
            self._ws = data["workspaceList"][0]["id"]
        print(f"[workspace] id={self._ws}")
        return self._ws

    def create_model_service(self, body):
        ws = self.workspace_id()
        params = {"workspace_id": ws, "available_check": "false"}
        r = self._post(f"/v1/{PROJECT_ID}/model-manager/model-services",
                       params=params, json_body=body)
        if r.status_code < 300:
            return r.json()
        print(f"  [model-service ERR] {r.status_code} {r.text[:160]}")
        return None

    def create_provider(self, body):
        ws = self.workspace_id()
        r = self._post(f"/v1/{PROJECT_ID}/model-manager/integration/providers",
                       params={"workspace_id": ws}, json_body=body)
        if r.status_code < 300:
            return r.json()
        print(f"  [provider ERR] {r.status_code} {r.text[:160]}")
        return None

    def create_auth_config(self, body):
        ws = self.workspace_id()
        r = self._post(f"/v1/{PROJECT_ID}/model-manager/provider/auths",
                       params={"workspace_id": ws, "available_check": "false"}, json_body=body)
        if r.status_code < 300:
            return True
        print(f"  [auth-config ERR] {r.status_code} {r.text[:160]}")
        return False

    def online_model(self, mid):
        ws = self.workspace_id()
        self._post(f"/v1/{PROJECT_ID}/model-manager/model-services/{mid}/online",
                   params={"workspace_id": ws}, json_body={}, timeout=60)

    def publish_agent_version(self, agent_id):
        """POST /agents/{id}/versions — 生成 IR + 发布版本(返回是否成功)"""
        ws = self.workspace_id()
        r = self._post(f"/v1/{PROJECT_ID}/agent-manager/agents/{agent_id}/versions",
                       params={"workspace_id": ws}, json_body={"version_name": "v1", "version_note": "preset"}, timeout=180)
        return r.status_code < 300

    def import_plugins(self, jsonl_path, import_ids):
        ws = self.workspace_id()
        with open(jsonl_path, "rb") as f:
            files = {"file": (os.path.basename(jsonl_path), f, "application/octet-stream")}
            params = {"workspace_id": ws, "import_ids": ",".join(import_ids)}
            r = self._post(f"/v1/{PROJECT_ID}/agent-manager/plugins/import",
                           params=params, files=files, timeout=180)
        return self._parse(r, "plugins")

    def import_workflows(self, jsonl_path, import_ids):
        ws = self.workspace_id()
        with open(jsonl_path, "rb") as f:
            files = {"file": (os.path.basename(jsonl_path), f, "application/octet-stream")}
            params = {"workspace_id": ws, "import_workflows": ",".join(import_ids), "import_tools": ""}
            r = self._post(f"/v1/{PROJECT_ID}/agent-manager/workflows/import",
                           params=params, files=files, timeout=180)
        return self._parse(r, "workflows")

    def import_agents(self, jsonl_path, import_ids):
        ws = self.workspace_id()
        with open(jsonl_path, "rb") as f:
            files = {"file": (os.path.basename(jsonl_path), f, "application/octet-stream")}
            params = {"workspace_id": ws, "import_agents": ",".join(import_ids),
                      "import_tools": "", "import_workflows": ""}
            r = self._post(f"/v1/{PROJECT_ID}/agent-manager/agents/import",
                           params=params, files=files, timeout=180)
        return self._parse(r, "agents")

    @staticmethod
    def _parse(r, kind):
        try:
            data = r.json()
        except Exception:
            print(f"  [{kind} ERR] HTTP {r.status_code} (非JSON): {r.text[:200]}")
            return {"succeed": [], "failed": [], "raw": r.text[:200]}
        if r.status_code >= 300:
            print(f"  [{kind} ERR] HTTP {r.status_code}: {data}")
            return {"succeed": [], "failed": [], "raw": data}
        return {
            "succeed": data.get("succeed_ids", []) or data.get("succeed_len", 0),
            "failed":  data.get("failed_ids", []),
            "raw":     data,
        }


# ============================== 工具函数 ==============================
def stable_id(name):
    return str(uuid.uuid5(NS, name))

def write_jsonl(path, obj):
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(obj, ensure_ascii=False))

def list_plugin_files():
    out = []
    idx = os.path.join(PLUGINS_DIR, "index.json")
    if os.path.exists(idx):
        with open(idx, encoding="utf-8") as f:
            try:
                data = json.load(f)
            except json.JSONDecodeError:
                data = {}
        for cat_key, cat in data.get("categories", {}).items():
            for p in cat.get("plugins", []):
                out.append(os.path.join(PLUGINS_DIR, p.replace("/", os.sep)))
    # 兜底: 直接扫目录
    if not out:
        for root, _, files in os.walk(PLUGINS_DIR):
            for fn in files:
                if fn.endswith(".json") and fn not in ("index.json", "schema.json"):
                    out.append(os.path.join(root, fn))
    return sorted(set(out))

def list_example_files(kind):
    """kind: 'agent' or 'workflow'"""
    d = EXAMPLES_ZH
    if not os.path.isdir(d):
        return []
    return sorted(os.path.join(d, fn) for fn in os.listdir(d)
                  if fn.endswith(".json") and fn.startswith(kind))


# ============================== 转换器: 插件 ==============================
SMAP = {"query": "Query", "body": "Body", "header": "Header", "path": "Path"}
VMAP = {"string": "CHAR", "number": "NUMBER", "integer": "INT",
        "boolean": "BOOL", "array": "CHAR", "object": "CHAR"}

def build_input_schema(request_params):
    props, required = {}, []
    for pname, p in (request_params or {}).items():
        sm = (p.get("send_method") or "query").lower()
        loc = SMAP.get(sm, "Query")
        t = p.get("type", "string")
        props[pname] = {
            "location": loc, "validate_rule": "", "validate_type": VMAP.get(t, "CHAR"),
            "validated": False, "name": pname, "description": p.get("description", ""),
            "type": t, "required": bool(p.get("required", False)),
        }
        if p.get("required"):
            required.append(pname)
    return json.dumps({"type": "object", "properties": props, "required": required},
                      ensure_ascii=False)

OUT_SCHEMA = json.dumps({"type": "object", "properties": {}, "required": []}, ensure_ascii=False)

def convert_plugin(hist_plugin):
    """一个历史插件 JSON -> (lines列表, [tool_id 列表])
    每个工具写成一行: {"metadata": {<tool字段含 request_info>}, "import_type": "tool"}
    (插件导入要求 metadata.request_info 存在, 见 PluginService.importplugins:702)"""
    plugin_id = hist_plugin.get("plugin_id") or hist_plugin.get("name", "plugin")
    api_prefix = hist_plugin.get("api_prefix", "")
    lines, ids = [], []
    for tool in hist_plugin.get("tools", []):
        tname = tool.get("name") or "tool"
        # tool_id 必须匹配 ^[a-zA-Z0-9_-]+$（接口校验），清洗非法字符
        safe_pid = re.sub(r'[^a-zA-Z0-9_-]', '_', plugin_id)
        safe_name = re.sub(r'[^a-zA-Z0-9_-]', '_', tname)
        tool_id = f"{safe_pid}__{safe_name}"
        if tool.get("path"):
            url = api_prefix.rstrip("/") + "/" + tool.get("path", "").lstrip("/")
        else:
            url = api_prefix
        # MethodEnum 仅支持 GET/POST (RequestInfo.java:150)，PUT/DELETE/PATCH 归一化为 POST
        method = tool.get("method", "GET")
        if method not in ("GET", "POST"):
            method = "POST"
        metadata = {
            "tool_id": tool_id,
            "tool_display_name": tname,
            "tool_chinese_name": hist_plugin.get("name", tname),
            "tool_desc": tool.get("description", ""),
            "icon": DEFAULT_ICON,
            "request_info": {"url": url, "method": method, "headers": {}},
            "auth_info": {"scope": "domain", "domain": "", "auth_keys": []},
            "visibility": "global",
            "input_schema": build_input_schema(tool.get("request_params")),
            "output_schema": OUT_SCHEMA,
            "is_input_list": False,
            "is_output_list": False,
            "type": "inner",
            "intf_type": "blocking",
            "auth_required": False,
            "published": 1,
            "project_id": PROJECT_ID,
        }
        lines.append({"metadata": metadata, "import_type": "tool"})
        ids.append(tool_id)
    return lines, ids


# ============================== 转换器: 工作流 ==============================
NODE_TYPE_MAP = {"1": "Start", "3": "LLM", "2": "End"}

def _ref_val(content):
    """历史 ref content=[node,var] -> 当前 value 对象"""
    if isinstance(content, list) and len(content) >= 2:
        return {"type": "ref",
                "content": {"ref_node_id": content[0], "ref_var_name": content[1], "source": "system"},
                "hint": ""}
    return {"type": "literal", "content": "", "hint": ""}

def convert_workflow(hist, model_map):
    wf_id = stable_id("wf:" + hist.get("name", uuid.uuid4().hex))
    sch = hist.get("schema", {})
    nodes, layouts = [], {}
    for n in sch.get("nodes", []):
        nid = n.get("id")
        ntype = NODE_TYPE_MAP.get(str(n.get("type", "")), "LLM")
        data = n.get("data", {}) or {}
        pos = (n.get("meta", {}) or {}).get("position", {}) or {}
        layouts[nid] = {"x": pos.get("x", 0), "y": pos.get("y", 0)}
        inputs, outputs, configs = [], [], {}
        if ntype == "Start":
            outs = (data.get("outputs", {}) or {}).get("properties", {})
            req = (data.get("outputs", {}) or {}).get("required", [])
            for pn, pv in outs.items():
                inputs.append({"name": pn, "type": pv.get("type", "string"),
                               "description": pv.get("description", ""),
                               "required": pn in req, "source": "system", "reflection": False,
                               "value": {"type": "literal", "content": "", "hint": pv.get("description", "")}})
            # Start 节点 outputs = 它的输入变量(用户输入)
            for pn, pv in outs.items():
                outputs.append({"name": pn, "type": pv.get("type", "string"),
                                "description": pv.get("description", ""),
                                "required": pn in req, "source": "system", "reflection": False,
                                "value": {"type": "literal", "content": "", "hint": pv.get("description", "")}})
        elif ntype == "LLM":
            llm_param = (data.get("inputs", {}) or {}).get("llmParam", {}) or {}
            in_params = (data.get("inputs", {}) or {}).get("inputParameters", {}) or {}
            for pn, pv in in_params.items():
                inputs.append({"name": pn, "type": "string", "description": "",
                               "required": False, "source": "user", "reflection": False,
                               "value": _ref_val(pv.get("content"))})
            outs = (data.get("outputs", {}) or {}).get("properties", {})
            for pn, pv in outs.items():
                outputs.append({"name": pn, "type": pv.get("type", "string"),
                                "description": pv.get("description", ""),
                                "required": True, "source": "user", "reflection": False,
                                "value": {"type": "literal", "content": "", "hint": ""}})
            sys_prompt = (llm_param.get("systemPrompt", {}) or {}).get("content", "")
            tpl = (llm_param.get("prompt", {}) or {}).get("content", "{{query}}")
            mdl = llm_param.get("model", {}) or {}
            mname = mdl.get("model_name") or (model_map.get("default_model_name") or "")
            mid = mdl.get("model_deployment_id") or (model_map.get("default_deployment_id") or "")
            configs = {
                "history_size": 3, "max_tokens": 2048, "template_content": tpl,
                "system_prompt": sys_prompt, "enable_history": False, "top_p": 0.5,
                "format_instruction": "", "vision": [], "response_format": "text",
                "frequency_penalty": 0, "stream": True, "safety_barrier": False,
                "temperature": 0.5,
                "model": {"model_name": mname, "model_type": "LLM", "model_deployment_id": str(mid) if mid else ""},
            }
        elif ntype == "End":
            in_params = (data.get("inputs", {}) or {}).get("inputParameters", {}) or {}
            for pn, pv in in_params.items():
                inputs.append({"name": pn, "type": "string", "description": "",
                               "required": False, "source": "user", "reflection": False,
                               "value": _ref_val(pv.get("content"))})
            outputs.append({"name": "response_content", "type": "string", "description": "",
                             "required": True, "source": "system", "reflection": False,
                             "value": {"type": "generated"}})
        nodes.append({"id": nid, "name": data.get("title", nid), "type": ntype,
                      "inputs": inputs, "outputs": outputs, "configs": configs, "branches": []})
    edges = [{"source": e.get("sourceNodeID"), "target": e.get("targetNodeID"), "exception_branch": False}
             for e in sch.get("edges", [])]
    dmid = model_map.get("default_deployment_id") or ""
    dmname = model_map.get("default_model_name") or ""
    dsl = {
        "id": wf_id, "name": hist.get("name", "workflow"), "description": hist.get("desc", ""),
        "nodes": nodes, "edges": edges, "layouts": layouts,
        "configs": {
            "environment": {"name": ""}, "voice_interaction": {"language": "chinese", "timbre": "huaxiaohe", "domain": "common"},
            "memory": [], "default_model_switch": True, "trigger_list": [], "safety_barrier": False,
            "default_model": {"model_deployment_id": str(dmid) if dmid else "", "model_name": dmname, "model_type": "LLM"},
            "content_review": {"enabled": False, "filter": {"keywords": ""}, "replace": [], "reply": []},
            "prologue": "", "suggest_queries": [],
        },
    }
    metadata = {
        "id": wf_id, "name": hist.get("name", "workflow"), "code": hist.get("name", "workflow"),
        "description": hist.get("desc", ""), "avatar": DEFAULT_ICON, "status": "draft",
        "visibility": "project", "deleted": 0, "icon_name": "",
        "dsl_path": "", "ir_path": "", "workflow_type": "task",
        "project_id": PROJECT_ID, "workspace_id": "", "domain_id": "0", "trace_id": wf_id,
    }
    return {"dsl": dsl, "metadata": metadata, "plugins": [], "import_type": "workflow",
            "sub_workflows": [], "mcp_servers": [], "share_reference": []}, wf_id


# ============================== 转换器: 智能体 ==============================
def convert_agent(hist, model_map):
    name = hist.get("agent_name") or hist.get("name") or "agent"
    aid = stable_id("agent:" + name)
    # 系统提示词: configs.system_prompt 优先, 否则 prompt_template
    instr = ""
    cfgs = hist.get("configs", {}) or {}
    if isinstance(cfgs, dict) and cfgs.get("system_prompt"):
        instr = cfgs["system_prompt"]
    elif hist.get("prompt_template"):
        pt = hist["prompt_template"]
        if isinstance(pt, list):
            instr = "\n\n".join(m.get("content", "") for m in pt if m.get("role") == "system")
        elif isinstance(pt, str):
            instr = pt
    atype = (hist.get("agent_type") or "react").lower()
    md = model_map.get("default_deployment_id") or ""
    mn = model_map.get("default_model_name") or ""
    metadata = {
        "name": name, "description": hist.get("description", ""), "icon": DEFAULT_ICON,
        "instructions": instr, "prologue": hist.get("opening_remarks", ""),
        "type": "agent", "status": "draft", "agent_id": aid, "project_id": PROJECT_ID,
        "trace_id": aid, "workspace_id": "", "domain_id": "0",
        "model_deployment_id": str(md) if md else "", "model_name": mn,
        "model_config": {"top_p": 0.7, "temperature": 1.0, "history_size": 20,
                          "output_format": "text", "max_tokens": 4096, "frequency_penalty": 0.0},
        "model_type": "LLM", "plan_qa_independent": False, "trigger_list": [],
        "suggest_queries": [], "additional_questions_config": {"enable": False, "rounds": 1, "prompt": ""},
        "voice_interaction": {}, "knowledge_retrieve_policy": {},
        "ir_path": "", "workflow_switch_enabled": False, "scheduling_mode": "ReAct",
        "icon_name": "",
    }
    return {"metadata": metadata, "plugins": [], "workflows": [],
            "import_type": "agent", "mcp_servers": []}, aid


# ============================== 模型 ==============================
def load_models(cfg_path):
    if not cfg_path or not os.path.exists(cfg_path):
        return {"entries": []}
    with open(cfg_path, encoding="utf-8") as f:
        return json.load(f)

def create_models(api, cfg):
    entries = cfg.get("entries", [])
    if not entries:
        print("[models] models.json 无条目，跳过模型创建(agent/workflow 用占位 model)")
        return {}
    print("[models] 注意: 模型创建走三步流程(供应商→鉴权→模型)，auth_info schema 校验较严，失败属正常，建议用 UI 配置")
    mmap = {}
    for e in entries:
        api_key = e.get("api_key", "")
        if not api_key or api_key.startswith("在此填"):
            print(f"  [model SKIP] {e.get('model_name','?')}: 未填 api_key，请在 models.json 填写或用 UI 配置")
            continue
        schema = e.get("auth_info_schema", {"API Key": ""})
        # 1. 建用户供应商
        prov_body = {
            "provider_name": e.get("provider_name", e.get("model_name")),
            "provider_name_en": e.get("provider_name_en", e.get("model_name")),
            "description": e.get("description", ""),
            "provider_url": e.get("provider_url", e.get("api_url", "")),
            "auth_type": e.get("auth_type", "API_KEY"),
            "auth_info": json.dumps(schema, ensure_ascii=False),
        }
        prov = api.create_provider(prov_body)
        provider_id = None
        if prov and isinstance(prov, dict):
            provider_id = prov.get("id") or prov.get("provider_id")
        if not provider_id:
            print(f"  [model FAIL] {e.get('model_name','?')}: 建供应商失败，请用 UI 配置")
            continue
        # 2. 建鉴权 (metadata_id 从供应商返回)
        meta_id = prov.get("auth_metadata_id") or prov.get("metadata_id") or prov.get("identity_id")
        if meta_id:
            field_name = next(iter(schema.keys()), "API Key")
            api.create_auth_config({"metadata_id": str(meta_id), "auth_info": {field_name: api_key}})
        # 3. 建模型服务
        body = {
            "provider_id": str(provider_id),
            "service_name": e.get("service_name") or e.get("model_name"),
            "model_name": e["model_name"],
            "model_type": e.get("model_type", "LLM"),
            "model_description": e.get("model_description", ""),
            "api_url": e.get("api_url", ""),
            "interface_protocol": e.get("interface_protocol", "openai"),
            "is_support_stream": e.get("is_support_stream", True),
            "is_support_function": e.get("is_support_function", False),
            "is_public": e.get("is_public", True),
            "is_network": e.get("is_network", True),
            "context_length": e.get("context_length", 0),
        }
        r = api.create_model_service(body)
        mid = None
        if r and isinstance(r, dict):
            mid = r.get("id") or r.get("ID")
        if mid:
            try:
                api.online_model(mid)
            except Exception:
                pass
            mmap[e["model_name"]] = mid
            print(f"  [model OK] {e['model_name']} -> deployment_id={mid}")
        else:
            print(f"  [model FAIL] {e.get('model_name','?')}: 建模型失败，请用 UI 配置")
    default = next((v for v in mmap.values()), "")
    default_name = next((k for k in mmap), "")
    out = {"default_deployment_id": default, "default_model_name": default_name}
    out.update(mmap)
    return out


# ============================== 主流程 ==============================
def write_jsonl_multi(path, objs):
    with open(path, "w", encoding="utf-8") as f:
        for o in objs:
            f.write(json.dumps(o, ensure_ascii=False))
            f.write("\n")

def do_plugins(api, sample=False):
    files = list_plugin_files()
    if sample:
        files = files[:1]
    print(f"\n[plugins] 共 {len(files)} 个插件文件")
    ok, fail = 0, 0
    for fp in files:
        try:
            with open(fp, encoding="utf-8") as f:
                hist = json.load(f)
            lines, ids = convert_plugin(hist)
            if not ids:
                continue
            out = WORK_DIR / (os.path.basename(fp).replace(".json", ".jsonl"))
            write_jsonl_multi(str(out), lines)
            r = api.import_plugins(str(out), ids)
            s = len(r["succeed"]) if isinstance(r["succeed"], list) else r["succeed"]
            if s:
                ok += 1
                print(f"  [OK]   {hist.get('name','?'):<30} tools={len(ids)} succeed={s}")
            else:
                fail += 1
                print(f"  [FAIL] {hist.get('name','?'):<30} {str(r.get('raw',''))[:160]}")
        except Exception as e:
            fail += 1
            print(f"  [ERR]  {fp}: {e}")
    print(f"[plugins] 成功 {ok} / 失败 {fail}")
    return ok, fail

def do_workflows(api, model_map, sample=False):
    files = list_example_files("workflow")
    if sample:
        files = files[:1]
    print(f"\n[workflows] 共 {len(files)} 个工作流模板")
    ok, fail = 0, 0
    for fp in files:
        try:
            with open(fp, encoding="utf-8") as f:
                hist = json.load(f)
            obj, wid = convert_workflow(hist, model_map)
            out = WORK_DIR / ("wf_" + os.path.basename(fp).replace(".json", ".jsonl"))
            write_jsonl(str(out), obj)
            r = api.import_workflows(str(out), [wid])
            s = len(r["succeed"]) if isinstance(r["succeed"], list) else r["succeed"]
            if s:
                ok += 1
                print(f"  [OK]   {hist.get('name','?'):<30} id={wid[:8]}")
            else:
                fail += 1
                print(f"  [FAIL] {hist.get('name','?'):<30} {str(r.get('raw',''))[:160]}")
        except Exception as e:
            fail += 1
            print(f"  [ERR]  {fp}: {e}")
    print(f"[workflows] 成功 {ok} / 失败 {fail}")
    return ok, fail

def do_agents(api, model_map, sample=False):
    files = list_example_files("agent")
    if sample:
        files = files[:1]
    print(f"\n[agents] 共 {len(files)} 个智能体模板")
    ok, fail = 0, 0
    for fp in files:
        try:
            with open(fp, encoding="utf-8") as f:
                hist = json.load(f)
            obj, aid = convert_agent(hist, model_map)
            out = WORK_DIR / ("ag_" + os.path.basename(fp).replace(".json", ".jsonl"))
            write_jsonl(str(out), obj)
            r = api.import_agents(str(out), [aid])
            s = len(r["succeed"]) if isinstance(r["succeed"], list) else r["succeed"]
            if s:
                ok += 1
                print(f"  [OK]   {hist.get('agent_name','?'):<30} id={aid[:8]}")
            else:
                fail += 1
                print(f"  [FAIL] {hist.get('agent_name','?'):<30} {str(r.get('raw',''))[:160]}")
        except Exception as e:
            fail += 1
            print(f"  [ERR]  {fp}: {e}")
    print(f"[agents] 成功 {ok} / 失败 {fail}")
    return ok, fail


# ============================== 发布到应用百宝箱 (app-library) ==============================
# home/app-library 路由通过 GET /agent-manager/apps 查 t_app 表(仅过滤 deleted=0)。
# 把已导入的 agent/workflow 写入 t_app，使其出现在应用百宝箱。
# 无创建 app 的 API(发布通道有 publish.app-store.enable + op-svc 项目门槛)，故直接插表。

APP_INSERT_SQL = """
INSERT INTO t_app (app_id, project_id, workspace_id, `name`, description, icon, tags,
                   app_type, resource_id, resource_type, creator, published_on, workflow_type,
                   input_params, output_params, prologue, suggest_queries)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW(), %s, %s, %s, %s, %s)
ON DUPLICATE KEY UPDATE
    `name`=VALUES(`name`), description=VALUES(description), icon=VALUES(icon), tags=VALUES(tags),
    app_type=VALUES(app_type), resource_type=VALUES(resource_type), creator=VALUES(creator),
    published_on=VALUES(published_on), workflow_type=VALUES(workflow_type),
    workspace_id=VALUES(workspace_id), deleted=0
"""

def publish_to_app_library(api=None):
    try:
        import pymysql
    except ImportError:
        print("[app-library] 缺少 pymysql，请用 agent-runtime venv 运行")
        return 0, 0
    print("\n[app-library] 把已导入的 agent/workflow 发布到应用百宝箱 (t_app)...")
    if api is None:
        try:
            api = Api(); api.workspace_id()
        except Exception:
            api = None  # 无 manager 时跳过 IR 生成，仅写表
    conn = pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASSWORD,
                           database=DB_NAME, charset="utf8mb4", autocommit=True)
    cur = conn.cursor()
    ok = 0
    try:
        # 读取 preset-data 的 agent 名称(只处理这些，不碰其他)
        preset_agent_names = set()
        for fn in os.listdir(EXAMPLES_ZH):
            if fn.startswith("agent") and fn.endswith(".json"):
                with open(os.path.join(EXAMPLES_ZH, fn), encoding="utf-8") as f:
                    preset_agent_names.add(json.load(f).get("agent_name", ""))

        # agent -> t_app(app_id=agent_id) + online 版本(复制 ir_path)
        cur.execute("SELECT agent_id, name, description, icon, ir_path, workspace_id FROM t_agent WHERE deleted=0")
        for aid, name, desc, icon, ir_path, ws in cur.fetchall():
            if str(name) not in preset_agent_names:
                continue  # 不是 preset-data 里的 agent，跳过
            icon = icon or DEFAULT_ICON
            # 无 IR 则调 POST /versions 生成(会把 ir_path 写回 t_agent)
            if (not ir_path) and api:
                try:
                    if api.publish_agent_version(aid):
                        cur.execute("SELECT ir_path FROM t_agent WHERE agent_id=%s", (aid,))
                        row = cur.fetchone()
                        ir_path = row[0] if row else None
                except Exception as e:
                    print(f"  [warn] agent {name} 生成IR失败: {e}")
            # 插 online 版本(复制 ir_path，幂等) —— copy/experience 需要 selectOnlineVersion 命中
            if ir_path:
                # 设 dsl_path（retrieveAgentApp 下载的是 dsl_path 不是 ir_path）
                dsl_path = ir_path.replace("/ir/", "/dsl/")
                # 如果 MinIO 里 DSL 不存在，从 IR 复制（IR 有 dsl 信息）
                try:
                    import boto3
                    from botocore.client import Config as BotoConfig
                    s3 = boto3.client("s3", endpoint_url=f"http://{OBS_HOST}:{OBS_PORT}",
                                      aws_access_key_id=MINIO_AK, aws_secret_access_key=MINIO_SK,
                                      region_name="us-east-1", config=BotoConfig(signature_version="s3v4"))
                    try:
                        s3.head_object(Bucket=MINIO_BUCKET, Key=dsl_path)
                    except Exception:
                        ir_data = s3.get_object(Bucket=MINIO_BUCKET, Key=ir_path)["Body"].read()
                        s3.put_object(Bucket=MINIO_BUCKET, Key=dsl_path, Body=ir_data)
                        print(f"  [OK] {str(name)[:30]}: IR -> DSL copied to OBS")
                except Exception as e:
                    print(f"  [warn] {str(name)[:30]}: OBS copy IR->DSL failed: {e}")
                cur.execute("""INSERT INTO t_agent_version
                    (version_id, agent_id, project_id, name, instructions, ir_path, dsl_path, is_online, workspace_id, published_on)
                    SELECT CONCAT('v-', agent_id), agent_id, project_id, name, instructions, ir_path, %s, 1, workspace_id, NOW()
                    FROM t_agent WHERE agent_id=%s
                    ON DUPLICATE KEY UPDATE is_online=1, ir_path=VALUES(ir_path), dsl_path=VALUES(dsl_path)""", (dsl_path, aid))
                # 同步 t_agent 的 dsl_path
                cur.execute("UPDATE t_agent SET dsl_path=%s WHERE agent_id=%s AND (dsl_path IS NULL OR dsl_path='')", (dsl_path, aid))
            # 设 published（只在资产广场展示，t_agent 无 visibility 列）
            cur.execute("UPDATE t_agent SET status='published' WHERE agent_id=%s", (aid,))
            # treasure metadata 上传到 OBS + Redis（从资产广场体验时 invokeMode=published -> isTreasure=true
            # -> PublishUtils.getPublish 读 metadata/{agentId}/{agentId}_treasure.json，不存在则 OBS_FAILED
            # -> AgentRuntimeService 转 AGENT_OR_VERSION_NOT_EXIST 02101032）
            # versionId 设为空字符串（cache loader 用空时不拼版本后缀 -> 读 {agentId}.json）
            try:
                import boto3
                from botocore.client import Config as BotoConfig
                s3 = boto3.client("s3", endpoint_url=f"http://{OBS_HOST}:{OBS_PORT}",
                                  aws_access_key_id=MINIO_AK, aws_secret_access_key=MINIO_SK,
                                  region_name="us-east-1", config=BotoConfig(signature_version="s3v4"))
                cur.execute("SELECT project_id, workspace_id, domain_id FROM t_agent WHERE agent_id=%s", (aid,))
                apid, aws2, adid = cur.fetchone()
                treasure_meta = json.dumps({
                    "agentId": str(aid), "domainId": str(adid) if adid else "0",
                    "projectId": str(apid), "workspaceId": str(aws2),
                    "versionId": "",  # 空：cache loader 读 {agentId}.json 不拼版本后缀
                    "updatedAt": 0, "isContentReviewEnable": False,
                    "safetyBarrier": False, "appType": "agent",
                }, ensure_ascii=False)
                treasure_key = f"metadata/{aid}/{aid}_treasure.json"
                s3.put_object(Bucket=MINIO_BUCKET, Key=treasure_key, Body=treasure_meta.encode("utf-8"))
                try:
                    import redis as redis_lib
                    r = redis_lib.Redis(host=REDIS_HOST, port=int(REDIS_PORT), decode_responses=True)
                    r.set(f"agent-builder:agent:metadata:{aid}:treasure", treasure_meta)
                except Exception:
                    pass
                print(f"  [OK] {str(name)[:30]}: treasure metadata -> OBS+Redis")
            except Exception as e:
                print(f"  [warn] {str(name)[:30]}: treasure metadata failed: {e}")
            # app: app_id = resource_id(=agent_id)
            cur.execute(APP_INSERT_SQL, (aid, PROJECT_ID, ws, name, desc, icon, '[]',
                                        "chat", aid, "agent", "官方预置", None, None, None, None, None))
            ok += 1
            print(f"  [OK] agent  {str(name)[:30]:<30} -> published+global+资产广场")
        # workflow -> t_app(app_id=workflow_id)
        # 只处理 preset-data 里的工作流(按 name 匹配)，不碰其他工作流
        # 同时设 status='published' + visibility='global'(只在资产广场展示，不在"我的工作流"出现)
        preset_wf_names = set()
        for fn in os.listdir(EXAMPLES_ZH):
            if fn.startswith("workflow") and fn.endswith(".json"):
                with open(os.path.join(EXAMPLES_ZH, fn), encoding="utf-8") as f:
                    preset_wf_names.add(json.load(f).get("name", ""))
        cur.execute("SELECT id, name, description, avatar, workflow_type, workspace_id FROM t_agent_workflow WHERE deleted=0")
        for wid, name, desc, avatar, wftype, ws in cur.fetchall():
            if str(name) not in preset_wf_names:
                continue  # 不是本次配置文件里的工作流，跳过
            icon = avatar or DEFAULT_ICON
            # 设 published + global（只在资产广场展示）
            cur.execute("UPDATE t_agent_workflow SET status='published', visibility='global' WHERE id=%s", (wid,))
            # 插 t_release_version（getWorkflowScene 需要）
            cur.execute("""INSERT INTO t_release_version
                (id, version_id, version_name, version_note, app_id, app_type, status, dsl_path, ir_path,
                 creator, creator_id, released_on, deleted, updated_on)
                SELECT CONCAT('rv-', id), CONCAT('rv-', id), 'v1', 'preset', id, 'workflow', 'normal',
                       dsl_path, ir_path, '官方预置', 'SYSTEM', NOW(), 0, NOW()
                FROM t_agent_workflow WHERE id=%s
                ON DUPLICATE KEY UPDATE dsl_path=VALUES(dsl_path), ir_path=VALUES(ir_path), deleted=0""", (wid,))
            # 插 t_release_channel（APP_STORE 渠道，getWorkflowScene 需要）
            cur.execute("""INSERT INTO t_release_channel
                (id, app_id, app_type, version_id, version_name, channel_type, status, project_id, workspace_id,
                 creator, creator_id, released_on)
                SELECT CONCAT('rc-', id), id, 'workflow', CONCAT('rv-', id), 'v1', 'APP_STORE', 'normal',
                       project_id, workspace_id, '官方预置', 'SYSTEM', NOW()
                FROM t_agent_workflow WHERE id=%s
                ON DUPLICATE KEY UPDATE version_id=VALUES(version_id), status=VALUES(status)""", (wid,))
            # 设 last_version_id（scenario-bot 体验需要）
            cur.execute("UPDATE t_agent_workflow SET last_version_id=CONCAT('hv-', id) WHERE id=%s", (wid,))
            # 插 t_history_agent_workflow（发布版本快照）
            cur.execute("""INSERT INTO t_history_agent_workflow
                (history_id, id, name, project_id, domain_id, is_share, dsl_path, ir_path, status, visibility,
                 created_at, published_at)
                SELECT CONCAT('hv-', id), id, name, project_id, domain_id, 1, dsl_path, ir_path, status, visibility,
                       UNIX_TIMESTAMP()*1000, UNIX_TIMESTAMP()*1000
                FROM t_agent_workflow WHERE id=%s
                ON DUPLICATE KEY UPDATE dsl_path=VALUES(dsl_path), ir_path=VALUES(ir_path), status=VALUES(status)""", (wid,))
            # t_app: input_params/output_params 设 '[]'（避免 parseWorkflowTable 解析 DSL 节点报错）
            cur.execute(APP_INSERT_SQL, (wid, PROJECT_ID, ws, name, desc, icon, '[]',
                                         "scene", wid, "workflow", "官方预置", wftype, '[]', '[]', None, None))
            # treasure metadata 上传到 OBS + Redis（试运行从资产广场时 invokeMode=published -> isTreasure=true
            # -> PublishUtils.getPublish 读 metadata/{wfId}/{wfId}_treasure.json，不存在则 OBS_ACCESS_FAILED）
            # versionId 必须为空字符串（cache loader 用空时不拼版本后缀 -> 读 {wfId}.json 而非 {wfId}_{ver}.json）
            try:
                import boto3
                from botocore.client import Config as BotoConfig
                s3 = boto3.client("s3", endpoint_url=f"http://{OBS_HOST}:{OBS_PORT}",
                                  aws_access_key_id=MINIO_AK, aws_secret_access_key=MINIO_SK,
                                  region_name="us-east-1", config=BotoConfig(signature_version="s3v4"))
                cur.execute("SELECT project_id, workspace_id, domain_id, workflow_type FROM t_agent_workflow WHERE id=%s", (wid,))
                wpid, wws, wdid, wwtype = cur.fetchone()
                treasure_meta = json.dumps({
                    "agentId": str(wid), "domainId": str(wdid) if wdid else "0",
                    "projectId": str(wpid), "workspaceId": str(wws),
                    "versionId": "",  # 空：cache loader 读 {wfId}.json 不拼版本后缀
                    "updatedAt": 0, "isContentReviewEnable": False,
                    "safetyBarrier": False, "appType": str(wwtype) if wwtype else "task",
                }, ensure_ascii=False)
                treasure_key = f"metadata/{wid}/{wid}_treasure.json"
                s3.put_object(Bucket=MINIO_BUCKET, Key=treasure_key, Body=treasure_meta.encode("utf-8"))
                try:
                    import redis as redis_lib
                    r = redis_lib.Redis(host=REDIS_HOST, port=int(REDIS_PORT), decode_responses=True)
                    r.set(f"agent-builder:agent:metadata:{wid}:treasure", treasure_meta)
                except Exception:
                    pass  # Redis 不可连不影响 OBS
                print(f"  [OK] {str(name)[:30]}: treasure metadata -> OBS+Redis")
            except Exception as e:
                print(f"  [warn] {str(name)[:30]}: treasure metadata failed: {e}")
            ok += 1
            print(f"  [OK] workflow {str(name)[:30]:<30} -> published+global+资产广场")
        cur.execute("SELECT COUNT(*) FROM t_app WHERE deleted=0")
        total = cur.fetchone()[0]
        print(f"[app-library] 完成，t_app 当前共 {total} 条应用 (GET /agent-manager/apps 可查)")
    except Exception as e:
        print(f"[app-library] ERR: {e}")
    finally:
        cur.close(); conn.close()
    return ok, 0


# ============================== 发布到插件市场 (plugin-market) ==============================
# home/plugin-market OFFICIAL 标签查 GET /agent-manager/plugins?type=inner&published=1。
# 导入的插件是 type=custom，需改为 inner 才在 OFFICIAL 标签显示。

def publish_plugins_to_market():
    try:
        import pymysql
    except ImportError:
        print("[plugin-market] 缺少 pymysql，跳过(转换器已设 type=inner+visibility=global)"); return 0
    print(f"\n[plugin-market] 发布 preset 插件到 OFFICIAL 标签(type=inner+visibility=global+project_id='{OP_SVC_PROJECT_ID}')...")
    # 读取 preset-data 的 plugin_id 列表(只处理这些，不碰其他插件)
    preset_pids = set()
    idx = os.path.join(PLUGINS_DIR, "index.json")
    if os.path.exists(idx):
        with open(idx, encoding="utf-8") as f:
            try:
                data = json.load(f)
            except json.JSONDecodeError:
                data = {}
        for cat in data.get("categories", {}).values():
            for p in cat.get("plugins", []):
                pfull = os.path.join(PLUGINS_DIR, p.replace("/", os.sep))
                if os.path.exists(pfull):
                    with open(pfull, encoding="utf-8") as pf:
                        pid = json.load(pf).get("plugin_id", "")
                        if pid:
                            preset_pids.add(re.sub(r'[^a-zA-Z0-9_-]', '_', pid))
    if not preset_pids:
        print("[plugin-market] 未读取到 preset plugin_id，跳过")
        return 0
    # 构建 SQL LIKE 条件(tool_id 格式: {plugin_id}__{tool_name})
    like_clauses = " OR ".join([f"tool_id LIKE %s" for _ in preset_pids])
    like_args = [f"{pid}__%" for pid in preset_pids]
    try:
        conn = pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASSWORD,
                               database=DB_NAME, charset="utf8mb4", autocommit=True,
                               connect_timeout=30, read_timeout=30)
    except Exception as e:
        print(f"[plugin-market] DB 不可连({e})，跳过 DB 更新。")
        print(f"  请手动执行以下 SQL(在远程 DB 上):")
        print(f"    UPDATE t_tool SET type='inner', visibility='global', project_id='{OP_SVC_PROJECT_ID}'")
        print(f"    WHERE tool_id LIKE 'preset_pid__%' (对每个 preset plugin_id 执行);")
        return 0
    cur = conn.cursor()
    try:
        # 1. 只更新 preset 插件：custom/project -> inner+global
        sql_custom = f"SELECT COUNT(*) FROM t_tool WHERE ({like_clauses}) AND (visibility='project' OR type='custom')"
        cur.execute(sql_custom, like_args)
        before = cur.fetchone()[0]
        if before > 0:
            sql_upd = f"UPDATE t_tool SET type='inner', visibility='global' WHERE ({like_clauses}) AND (visibility='project' OR type='custom')"
            cur.execute(sql_upd, like_args)
            print(f"  [OK] {before} 个 preset 旧 custom 已更新为 inner+global")
        else:
            print("  [skip] 无 preset 旧 custom 插件需更新")
        # 2. 把 preset inner 插件 project_id 设为 OP_SVC_PROJECT_ID（匹配 manager 的 opSvcProjectId）
        sql_fix = f"SELECT COUNT(*) FROM t_tool WHERE ({like_clauses}) AND type='inner' AND visibility='global' AND published=1 AND project_id<>%s"
        cur.execute(sql_fix, like_args + [OP_SVC_PROJECT_ID])
        need_fix = cur.fetchone()[0]
        if need_fix > 0:
            sql_upd2 = f"UPDATE t_tool SET project_id=%s WHERE ({like_clauses}) AND type='inner' AND visibility='global' AND published=1"
            cur.execute(sql_upd2, like_args + [OP_SVC_PROJECT_ID])
            print(f"  [OK] {need_fix} 个 preset inner 插件 project_id 已设为 '{OP_SVC_PROJECT_ID}'")
        else:
            print("  [skip] preset inner 插件 project_id 已正确")
        sql_cus = f"SELECT COUNT(*) FROM t_tool WHERE ({like_clauses}) AND type='inner' AND published=1 AND visibility='global' AND project_id=%s"
        cur.execute(sql_cus, like_args + [OP_SVC_PROJECT_ID])
        after = cur.fetchone()[0]
        print(f"[plugin-market] preset OFFICIAL 可见 共 {after} 个")
    except Exception as e:
        print(f"[plugin-market] ERR: {e}")
    finally:
        cur.close(); conn.close()
    return 0


# ============================== 发布到模型广场 (model-square) ==============================
# home/model-square 查 GET /model-manager/model-services，mapper 条件
# (PROJECT_ID=用户 OR 'SYSTEM') AND (WORKSPACE_ID=用户 OR 'SYSTEM') → SYSTEM 作用域模型全局可见。
# 历史预置模型数据已丢失，这里插入代表性模型目录(需后续配置鉴权 AK/SK 才可调用)。

PRESET_MODELS = [
    # (service_name, model_name, model_type, api_url, interface_protocol, is_function, is_stream)
    ("DeepSeek-V3",       "DeepSeek-V3",        "LLM",            "https://api.deepseek.com/v1",                    "openai",  True,  True),
    ("deepseek-reasoner", "deepseek-reasoner",  "LLM",            "https://api.deepseek.com/v1",                    "openai",  True,  True),
    ("qwen-max",          "qwen-max",           "LLM",            "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen",  True,  True),
    ("qwen-plus",         "qwen-plus",          "LLM",            "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen",  True,  True),
    ("glm-4",             "glm-4",              "LLM",            "https://open.bigmodel.cn/api/paas/v4",           "zhipu",   True,  True),
    ("glm-4-flash",       "glm-4-flash",        "LLM",            "https://open.bigmodel.cn/api/paas/v4",           "zhipu",   False, True),
    ("moonshot-v1-8k",    "moonshot-v1-8k",     "LLM",            "https://api.moonshot.cn/v1",                     "moonshot",True,  True),
    ("bge-m3",            "bge-m3",             "Text-Embedding", "",                                               "openai",  False, False),
]

MODEL_INSERT_SQL = """
INSERT INTO t_model_service (ID, PROVIDER_ID, SERVICE_NAME, SERVICE_KEY, MODEL_NAME, MODEL_VERSION,
    MODEL_TYPE, MODEL_DEPLOY_TYPE, DOMAIN_ID, PROJECT_ID, WORKSPACE_ID, API_URL, IS_SUPPORT_FUNCTION,
    INTERFACE_PROTOCOL, IS_SUPPORT_STREAM, PUBLISH_STATUS, IS_PUBLIC, LOGO, CREATED_BY_USER,
    LAST_UPDATED_BY_USER, CREATED_DATE, LAST_UPDATED_DATE, STATUS, SYNC_STATUS, MODEL_TAGS)
VALUES (%s, '100', %s, %s, %s, '1.0', %s, 'api', '0', 'SYSTEM', 'SYSTEM', %s, %s, %s, %s, 'online', 1,
    NULL, 'SYSTEM', 'SYSTEM', %s, %s, 1, 0, NULL)
ON DUPLICATE KEY UPDATE SERVICE_NAME=VALUES(SERVICE_NAME), MODEL_TYPE=VALUES(MODEL_TYPE),
    API_URL=VALUES(API_URL), INTERFACE_PROTOCOL=VALUES(INTERFACE_PROTOCOL),
    PUBLISH_STATUS=VALUES(PUBLISH_STATUS), IS_PUBLIC=VALUES(IS_PUBLIC)
"""

def publish_models_to_square():
    try:
        import pymysql
    except ImportError:
        print("[model-square] 缺少 pymysql"); return 0
    import time as _t
    print("\n[model-square] 插入预置模型目录到 t_model_service (SYSTEM 全局作用域)...")
    print("  注意: 这些模型仅展示，需在前端模型管理里配置供应商鉴权(AK/SK)后才可调用")
    conn = pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASSWORD,
                           database=DB_NAME, charset="utf8mb4", autocommit=True)
    cur = conn.cursor()
    ok = 0
    try:
        now_ms = int(_t.time() * 1000)
        for svc, mn, mt, url, proto, isfn, isstream in PRESET_MODELS:
            mid = stable_id("model:" + mn)
            skey = "system:" + mn
            cur.execute(MODEL_INSERT_SQL, (mid, svc, skey, mn, mt, url, 1 if isfn else 0, proto,
                                           1 if isstream else 0, now_ms, now_ms))
            ok += 1
            print(f"  [OK] {mn:<22} type={mt:<16} proto={proto}")
        cur.execute("SELECT COUNT(*) FROM t_model_service")
        total = cur.fetchone()[0]
        print(f"[model-square] 完成，t_model_service 共 {total} 条 (GET /model-manager/model-services 可查)")
    except Exception as e:
        print(f"[model-square] ERR: {e}")
    finally:
        cur.close(); conn.close()
    return ok


def publish_all_markets():
    publish_to_app_library()
    publish_plugins_to_market()
    publish_models_to_square()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sample", action="store_true", help="只导入样本验证")
    ap.add_argument("--only", choices=["plugins", "agents", "workflows"], help="只导入某一类")
    ap.add_argument("--app-library", action="store_true",
                    help="把已导入的 agent/workflow 发布到应用百宝箱(home/app-library)")
    ap.add_argument("--plugin-market", action="store_true",
                    help="把已导入插件设为 inner 发布到插件市场(home/plugin-market)")
    ap.add_argument("--model-square", action="store_true",
                    help="插入预置模型目录到模型广场(home/model-square)")
    ap.add_argument("--markets", action="store_true",
                    help="发布到全部三个市场(app-library + plugin-market + model-square)")
    ap.add_argument("--models", default=str(SCRIPT_DIR / "models.json"), help="模型配置文件路径")
    args = ap.parse_args()

    WORK_DIR.mkdir(exist_ok=True)
    print("=" * 60)
    print("  Agent Studio 预置数据一键导入")
    print("=" * 60)

    # --markets: 发布到全部三个市场
    if args.markets:
        try:
            publish_all_markets()
        except Exception as e:
            print(f"[FATAL] {e}"); sys.exit(1)
        print("\n" + "=" * 60)
        print("  前端访问:")
        print("  应用百宝箱: http://localhost:4200/openjiuwen/home/app-library")
        print("  插件市场:   http://localhost:4200/openjiuwen/home/plugin-market")
        print("  模型广场:   http://localhost:4200/openjiuwen/home/model-square")
        print("=" * 60)
        return

    # --app-library / --plugin-market / --model-square 单独模式
    standalone = {
        "app_library": ("应用百宝箱",     "home/app-library",   publish_to_app_library),
        "plugin_market": ("插件市场",      "home/plugin-market", publish_plugins_to_market),
        "model_square": ("模型广场",      "home/model-square",  publish_models_to_square),
    }
    for flag, (name, route, fn) in standalone.items():
        if getattr(args, flag):
            try:
                fn()
            except Exception as e:
                print(f"[FATAL] {e}"); sys.exit(1)
            print("\n" + "=" * 60)
            print(f"  前端访问: http://localhost:4200/openjiuwen/{route}")
            print("=" * 60)
            return

    api = Api()
    try:
        api.workspace_id()
    except Exception as e:
        print(f"[FATAL] 无法连接 studio-manager (31111) 或初始化工作空间: {e}")
        sys.exit(1)

    model_map = {}
    if not args.only or args.only not in ("plugins",):  # agents/workflows 可能用模型
        # 仅当配置文件存在且有条目时才创建模型
        mcfg = load_models(args.models)
        if mcfg.get("entries"):
            print("\n[models] 根据配置创建模型服务...")
            model_map = create_models(api, mcfg)
        else:
            print(f"[models] {args.models} 无条目或不存在，跳过(agent/workflow 用占位 model)")

    ok = fail = 0
    if not args.only or args.only == "plugins":
        o, f = do_plugins(api, args.sample); ok += o; fail += f
    if not args.only or args.only == "workflows":
        o, f = do_workflows(api, model_map, args.sample); ok += o; fail += f
    if not args.only or args.only == "agents":
        o, f = do_agents(api, model_map, args.sample); ok += o; fail += f

    # 导入后自动发布到三个市场
    try:
        if not args.only or args.only in ("agents", "workflows"):
            publish_to_app_library(api)
        if not args.only or args.only == "plugins":
            publish_plugins_to_market()
        if not args.only or args.only in ("agents", "workflows", "plugins"):
            publish_models_to_square()
    except Exception as e:
        print(f"[markets] 跳过: {e}")

    print("\n" + "=" * 60)
    print(f"  导入完成: 成功 {ok} 项, 失败 {fail} 项")
    print(f"  前端: http://localhost:4200/openjiuwen/")
    print(f"  应用百宝箱: http://localhost:4200/openjiuwen/home/app-library")
    print(f"  插件市场:   http://localhost:4200/openjiuwen/home/plugin-market")
    print(f"  模型广场:   http://localhost:4200/openjiuwen/home/model-square")
    print("=" * 60)

if __name__ == "__main__":
    main()
