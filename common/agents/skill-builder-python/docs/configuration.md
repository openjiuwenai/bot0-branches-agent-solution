# 配置参考

Skill Builder 从宿主进程环境读取配置，不会自动加载 `.env`。部署系统必须在构造 `SkillBuilderClient` 前注入普通配置和密钥，Agent Core 子进程会继承这些变量。

## 安装组合

```bash
# 只使用状态加载、验收和打包
python -m pip install openjiuwen-skill-builder

# 使用 OpenJiuwen Agent Core 生成
python -m pip install 'openjiuwen-skill-builder[agent-openjiuwen-python]'

# 可选 Playwright 材料录屏
python -m pip install 'openjiuwen-skill-builder[recording]'
python -m playwright install chromium

# 生成和录屏全部安装
python -m pip install 'openjiuwen-skill-builder[full]'
```

精简 Linux 或容器环境还需要 Chromium 系统依赖，可在镜像构建阶段执行 `python -m playwright install --with-deps chromium`。

OpenJiuwen Python adapter 当前支持并锁定 `openjiuwen==0.1.12`。版本升级应作为单独的兼容性变更处理。

## 模型必需配置

`build`、模型驱动的 `run_turn` 和 `repair` 需要以下模型配置。纯 Core 的 load、结构验收和打包不应依赖模型配置。

| 变量 | 必填 | 默认值 | 说明 |
|---|---|---:|---|
| `SKILL_BUILDER_LLM_API_BASE` | 是 | 无 | OpenAI-compatible 模型地址 |
| `SKILL_BUILDER_LLM_API_KEY` | 是 | 无 | 模型密钥，只能通过安全环境注入 |
| `SKILL_BUILDER_LLM_MODEL` | 是 | 无 | 宿主配置的模型名称 |
| `SKILL_BUILDER_LLM_PROVIDER` | 否 | `OpenAI` | Adapter 使用的 provider 标识 |
| `SKILL_BUILDER_LLM_TIMEOUT_SECONDS` | 否 | `120` | 单次模型 HTTP 请求超时 |
| `SKILL_BUILDER_LLM_MAX_TOKENS` | 否 | `16384` | 默认输出 token 预算 |
| `SKILL_BUILDER_LLM_MAX_REQUEST_BYTES` | 否 | `524288` | 完整序列化请求字节上限 |
| `SKILL_BUILDER_LLM_REQUEST_HEADROOM_RATIO` | 否 | `0.8` | 实际可用请求预算比例 |
| `SKILL_BUILDER_LLM_TEMPERATURE` | 否 | `0.2` | temperature |
| `SKILL_BUILDER_LLM_TOP_P` | 否 | `0.9` | top-p |

## 分阶段模型配置

| 变量 | 示例 | 说明 |
|---|---:|---|
| `SKILL_BUILDER_LLM_ENABLE_THINKING` | `auto` | 默认 thinking 控制；`auto` 表示不发送该参数 |
| `SKILL_BUILDER_LLM_SCENARIO_ENABLE_THINKING` | `false` | Scenario 覆盖值 |
| `SKILL_BUILDER_LLM_AUTHOR_ENABLE_THINKING` | `false` | Author 覆盖值 |
| `SKILL_BUILDER_LLM_REPAIR_ENABLE_THINKING` | `true` | Repair 覆盖值 |
| `SKILL_BUILDER_LLM_SCENARIO_MAX_TOKENS` | `8192` | Scenario 输出上限 |
| `SKILL_BUILDER_LLM_AUTHOR_MAX_TOKENS` | `12288` | Author 输出上限 |
| `SKILL_BUILDER_LLM_REPAIR_MAX_TOKENS` | `8192` | Repair 输出上限 |

模型参数由宿主配置。Skill Builder 不限制为单一模型，但宿主应对模型专用参数做兼容测试。

## Jiuwenbox

Jiuwenbox 是独立服务，默认 adapter 使用它完成 Agent workspace 操作和最终 Acceptance 执行。

| 变量 | 默认值 | 说明 |
|---|---:|---|
| `SKILL_BUILDER_SANDBOX_ENABLED` | 代码默认 `false`，示例为 `true` | Agent worker 是否创建 Jiuwenbox workspace |
| `SKILL_BUILDER_JIUWENBOX_URL` | `JIUWENBOX_URL` 或 `http://127.0.0.1:8321` | Jiuwenbox 地址 |
| `SKILL_BUILDER_JIUWENBOX_TIMEOUT_SECONDS` | `JIUWENBOX_TIMEOUT_SECONDS` 或 `30` | client 请求超时 |
| `SKILL_BUILDER_SANDBOX_COMMAND_TIMEOUT_SECONDS` | `120` | Agent workspace 命令默认超时 |
| `SKILL_BUILDER_SANDBOX_IO_TIMEOUT_SECONDS` | `20` | 上传、读取和下载超时 |
| `SKILL_BUILDER_SANDBOX_WRITE_TIMEOUT_SECONDS` | `30` | 写入和同步超时 |
| `SKILL_BUILDER_SANDBOX_KEEP` | `false` | 是否为受限诊断保留阶段沙箱 |

生产环境不能在 Jiuwenbox 不可用时静默改为宿主进程执行生成脚本。Core 可以继续执行不需要运行不可信代码的检查，但必需执行证据应按契约标记为未验证或阻断。

平台中立 Jiuwenbox client 也兼容 `JIUWENBOX_URL` 和 `JIUWENBOX_TIMEOUT_SECONDS`。建议优先使用 `SKILL_BUILDER_` 前缀，以便不同产品连接不同实例。

## Agent Core 预算

| 变量 | 示例 | 含义 |
|---|---:|---|
| `SKILL_BUILDER_AGENT_TOTAL_TIMEOUT_SECONDS` | `1200` | 阶段绝对超时兜底 |
| `SKILL_BUILDER_AGENT_CHAT_TIMEOUT_SECONDS` | `120` | 只读问答超时 |
| `SKILL_BUILDER_AGENT_EDIT_TIMEOUT_SECONDS` | `360` | 事务编辑超时 |
| `SKILL_BUILDER_AGENT_SCENARIO_TIMEOUT_SECONDS` | `240` | Scenario 超时 |
| `SKILL_BUILDER_AGENT_AUTHOR_TIMEOUT_SECONDS` | `900` | Author 超时 |
| `SKILL_BUILDER_AGENT_REPAIR_TIMEOUT_SECONDS` | `480` | Repair 超时 |
| `SKILL_BUILDER_AGENT_REPAIR_RESERVE_TIMEOUT_SECONDS` | `180` | 候选提交被拒后的有界预留时间 |
| `SKILL_BUILDER_AGENT_IDLE_TIMEOUT_SECONDS` | `240` | 无流事件超时 |
| `SKILL_BUILDER_AGENT_CHAT_MAX_ITERATIONS` | `6` | Chat 单会话安全上限 |
| `SKILL_BUILDER_AGENT_EDIT_MAX_ITERATIONS` | `12` | Edit 单会话安全上限 |
| `SKILL_BUILDER_AGENT_SCENARIO_MAX_ITERATIONS` | `8` | Scenario 单会话安全上限 |
| `SKILL_BUILDER_AGENT_AUTHOR_MAX_ITERATIONS` | `32` | Author 单会话安全上限 |
| `SKILL_BUILDER_AGENT_REPAIR_MAX_ITERATIONS` | `12` | Repair 单会话安全上限 |
| `SKILL_BUILDER_AUTHOR_SELF_CHECK_MAX_RUNS` | `4` | Author 自检执行上限 |
| `SKILL_BUILDER_MAX_REPAIR_ATTEMPTS` | `1` | 自动机械 Repair 次数，硬范围 `0-1` |

Iteration 不是重试次数。提高 iteration 不会增加 Repair 次数，也不应被用于掩盖无进展循环。

`AgentCoreProcessConfig.timeout_seconds` 是额外的宿主硬超时，通常保持 `None`，让阶段内活动感知超时负责正常运行。设置后，超时或取消会终止子进程。

## Gate 灰度配置

| 变量 | 默认值 | 说明 |
|---|---:|---|
| `SKILL_BUILDER_CAPABILITY_GATE_MODE` | `shadow` | 能力文本启发式 finding |
| `SKILL_BUILDER_DOCUMENTATION_GATE_MODE` | `shadow` | 文档启发式 finding |
| `SKILL_BUILDER_OFFLINE_PROTOCOL_GATE_MODE` | `shadow` | 生成自检协议诊断 |
| `SKILL_BUILDER_HEURISTIC_GATE_MODE` | `shadow` | 旧版共享 fallback |

类型化契约失败、不可用包结构、语法错误、必需重放失败和无效 receipt 不受上述灰度配置影响，始终阻断。启发式规则从 shadow 切换到 enforce 前必须经过代表性样本回归。

## 录屏配置

录屏是可选材料采集能力，不是浏览器真实性验证。

| 变量 | 默认值 | 说明 |
|---|---:|---|
| `PLAYWRIGHT_BROWSERS_PATH` | Playwright 默认值 | 宿主使用的 Chromium 安装/缓存目录 |
| `WEB_RECORDING_HEADLESS` | `auto` | `auto`、`true/headless/viewer` 或 `false/headed/desktop` |
| `WEB_RECORDING_DISPLAY` | `DISPLAY` | headed 模式的 X11 display |
| `WEB_RECORDING_XAUTHORITY` | `XAUTHORITY` 或可读 fallback | X11 授权文件 |
| `WEB_RECORDING_DISPLAY_PROBE_TIMEOUT_SECONDS` | `3` | display 探测超时，范围 1-10 秒 |
| `WEB_RECORDING_WINDOW_WIDTH` | `1280` | 浏览器宽度 |
| `WEB_RECORDING_WINDOW_HEIGHT` | `860` | 浏览器高度 |

详细 API/UI、资产、安全和进程生命周期要求见[录屏接入](recording-integration.md)。

进程布局、Jiuwenbox 启动、systemd/容器边界、健康检查和未来 Runtime 部署见[部署说明](deployment.md)。

## 不通过环境变量表达的宿主配置

宿主还必须单独配置：

- workspace 和 state 根目录；
- 同 workspace 单写锁或 StateStore CAS；
- Agent worker 并发和宿主任务队列；
- 材料类型/大小策略和二进制预处理；
- 模型数据地域、保留期限和用户授权策略；
- Jiuwenbox CPU、内存、网络策略和健康检查；
- 事件保留与敏感 payload 访问权限；
- 对象存储、导出、审核和外部发布；
- 录屏 URL/域名策略和资产保留期限。

这些属于宿主职责，不应变成隐藏的 Skill Builder 业务规则。

## 密钥

禁止把凭据写入源码、workspace 材料、worker 请求/结果或事件 JSON。通过宿主进程环境或密钥管理系统注入。Agent Core 子进程继承环境，但事件和结果序列化不得回显密钥。

录屏 browser profile 和 `storage-state.json` 也可能包含会话凭据，应按密钥文件管理。
