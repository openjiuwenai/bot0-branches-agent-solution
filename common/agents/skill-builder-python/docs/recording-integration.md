# 录屏接入

## 能力范围

`skill_builder.recording` 是可选材料采集运行时，用于记录用户展示的网页流程并生成 Scenario 可消费的 Markdown。

它不是：

- `SkillBuilderClient.build` 的默认生命周期阶段；
- Jiuwenbox Sandbox；
- 生成 Skill 的浏览器真实性验证；
- 宿主 HTTP/UI 实现；
- 跨宿主进程重启的持久录屏协调器。

## 安装

```bash
python -m pip install 'openjiuwen-skill-builder[recording]'
python -m playwright install chromium
```

第一条命令安装 Playwright Python 包，第二条命令下载代码实际使用的 Playwright Chromium。Skill Builder 调用 `playwright.chromium`，不要求额外安装 Google Chrome。

精简 Linux 或容器环境还需要浏览器系统依赖，可在镜像构建阶段执行：

```bash
python -m playwright install --with-deps chromium
```

执行 `playwright install chromium` 的 Python 环境和 `PLAYWRIGHT_BROWSERS_PATH` 必须与宿主进程一致。

## 配置

| 变量 | 默认值 | 作用 |
|---|---:|---|
| `PLAYWRIGHT_BROWSERS_PATH` | Playwright 默认值 | Chromium 缓存/安装目录 |
| `WEB_RECORDING_HEADLESS` | `auto` | `auto`、`true/headless/viewer` 或 `false/headed/desktop` |
| `WEB_RECORDING_DISPLAY` | `DISPLAY` | headed 模式 X11 display |
| `WEB_RECORDING_XAUTHORITY` | `XAUTHORITY` 或可读 fallback | X11 授权文件 |
| `WEB_RECORDING_DISPLAY_PROBE_TIMEOUT_SECONDS` | `3` | X11 探测超时，范围 1-10 秒 |
| `WEB_RECORDING_WINDOW_WIDTH` | `1280` | 浏览器窗口宽度 |
| `WEB_RECORDING_WINDOW_HEIGHT` | `860` | 浏览器窗口高度 |

headless/viewer 模式下，宿主展示 `capture_recording_frame()` 返回的画面并发送明确操作；headed 模式下用户操作可见浏览器，宿主仍可轮询状态和画面。

## 职责边界

Skill Builder 录屏核心负责：

- HTTP/HTTPS URL 基础格式校验；
- 创建 Playwright context；
- 捕获页面事件、截图、下载和 trace；
- 对 Markdown 事件中的密码类输入做尽力脱敏；
- 生成最终 `web-recording.md`；
- 正常 stop 和 Playwright 清理。

宿主 adapter 负责：

- 带鉴权的开始、画面、操作、停止 API 和 UI；
- workspace/租户权限；
- URL、DNS、代理、域名和网络出站策略；
- Chromium 安装及 display/X11 配置；
- 资产登记、对象存储、保留期限和访问控制；
- 任务取消、workspace 删除和宿主停止时的清理；
- 将 Markdown 加入下一次材料聚合。

内置 URL 校验只检查 HTTP/HTTPS 格式，不是 SSRF 防护或域名白名单。生产宿主必须在调用 `start_recording` 和 `navigate` 前执行网络策略。

## 公共 API

```python
from skill_builder.recording import (
    RecordingAction,
    capture_recording_frame,
    get_active_recording,
    perform_recording_action,
    recording_snapshot,
    start_recording,
    stop_recording,
)
```

### 开始录屏

```python
recording, capability = await start_recording(
    root=workspace_root,
    workspace_id=workspace_id,
    start_url="https://approved.example/app",
    title="Sample workflow",
    goal="Demonstrate the normal flow",
)

response = {
    **recording_snapshot(recording),
    "display_capability": capability,
}
```

同一宿主进程中，每个 `workspace_id` 只允许一个活动录屏。

### 查询状态和画面

```python
recording = get_active_recording(workspace_id)
status = recording_snapshot(recording) if recording is not None else None

png = await capture_recording_frame(
    workspace_id=workspace_id,
    recording_id=recording_id,
)
```

宿主可把 `png` 作为鉴权图片响应返回，不能默认发布为公共静态资源。

### 执行 viewer 操作

支持 `click`、`type`、`press`、`scroll`、`navigate` 和 `refresh`：

```python
recording = await perform_recording_action(
    root=workspace_root,
    workspace_id=workspace_id,
    recording_id=recording_id,
    action=RecordingAction(action="click", x=420, y=260),
)
```

风险或不可逆动作必须先获得用户明确授权。录屏 API 只记录用户演示，不授予业务操作权限。

### 停止并登记材料

```python
recording, markdown = await stop_recording(
    root=workspace_root,
    workspace_id=workspace_id,
    recording_id=recording_id,
)

material_path = workspace_root / recording.material_path
assert material_path.read_text(encoding="utf-8") == markdown
```

最终材料路径：

```text
inputs/external-sources/<recording_id>/web-recording.md
```

宿主把该路径登记为 Markdown 材料，并纳入传给 `SkillBuilderInput` 的材料索引/`materials_markdown`。截图、下载、trace、profile 和 storage state 是诊断资产，除非宿主明确预处理和授权，否则不作为模型材料。

## Workspace 产物

```text
playwright/
├── profile/
├── storage-state.json
└── recordings/<recording_id>/
    ├── recording.md
    ├── metadata.json
    ├── screenshots/
    ├── downloads/
    └── trace.zip

inputs/external-sources/<recording_id>/
└── web-recording.md
```

`storage-state.json`、截图、下载和 trace 可能包含 cookie、个人数据、内部 URL 或可见密钥，必须限制访问、按需加密并设置删除期限。

密码类输入只在 Markdown 事件记录中尽力脱敏，不会自动遮挡截图或下载文件。

## 建议宿主入口

| 宿主入口 | Recording 调用 | 返回 |
|---|---|---|
| `POST /workspaces/{id}/recording` | `start_recording` | snapshot 和 display capability |
| `GET /workspaces/{id}/recording` | `get_active_recording` + `recording_snapshot` | 当前 snapshot 或空 |
| `GET /workspaces/{id}/recording/frame` | `capture_recording_frame` | 鉴权 PNG |
| `POST /workspaces/{id}/recording/actions` | `perform_recording_action` | 更新后 snapshot |
| `DELETE /workspaces/{id}/recording` | `stop_recording` | 完成 snapshot 和材料路径 |

这些是建议宿主路由，本包不会注册 HTTP route。完成鉴权和审计后，宿主可以映射 `RecordingError.status_code`，但不能向用户暴露原始 Playwright 异常或本地路径。

## 进程生命周期限制

活动 Playwright 对象保存在当前进程的 `_ACTIVE_WEB_RECORDINGS`。宿主重启后，其他进程无法继续原活动录屏。因此宿主必须：

- 把一个录屏的所有操作路由到同一进程；
- 优雅停止时关闭活动录屏；
- 异常重启后把进行中的资产记录标为中断；
- 清理部署级孤立浏览器和临时资产；
- 新建录屏，而不是声称旧录屏已续跑。

这只影响录屏采集。已写入 `inputs/` 的 Markdown 仍是持久材料。

## 与浏览器验证的关系

| 能力 | 目的 | 实现状态 |
|---|---|---|
| 录屏材料采集 | 记录用户展示的网页操作流程，生成 Scenario 可消费的 Markdown | 已实现 |
| 浏览器真实性验证 | 验证生成 Skill 能否在获批外部环境中执行声明的浏览器能力 | 预留独立 Acceptance adapter |

录屏成功只能证明材料采集完成，不能替代生成 Skill 的浏览器真实性验证。
