# 南北向对接-北向对接 SkillHub 使用指南

> 本指南介绍如何通过 Adapter 对接 SkillHub 市场：将优化后的 Skill 发布到市场供团队复用；从市场拉取 Skill 作为优化 baseline；以及查询、下载、删除 Skill 版本等管理操作。

---

## 1. 特性概览

### 1.1 这是什么

> **北向对接 SkillHub** 在 Adapter 侧新增了 SkillHub Client 模块，使 EvoAgent 优化链路产出的 Skill 能够发布到 SkillHub 市场，同时支持从市场拉取已有 Skill 作为优化 baseline，供团队复用、版本治理与审核。

机制摘要：

1. 优化完成后，由 EvoAgent 或 Studio 调用 Adapter 的 `publish_skill` 操作；
2. Adapter 将本地 skill 目录打包为 zip，计算 SHA256，上传到 SkillHub；
3. 发布成功后记录 `asset_id` 与版本号，后续可通过 `list_hub_skills` / `get_hub_version` 查询；
4. 其他 Agent 或环境可通过 `pull_skill` 从 Hub 下载 Skill，加载到本地 `skills_dir`；
5. 发布者可通过 `delete_hub_version` 删除指定版本。

优化与发布解耦：训练阶段仍走本地 `SkillStore` 热更（`update_skill`），**不会**每次都上传到 Hub；发布是优化成功后的可选操作。

### 1.2 本指南覆盖范围

本指南包含：

- 5 个 Hub action 的使用方法：`list_hub_skills` / `get_hub_version` / `pull_skill` / `publish_skill` / `delete_hub_version`；
- Adapter 接口参数、响应格式与错误码；
- 部署配置与联调要点；
- 常见问题与故障排查。

本指南不包含：

- SkillHub 服务端的部署与运维；
- Skill 优化器的使用方法；
- SkillHub 前端 UI 操作。

---

## 2. 什么时候使用

| 使用 SkillHub 对接 | 不使用 SkillHub 对接 |
|---|---|
| 已通过 EvoAgent 优化 Skill，需要发布到市场供团队复用 | 仅需本地运行 Skill，不需要团队共享 |
| 需要从市场下载已有的 Skill 作为优化 baseline | Skill 已通过其他方式同步 |
| 需要查询 Skill 的版本历史与审核状态 | 不需要版本治理 |
| 需要删除已发布的 Skill 版本 | 不需要管理市场资产 |

> 简单判断：如果「优化后的 Skill 需要被其他 Agent 或环境使用，或者需要版本管理」，就适合使用本特性。

---

## 3. 准备工作

### 3.1 获取必要信息

开始前，请向平台或部署维护人员确认以下信息：

| 信息 | 示例 | 用途 |
|---|---|---|
| Adapter 地址 | `http://127.0.0.1:8900` | Adapter 对外暴露 Skill 操作的请求入口 |
| SkillHub 地址 | `http://127.0.0.1:8100` | Adapter 对接的 Hub 后端 |
| Agent 名称 | `edp_agent` | 指定操作的目标 Agent |
| Skill 名称 | `demo-skill` | 待发布或操作的 Skill 目录名 |
| 鉴权 Token | `ADAPTER_SKILLHUB_TOKEN` | 与 SkillHub 的 `SYSTEM_ADMIN_TOKEN` 一致 |
| 版本号 | `1.0.0` | 语义版本 `x.y.z`，不含 `v` 前缀 |

后续示例中的 `{...}` 都需要替换为实际值。

### 3.2 检查服务

检查 Adapter 健康状态：

```bash
curl http://127.0.0.1:8900/health
```

预期返回：`{"status": "ok"}`。

检查 SkillHub 健康状态：

```bash
curl http://127.0.0.1:8100/api/health
```

> 如果任一检查失败，请先联系部署维护人员，不要继续操作。

### 3.3 检查 SkillHub 配置

确认 Adapter 的 SkillHub 集成已启用。检查环境变量：

```ini
ADAPTER_SKILLHUB_ENABLED=true
ADAPTER_SKILLHUB_BASE_URL=http://host.docker.internal:8100
ADAPTER_SKILLHUB_AUTH_MODE=system_token
ADAPTER_SKILLHUB_TOKEN=<与 SkillHub SYSTEM_ADMIN_TOKEN 一致>
ADAPTER_SKILLHUB_VERSION_STRATEGY=manual
```

> `version_strategy=manual` 时，`publish_skill` 请求**必须**传 `plugin_version`。

### 3.4 准备本地 Skill 目录

待发布的 Skill 目录须满足以下要求：

```text
{skills_dir}/{agent_name}/{skill_name}/SKILL.md
```

示例：

```text
/data/skills/edp_agent/demo-skill/SKILL.md
```

`SKILL.md` 的 frontmatter 中 `name` 必须与目录名一致：

```markdown
---
name: demo-skill
description: Adapter to SkillHub integration test skill
---

# Demo Skill
```

---

## 4. 快速上手

### 4.1 查询 Hub 上的 Skill 列表

```bash
curl -X POST http://127.0.0.1:8900/api/v1/skills \
  -H "Content-Type: application/json" \
  -d '{
    "agent_name": "edp_agent",
    "action": "list_hub_skills",
    "page": 1,
    "page_size": 20,
    "keyword": "demo-skill"
  }'
```

预期返回：

```json
{
  "page": 1,
  "page_size": 20,
  "total": 1,
  "items": [
    {
      "asset_id": "95c8fce51e9b47e09015356246a5400d",
      "name": "demo-skill",
      "latest_version": "1.0.0",
      "moderation_status": "APPROVED"
    }
  ]
}
```

> 测试客户端**不携带** `X-System-Token`；Adapter 用部署侧注入的 System Token 调 Hub。`keyword` 为空时返回全部 Skill（分页），接头、中、尾部子串均可模糊匹配。

### 4.2 发布 Skill

```bash
curl -X POST http://127.0.0.1:8900/api/v1/skills \
  -H "Content-Type: application/json" \
  -d '{
    "agent_name": "edp_agent",
    "action": "publish_skill",
    "skill_name": "demo-skill",
    "plugin_version": "1.0.1",
    "version_desc": "optimizer run opt-001",
    "force": false
  }'
```

预期返回：

```json
{
  "asset_id": "95c8fce51e9b47e09015356246a5400d",
  "skill_name": "demo-skill",
  "version": "1.0.1",
  "plugin_type": "skill",
  "publish_result": "success",
  "moderation_status": "APPROVED",
  "checksum_sha256": "a1b2c3d4...",
  "version_desc": "optimizer run opt-001",
  "local_revision": "e3b0c44298fc1c149afbf4c8996fb924..."
}
```

### 4.3 查询版本详情

```bash
curl -X POST http://127.0.0.1:8900/api/v1/skills \
  -H "Content-Type: application/json" \
  -d '{
    "agent_name": "edp_agent",
    "action": "get_hub_version",
    "asset_id": "95c8fce51e9b47e09015356246a5400d",
    "version": "1.0.1"
  }'
```

### 4.4 从 Hub 拉取 Skill

```bash
curl -X POST http://127.0.0.1:8900/api/v1/skills \
  -H "Content-Type: application/json" \
  -d '{
    "agent_name": "edp_agent",
    "action": "pull_skill",
    "asset_id": "95c8fce51e9b47e09015356246a5400d",
    "version": "1.0.1",
    "overwrite": true
  }'
```

预期返回：

```json
{
  "asset_id": "95c8fce51e9b47e09015356246a5400d",
  "skill_name": "demo-skill",
  "version": "1.0.1",
  "local_path": "/data/skills/edp_agent/demo-skill",
  "revision": "e3b0c44298fc1c149afbf4c8996fb924..."
}
```

### 4.5 删除 Hub 版本

```bash
curl -X POST http://127.0.0.1:8900/api/v1/skills \
  -H "Content-Type: application/json" \
  -d '{
    "agent_name": "edp_agent",
    "action": "delete_hub_version",
    "asset_id": "95c8fce51e9b47e09015356246a5400d",
    "version": "1.0.1"
  }'
```

预期返回：

```json
{
  "asset_id": "95c8fce51e9b47e09015356246a5400d",
  "version": "1.0.1",
  "deleted": true
}
```

### 4.6 验证是否成功

完成以上步骤后，重点确认：

| 检查项 | 验证方式 |
|---|---|
| 发布成功 | `list_hub_skills` 中能看到新版本 |
| 版本详情正确 | `get_hub_version` 返回的 `moderation_status` 为 `APPROVED` |
| 拉取成功 | `pull_skill` 返回的 `local_path` 非空，本地目录可读 |
| 删除成功 | `delete_hub_version` 返回 `deleted: true` |

> 完成本节后，你应该已经能独立完成一次完整的 Skill 发布、查询、拉取、删除流程。

---

## 5. 接口与配置

### 5.1 接口清单

所有 SkillHub 操作通过 `POST /api/v1/skills` 使用 `action` 字段分发，与本地 Skill 操作并行：

| 方法 | 路径 | action | 作用 |
|:--:|---|---|---|
| `POST` | `/api/v1/skills` | `list_hub_skills` | 分页查询 Hub 上的 Skill 列表 |
| `POST` | `/api/v1/skills` | `get_hub_version` | 查询指定版本详情 |
| `POST` | `/api/v1/skills` | `pull_skill` | 从 Hub 下载并解压 Skill 到本地 |
| `POST` | `/api/v1/skills` | `publish_skill` | 将本地 Skill 打包发布到 Hub |
| `POST` | `/api/v1/skills` | `delete_hub_version` | 删除 Hub 上的指定版本 |

### 5.2 核心请求参数

#### list_hub_skills

| 字段 | 必填 | 说明 |
|---|:--:|---|
| `agent_name` | 是 | Adapter 侧业务 Agent 名称 |
| `action` | 是 | 固定为 `list_hub_skills` |
| `page` | 否 | 页码，默认 `1` |
| `page_size` | 否 | 每页数量，默认 `20`，最大 `200` |
| `keyword` | 否 | 搜索关键词 |

#### get_hub_version

| 字段 | 必填 | 说明 |
|---|:--:|---|
| `agent_name` | 是 | Adapter 侧业务 Agent 名称 |
| `action` | 是 | 固定为 `get_hub_version` |
| `asset_id` | 是 | SkillHub 资产 ID |
| `version` | 是 | 语义版本号 `x.y.z` |

#### pull_skill

| 字段 | 必填 | 说明 |
|---|:--:|---|
| `agent_name` | 是 | Adapter 侧业务 Agent 名称 |
| `action` | 是 | 固定为 `pull_skill` |
| `asset_id` | 是 | SkillHub 资产 ID |
| `version` | 是 | 目标版本号 |
| `overwrite` | 否 | 是否覆盖本地同名目录，默认 `true` |

#### publish_skill

| 字段 | 必填 | 说明 |
|---|:--:|---|
| `agent_name` | 是 | Adapter 侧业务 Agent 名称 |
| `action` | 是 | 固定为 `publish_skill` |
| `skill_name` | 是 | 本地 skill 目录名 |
| `plugin_version` | 条件 | `version_strategy=manual` 时必填，格式 `x.y.z`；`version_strategy=patch` 时自动递增，无需传 |
| `asset_id` | 条件 | 发布已有 Skill 的新版本时必填（用于定位已有资产）；首次发布可省略 |
| `version_desc` | 否 | 版本说明，如优化 run_id 摘要 |
| `force` | 否 | 是否强制覆盖同版本已有包体，默认 `false`。`force=true` 操作不可逆，请谨慎使用 |

#### delete_hub_version

| 字段 | 必填 | 说明 |
|---|:--:|---|
| `agent_name` | 是 | Adapter 侧业务 Agent 名称 |
| `action` | 是 | 固定为 `delete_hub_version` |
| `asset_id` | 是 | SkillHub 资产 ID |
| `version` | 是 | 待删除的版本号 |

### 5.3 配置项

#### Adapter 侧环境变量

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `ADAPTER_SKILLHUB_ENABLED` | `false` | 开启 SkillHub 集成；关闭时 Hub action 返回 503 |
| `ADAPTER_SKILLHUB_BASE_URL` | — | SkillHub 后端根地址，不含 `/api/v1` |
| `ADAPTER_SKILLHUB_AUTH_MODE` | `system_token` | 鉴权方式：`system_token` 或 `bearer` |
| `ADAPTER_SKILLHUB_TOKEN` | — | 鉴权 Token（推荐直接配置） |
| `ADAPTER_SKILLHUB_TOKEN_ENV` | `SKILLHUB_TOKEN` | 未设 token 时从此环境变量读取 |
| `ADAPTER_SKILLHUB_VERSION_STRATEGY` | `manual` | `manual` 时请求须传 `plugin_version`；`patch` 时自动递增 |
| `ADAPTER_SKILLHUB_CONNECT_TIMEOUT` | `30` | 查询类 API 超时（秒） |
| `ADAPTER_SKILLHUB_PUBLISH_TIMEOUT` | `120` | 发布与下载超时（秒） |
| `ADAPTER_SKILLHUB_DEFAULT_PLUGIN_TYPE` | `skill` | `list_hub_skills` 默认类型 |

#### SkillHub 侧配置（对接 Adapter 时必填）

| 配置项 | 说明 |
|---|---|
| `SYSTEM_ADMIN_TOKEN` | 与 `ADAPTER_SKILLHUB_TOKEN` **保持一致** |
| `SYSTEM_ADMIN_USER` | System Token 请求的身份，如 `system_admin` |

### 5.4 响应格式

#### 统一错误 envelope

所有错误响应均采用统一格式：

```json
{
  "error": {
    "code": "HUB_NOT_FOUND",
    "message": "SkillHub resource not found"
  },
  "detail": "SkillHub resource not found"
}
```

### 5.5 状态码与错误码

| 状态码 | 错误码 | 含义 | 处理方式 |
|---|---|---|---|
| 200 | — | 操作成功 | 正常处理 |
| 400 | `INVALID_ACTION` | 请求参数校验失败 | 检查必填字段和参数格式 |
| 401 | `HUB_AUTH_FAILED` | SkillHub 鉴权失败 | 检查 Token 配置 |
| 404 | `HUB_NOT_FOUND` | 资产或版本不存在 | 确认 `asset_id` 和 `version` |
| 404 | `AGENT_NOT_FOUND` | Agent 名称不存在 | 检查 `agent_name` |
| 409 | `HUB_CONFLICT` | 同版本不同包体冲突 | 使用 `force=true` 或换个版本号 |
| 500 | `INTERNAL_ERROR` | 服务端内部错误 | 查看 Adapter 日志 |
| 503 | `SKILLHUB_DISABLED` | SkillHub 集成未启用 | 检查 `ADAPTER_SKILLHUB_ENABLED` |

---

## 6. 场景用例

### 6.1 基础用例：首次发布 Skill

适用于：优化完成后，首次将 Skill 发布到 Hub。

```bash
curl -X POST http://127.0.0.1:8900/api/v1/skills \
  -H "Content-Type: application/json" \
  -d '{
    "agent_name": "edp_agent",
    "action": "publish_skill",
    "skill_name": "demo-skill",
    "plugin_version": "1.0.0",
    "version_desc": "initial release from optimizer"
  }'
```

完成后记录返回的 `asset_id`，后续发新版时需要传入。

### 6.2 进阶用例：发布新版本

适用于：已有 `asset_id`，需要为同一 Skill 发布新版本。

```bash
curl -X POST http://127.0.0.1:8900/api/v1/skills \
  -H "Content-Type: application/json" \
  -d '{
    "agent_name": "edp_agent",
    "action": "publish_skill",
    "skill_name": "demo-skill",
    "plugin_version": "1.0.1",
    "asset_id": "95c8fce51e9b47e09015356246a5400d",
    "version_desc": "TF-GRPO optimized, run_id=opt-002",
    "force": false
  }'
```

### 6.3 进阶用例：从 Hub 拉取 Skill 作为 baseline

适用于：优化前从 Hub 获取最新 Skill 作为起始版本。

```bash
curl -X POST http://127.0.0.1:8900/api/v1/skills \
  -H "Content-Type: application/json" \
  -d '{
    "agent_name": "edp_agent",
    "action": "pull_skill",
    "asset_id": "95c8fce51e9b47e09015356246a5400d",
    "version": "1.0.0",
    "overwrite": true
  }'
```

### 6.4 进阶用例：强制覆盖同版本

适用于：需要替换已发布版本的包体内容。

```bash
curl -X POST http://127.0.0.1:8900/api/v1/skills \
  -H "Content-Type: application/json" \
  -d '{
    "agent_name": "edp_agent",
    "action": "publish_skill",
    "skill_name": "demo-skill",
    "plugin_version": "1.0.0",
    "asset_id": "95c8fce51e9b47e09015356246a5400d",
    "force": true
  }'
```

> 注意：`force=true` 会覆盖同版本已有包体，操作不可逆，请谨慎使用。

### 6.5 编程用例：通过 EvoAgent AdapterClient 调用

EvoAgent 的 `AdapterClient` 封装了相同的 Hub 操作：

```python
from evo_agent.adapter_client import AdapterClient

client = AdapterClient(adapter_url="http://127.0.0.1:8900", agent_name="edp_agent")

# 查询列表
data = await client.list_hub_skills(keyword="demo-skill")

# 发布
result = client.publish_skill(
    skill_name="demo-skill",
    plugin_version="1.0.1",
    version_desc="optimized by TF-GRPO",
)

# 拉取
data = await client.pull_skill("asset_id", "1.0.0")

# 删除
data = await client.delete_hub_version("asset_id", "1.0.0")
```

---

## 7. 常见问题

### 7.1 故障排查表

| 现象 | 常见原因 | 处理方式 |
|---|---|---|
| 返回 503 `SKILLHUB_DISABLED` | `ADAPTER_SKILLHUB_ENABLED` 未设为 `true` | 检查环境变量并重启 Adapter |
| 返回 401 `HUB_AUTH_FAILED` | Token 不匹配或过期 | 确认 `ADAPTER_SKILLHUB_TOKEN` 与 SkillHub 的 `SYSTEM_ADMIN_TOKEN` 一致 |
| `publish_skill` 返回 400 缺 `plugin_version` | `version_strategy=manual` 时未传该字段 | 请求中增加 `plugin_version`，格式 `x.y.z` |
| 返回 409 `HUB_CONFLICT` | 同版本号但包体不同 | 使用 `force=true` 或换个版本号 |
| 同版本重复发布返回 200 | checksum 相同，Hub 视为幂等重试 | 正常行为，无需处理 |
| `pull_skill` 下载失败 | 容器无法解析 MinIO 预签名 URL | 在 `docker-compose.yml` 中增加 `skillhub.local:host-gateway` |
| 发布后 `moderation_status` 为 `PENDING` | 审核未完成 | 稍后通过 `get_hub_version` 轮询状态 |
| Hub 不可用 | 网络不通或 Hub 宕机 | 不影响本地 `update_skill` 热更，等待 Hub 恢复后重试 |

### 7.2 常见问答

#### Q：`asset_id` + `version` 和 `skill_name` 有什么区别？

**结论：`asset_id` + `version` 在 Hub 上全局唯一地标识一份 Skill 包；`skill_name` 仅在同一发布者下唯一。**

查询、拉取、删除等操作必须使用 `asset_id` + `version`，不要只凭 `skill_name` 定位。

#### Q：优化中每次 `update_skill` 都会发布到 Hub 吗？

**结论：不会。优化与发布解耦。**

训练阶段仍写本地 `SkillStore`，Hub 仅在显式调用 `publish_skill` 时触发。Hub 不可用不会阻断优化流程。

#### Q：`version_strategy=patch` 和 `manual` 有什么区别？

**结论：`patch` 自动在 Hub 最新已通过版本上 patch+1；`manual` 要求调用方显式传入 `plugin_version`。**

当前部署默认 `manual`，请求中必须传 `plugin_version`。

#### Q：发布后 Skill 是否立即可被其他 Agent 下载？

**结论：取决于审核状态。**

- `moderation_status=APPROVED`：市场可见，可下载；
- `moderation_status=PENDING`：仅发布者可见，其他用户下载可能返回 404。

内网部署可通过 `MARKET_SKILL_REVIEW_ENABLED=false` 配合 System Token 实现直接 `APPROVED`。

#### Q：同版本号重复发布会发生什么？

**结论：分两种情况。**

- 包体内容相同（checksum 一致）：Hub 返回 200，视为幂等重试，不覆盖存储；
- 包体内容不同且 `force=false`：返回 409 `HUB_CONFLICT`。

#### Q：本地 `revision`（SHA256）和 Hub `version`（x.y.z）是什么关系？

**结论：两者独立。**

- 本地 `revision` 是 `SKILL.md` 内容的 SHA256 哈希，用于热更幂等；
- Hub `version` 是语义版本号，用于资产治理。

`publish_skill` 返回结果中同时包含 `checksum_sha256` 和 `local_revision`，可追溯映射关系。

#### Q：`list_hub_skills` 能看到哪些 Skill？只能看到自己发布的吗？

**结论：取决于 SkillHub 的权限配置。**

Adapter 使用 System Token 调用 Hub，以 `system_admin` 身份访问。在未配置 Group ACL 的内网环境中，`list_hub_skills` 返回市场全部已审核 Skill；如果配置了发布者隔离，则仅返回当前 Token 对应发布者的 Skill。具体权限请咨询 SkillHub 管理员。

#### Q：`pull_skill` 下载后 Skill 放在哪个目录？可以配置吗？

**结论：可配置，默认路径为 `{skills_root}/{agent_name}/{skill_name}`。**

路径由 Adapter 配置中对应 Agent 的 `skills_dir` 决定。如果不指定 `skills_dir`，则回退到 `{skills_root}/{agent_name}`。下载后 Skill 解压到该目录下以 `skill_name` 命名的子目录中。

#### Q：`keyword` 搜索是精确匹配还是模糊匹配？

**结论：模糊匹配（子串匹配），关键词在 Skill 名称的头部、中部、尾部均可命中。**

`keyword` 实际映射为 SkillHub 的 `search_keyword` 参数，后端对 Skill 的 `name`、`display_name`、`short_desc`、`detail_desc` 四个字段做 `ILIKE '%keyword%'` 模糊搜索。`keyword` 为空时返回全部 Skill（分页）。

#### Q：`get_hub_version` 只能通过 `asset_id` 查询吗？能否通过名称查？

**结论：当前版本仅支持 `asset_id` 查询。**

`get_hub_version` 需要 `asset_id` + `version` 精确定位。如需通过名称获取 `asset_id`，请先使用 `list_hub_skills` 加 `keyword` 搜索，从返回结果中获取 `asset_id`。

#### Q：`publish_skill` 的 `force` 字段是什么意思？

**结论：`force=true` 强制覆盖同版本已有包体，`force=false` 时同版本不同包体返回 409 冲突。**

发布时 Hub 会对比 SHA256 校验和。相同包体重复发布视为幂等重试（返回 200），不同包体同版本号时：`force=false` 返回 `HUB_CONFLICT`；`force=true` 覆盖已有包体。注意 `force=true` 操作不可逆，请谨慎使用。

#### Q：`plugin_version` 是指谁的版本号？

**结论：指 Skill 在 Hub 上的语义版本号（`x.y.z`），由发布者指定。**

与代码中的 `local_revision`（SKILL.md 内容的 SHA256 哈希）无关。`plugin_version` 用于 SkillHub 市场的版本治理，`local_revision` 用于本地热更的幂等校验。

#### Q：Token 过期了怎么办？是否需要重启 Adapter？

**结论：当前 Token 过期后需要更新配置并重启 Adapter。**

Token 通过环境变量 `ADAPTER_SKILLHUB_TOKEN` 注入，Adapter 启动时一次性读取。如需更换 Token，修改环境变量后重启 Adapter 即可。建议使用长期有效的 System Token，避免频繁更换。

#### Q：沙箱模式（`skill_backend=jiuwenbox`）下 `publish_skill` 能正常工作吗？

**结论：需要确保待发布 Skill 在 Adapter 本地 `skills_dir` 下存在。**

`publish_skill` 直接从 Adapter 本地文件系统读取 Skill 目录（路径 `{skills_dir}/{agent_name}/{skill_name}/SKILL.md`），不经过 SkillStore 的沙箱文件 API。沙箱模式下，如果 Skill 仅存在于沙箱内而未同步到 Adapter 本地目录，`publish_skill` 会因找不到 `SKILL.md` 而失败。发布前请先通过 `pull_skill` 将 Skill 从 Hub 拉取到本地，或确保 Adapter 的 `skills_dir` 与沙箱内 Skill 目录保持同步（如通过 bind-mount 或主动同步）。

---

## 8. 相关文档

| 文档 | 说明 |
|---|---|
| [evoagent-adapter 对接 SkillHub 开发串讲](../../test_docs/822版本/【智能体自进化】Skill发布-对接SkillHub/evoagent-adapter对接SkillHub_开发串讲.md) | 方案设计、接口映射与联调要点 |
| [evoagent-adapter 对接 SkillHub 测试用例](../../test_docs/822版本/【智能体自进化】Skill发布-对接SkillHub/evoagent-adapter对接SkillHub_测试用例.md) | 分层测试用例 |
| SkillHub API 参考 | `skillhub/docs/zh/7. API参考/TeamSkillsHub-接口参考.md` |
| Skill 发布用户指南 | `skillhub/docs/zh/4. 用户指南/发布Skill.md` |
| Adapter 配置模板 | `evoagent-adapter/deployment/config/.env.example` |
| 端到端测试脚本 | `evoagent-adapter/deployment/scripts/e2e_skillhub_test.py` |