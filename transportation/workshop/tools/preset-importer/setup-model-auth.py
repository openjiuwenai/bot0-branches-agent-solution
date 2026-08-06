#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
模型供应商鉴权配置 + 调用验证
================================
读取启动时填入的 API_KEY / API_URL，给预置模型绑定鉴权(provider 100,
metadata 1022)，设 online，并端到端调用 chat/completions 验证。

用法(由 start-all.ps1 自动调用，也可手动):
    python setup-model-auth.py --api-key sk-xxx --api-url https://api.deepseek.com/v1
    python setup-model-auth.py --api-key sk-xxx --api-url https://api.deepseek.com/v1 --model DeepSeek-V3
若不传 --api-key，仅打印提醒(模型不可调用)。
"""
import json
import os
import sys
import argparse
from pathlib import Path

try:
    import requests, pymysql
except ImportError:
    print("[model-auth] 缺少 requests/pymysql，请用 agent-runtime venv:")
    print(r"  ~\agent-runtime\.venv\Scripts\python.exe setup-model-auth.py")
    sys.exit(2)


def _load_env(path):
    cfg = {}
    if not os.path.exists(path):
        return cfg
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            v = v.strip()
            if len(v) >= 2 and v[0] == v[-1] and v[0] in ('"', "'"):
                v = v[1:-1]
            cfg[k.strip()] = v
    return cfg


# 优先读同目录 importer.env(独立模式)，回退 agent-studio.env(工程内模式)
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_CFG = _load_env(os.path.join(_SCRIPT_DIR, "importer.env"))
if not _CFG:
    _CFG = _load_env(os.path.join(_SCRIPT_DIR, "agent-studio.env"))


def cfg(k, d):
    return _CFG.get(k, d)


BASE_URL = cfg("BASE_URL", f"http://127.0.0.1:{cfg('MANAGER_PORT','31111')}")
TOKEN = cfg("AUTH_TOKEN", "testUser|0")
PROJECT_ID = cfg("PROJECT_ID", "0")
DB_HOST = cfg("DB_HOST", "127.0.0.1")
DB_PORT = int(cfg("DB_PORT", "3306"))
DB_USER = cfg("DB_USER", " ")
DB_PASSWORD = cfg("DB_PASSWORD", " ")
DB_NAME = cfg("DB_NAME", "agent-builder")
# OBS(MinIO) 配置 — 改 agent IR 里的模型引用用
OBS_HOST = cfg("OBS_HOST", "127.0.0.1")
OBS_PORT = int(cfg("OBS_PORT", "9000"))
MINIO_AK = cfg("MINIO_AK", " ")
MINIO_SK = cfg("MINIO_SK", " ")
MINIO_BUCKET = cfg("MINIO_BUCKET", "agent-builder")

H = {"X-Auth-Token": TOKEN, "X-Language": "zh-CN", "Content-Type": "application/json"}
SYSTEM_AUTH_META_ID = "1022"  # provider 100 的系统 API_KEY 鉴权元数据(t_provider_auth_metadata)


def workspace_id():
    r = requests.post(f"{BASE_URL}/v1/{PROJECT_ID}/agent-manager/workspace/init", headers=H, timeout=60)
    data = r.json()
    wl = data.get("workspaceList") or []
    if wl:
        return wl[0]["id"]
    g = requests.get(f"{BASE_URL}/v1/{PROJECT_ID}/agent-manager/workspace?offset=0&limit=10", headers=H, timeout=30)
    wl = g.json().get("workspaceList", [])
    return wl[0]["id"] if wl else None


def create_auth_config(ws, api_key):
    """创建/更新 provider 100 的鉴权数据(t_provider_auth_data)，auth_info 用 API Key 字段。"""
    body = {"metadata_id": SYSTEM_AUTH_META_ID, "auth_info": {"API Key": api_key}}
    r = requests.post(f"{BASE_URL}/v1/{PROJECT_ID}/model-manager/provider/auths",
                      headers=H, params={"workspace_id": ws, "available_check": "false"}, json=body, timeout=60)
    if r.status_code < 300:
        print("  [auth OK] provider 100 鉴权配置完成(API Key 已绑定)")
        return True
    print(f"  [auth ERR] HTTP {r.status_code}: {r.text[:200]}")
    return False


def update_models(api_url):
    """绑定预置模型 AUTH_METADATA_ID=1022，设 online；bge-m3 用传入 api_url。"""
    conn = pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASSWORD,
                           database=DB_NAME, charset="utf8mb4", autocommit=True)
    cur = conn.cursor()
    try:
        # 所有 SYSTEM 预置模型绑鉴权 + online
        cur.execute("UPDATE t_model_service SET AUTH_METADATA_ID=%s, PUBLISH_STATUS='online' WHERE PROJECT_ID='SYSTEM'",
                    (SYSTEM_AUTH_META_ID,))
        # embedding 模型(bge-m3)的 API_URL 若空，用传入 url 占位(实际 embedding 端点需单独配)
        if api_url:
            cur.execute("UPDATE t_model_service SET API_URL=%s WHERE PROJECT_ID='SYSTEM' AND MODEL_TYPE='Text-Embedding' AND (API_URL='' OR API_URL IS NULL)",
                        (api_url,))
        cur.execute("SELECT COUNT(*) FROM t_model_service WHERE PROJECT_ID='SYSTEM' AND AUTH_METADATA_ID=%s AND PUBLISH_STATUS='online'",
                    (SYSTEM_AUTH_META_ID,))
        n = cur.fetchone()[0]
        print(f"  [model OK] {n} 个预置模型已绑定鉴权+online")
    finally:
        cur.close(); conn.close()


def find_test_model(api_url, model_name):
    """找要测试的模型 + IR 重绑的目标模型: 优先 --model，其次 API_URL 匹配。"""
    conn = pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASSWORD,
                           database=DB_NAME, charset="utf8mb4")
    cur = conn.cursor()
    sql = "SELECT ID, MODEL_NAME, API_URL FROM t_model_service WHERE PROJECT_ID='SYSTEM' AND MODEL_TYPE='LLM'"
    try:
        if model_name:
            cur.execute(sql + " AND MODEL_NAME=%s LIMIT 1", (model_name,))
            row = cur.fetchone()
            if row:
                return row
        if api_url:
            cur.execute(sql + " AND API_URL=%s ORDER BY (CASE WHEN MODEL_NAME LIKE '%%reasoner%%' OR MODEL_NAME LIKE '%%r1%%' THEN 1 ELSE 0 END) LIMIT 1", (api_url,))
            row = cur.fetchone()
            if row:
                return row
        cur.execute(sql + " LIMIT 1")
        return cur.fetchone()
    finally:
        cur.close(); conn.close()


def rebind_agent_models(target_id, target_name):
    """把所有 agent 的 IR(OBS) 里 modelConfig 改成目标模型，同步更新 t_agent。
    直接体验时 agent 运行用的是 IR 里的模型，改 t_agent 不够，必须改 IR JSON。"""
    import boto3
    from botocore.client import Config
    s3 = boto3.client("s3", endpoint_url=f"http://{OBS_HOST}:{OBS_PORT}", aws_access_key_id=MINIO_AK,
                      aws_secret_access_key=MINIO_SK, region_name="us-east-1", config=Config(signature_version="s3v4"))
    conn = pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASSWORD,
                           database=DB_NAME, charset="utf8mb4", autocommit=True)
    cur = conn.cursor()
    n = 0
    try:
        cur.execute("SELECT agent_id, ir_path FROM t_agent WHERE ir_path IS NOT NULL AND ir_path <> '' AND deleted=0")
        for aid, ir_path in cur.fetchall():
            try:
                obj = s3.get_object(Bucket=MINIO_BUCKET, Key=ir_path)
                ir = json.loads(obj["Body"].read())
                mc = ir.get("configs", {}).get("modelConfig")
                if not isinstance(mc, dict):
                    continue
                mc["modelName"] = target_id
                if isinstance(mc.get("extension"), dict):
                    mc["extension"]["deploymentId"] = target_id
                    mc["extension"]["authId"] = ""
                s3.put_object(Bucket=MINIO_BUCKET, Key=ir_path,
                              Body=json.dumps(ir, ensure_ascii=False).encode("utf-8"))
                n += 1
            except Exception as e:
                print(f"  [rebind] agent {aid[:8]} IR 更新失败: {e}")
        # 同步 t_agent 字段
        cur.execute("UPDATE t_agent SET model_deployment_id=%s, model_name=%s WHERE ir_path IS NOT NULL AND ir_path <> '' AND deleted=0",
                    (target_id, target_name))
    finally:
        cur.close(); conn.close()
    print(f"  [rebind OK] {n} 个 agent 的 IR 模型已改为 {target_name}({target_id[:8]})")


def test_chat(ws, model_id, model_name):
    body = {"model": model_id, "messages": [{"role": "user", "content": "你好，请用一句话自我介绍"}], "stream": False}
    r = requests.post(f"{BASE_URL}/v1/{PROJECT_ID}/agent-manager/agent-builder/chat/completions",
                      headers=H, params={"workspace_id": ws, "refresh": "true"}, json=body, timeout=120)
    print(f"  [test] model={model_name}({model_id[:8]}) HTTP {r.status_code}")
    try:
        data = r.json()
    except Exception:
        print(f"  [test FAIL] 原始响应: {r.text[:300]}")
        return
    if r.status_code < 300 and data.get("choices"):
        content = data["choices"][0].get("message", {}).get("content", "")
        print(f"  [test OK 调用成功] 回复: {str(content)[:120]}")
    else:
        print(f"  [test FAIL] {str(data)[:300]}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api-key", default=os.environ.get("MODEL_API_KEY", ""), help="模型 API Key")
    ap.add_argument("--api-url", default=os.environ.get("MODEL_API_URL", ""), help="模型 API URL")
    ap.add_argument("--model", default="", help="指定测试的模型名(可选)")
    args = ap.parse_args()

    print("=" * 60)
    print("  模型供应商鉴权配置")
    print("=" * 60)

    if not args.api_key:
        print("[warn] 未提供 API Key —— 预置模型将无法调用(模型广场仅展示)。")
        print("       请在前端「模型管理」配置供应商鉴权，或启动时填入 API Key + URL。")
        return

    print(f"[config] api_url={args.api_url or '(用各模型预置 URL)'}")
    try:
        ws = workspace_id()
        print(f"[workspace] {ws}")
    except Exception as e:
        print(f"[FATAL] 无法连接 manager 或初始化工作空间: {e}")
        sys.exit(1)

    # 1. 创建鉴权配置(provider 100)
    if not create_auth_config(ws, args.api_key):
        print("[warn] 鉴权配置失败，模型仍不可调用")
        return
    # 2. 绑定模型 + online
    update_models(args.api_url)
    # 3. 找目标模型(api_url 匹配)并把所有 agent 的 IR 模型改成它 —— 直接体验用
    target = find_test_model(args.api_url, args.model)
    if target:
        rebind_agent_models(target[0], target[1])
    # 4. 端到端测试(模型调用)
    row = find_test_model(args.api_url, args.model)
    if row:
        print(f"\n[test] 端到端调用验证...")
        test_chat(ws, row[0], row[1])
    else:
        print("[test] 无可测试的 LLM 模型")
    print("=" * 60)


if __name__ == "__main__":
    main()
