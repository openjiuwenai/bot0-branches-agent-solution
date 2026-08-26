# 草稿提交清单

写包结束前只检查当前草稿，不运行平台验收：

- 根 `SKILL.md` 存在，UTF-8 可读，并带非空 `name`、`description` frontmatter。
- `name` 是 kebab-case，包内没有第二个 `SKILL.md`。
- 只生成真实需要的 references、scripts、fixtures 和 assets；不生成宿主管理的 `agents/openai.yaml`。
- 包内没有符号链接、平台保留目录、缓存、日志或本次运行输出。
- 文档以导出后的 Skill 根目录为基准，不依赖也不引用 `inputs/`、`validation/`、`workspace/`、`playwright/` 或 `.skill-builder/`；来源事实已归纳到包内 `references/`。
- 文档中提到的包内文件已经存在；脚本、依赖说明和样例没有明显自相矛盾。
- 资源表和反引号中的 `scripts/`、`references/`、`fixtures/`、`assets/` 路径都指向真实文件。
- 生产脚本没有只包含 `pass` 的条件分支；从输入映射读取到局部变量的字段会真实用于调用、分支、输出或断言。
- `scripts/` 下的 Python 文件和顶层包名不与标准库模块同名；不存在 `inspect.py`、`json.py`、`email/` 等会遮蔽依赖导入的路径。
- `author_build` 不生成自检、成组 invalid fixture 或未被生产代码消费的外部响应展示样例；结构化入口已有一份非平台 schema 的业务 happy fixture。`author_validate` 不修改生产文件，只调用 `write_self_check_plan` 生成唯一的 `scripts/self_check.py` 并立即运行。已有 `happy_path`，结构化输入已有平台 `invalid_input`；只有一个 CLI 的真实输出被另一个 CLI 消费时才需要 `file_handoff`；外部入口已有 `external_offline`，任何 HTML/JSON 响应 fixture 都被生产命令真实消费。每个用例都包含安全命令、预期退出码和平台可重新计算的真实输出断言，不为消除 warning 重写已通过文件或弱化断言。
- `happy_path` 只接受退出码 0，使用 Author 创建的业务 fixture 产生至少一条有效业务结果，并断言关键字段或数量；平台 schema `sample-input` 不能作为业务成功证据。`blocked` 只出现在独立的 `external_offline` 降级用例中。
- 对 CSV 或其他列表字段，样例中的分隔符必须与解析器实际使用的分隔符完全一致；不要根据字段名臆测，至少读取样例和解析代码各一次。
- fixture 中的客户、账户、合同、交易、金额和地址等样例值没有作为生产脚本的固定业务结果；脚本从实际输入或页面/API 结果读取这些字段。
- 已读取 `validation/artifact_manifest.json.resolvedCapabilityContract`；其中 `requiredCapabilities=true` 的浏览器/API/通用外部采集能力都有真实入口，且没有通过人工、外部系统边界或否定描述降级。未被结构化契约要求的外部业务系统不自动成为本包能力；纯 SOP/知识 Skill 才将它写为前置条件或人工/外部系统边界，不凭空增加浏览器/API“未验证能力”。
- 浏览器入口实际调用浏览器 launch/navigation/解析，不能只导入 Playwright 后固定返回 `blocked`；同时捕获 Playwright 导入、runtime 初始化、浏览器 launch 和页面执行全链路异常，运行依赖缺失时返回 `blocked`，不崩溃。任何 `success/queried` 结果都不能同时返回空核心字段和“需解析/待解析”占位说明。真实 API 端点没有材料/HITL 证据时必须保持可配置并标记未验证，不能把保留示例域名作为生产默认值。
- 外部响应已被解析到 ScenarioContract 声明的关键业务输出，不是只保存整页文本或 `notes`；受控 fixture 的离线自检会断言成功样例的关键字段非空，生产字段不会永久为空后以“待查询/待确定”交付。
- 每个 HITL 已确认的固定或排他选项已同时落实到正文、生产脚本和自检；没有继续公开其他选项，也没有从用户输入动态覆盖同一固定配置。
- ImplementationPlan 中的 `SKILL.md`、控制器要求的非自检生产脚本、能力入口和必要业务 fixture 已经物化；Author 没有自行选择或改写 `packageKind/scriptsRequired`。普通辅助 reference 可以不保留。可选 implementation evidence 只用于诊断，不影响候选提交。
- 携带数值的枚举确认值（如 `first_50` / 前 50 条）没有被转换成带默认值的可配置参数。
- 当前环境无法执行的已实现能力已如实标为未验证。Author 完成后由控制器自动执行全部预检；只有单一机械根因族且存在明确 targetPaths 时才会进入有界 Repair，第二轮还要求第一轮严格减少同族问题且无新增问题。

提交成功只代表生成预检通过并形成 PackageRevision；最终状态由随后独立运行的交付验收决定，未实际执行的外部系统或业务能力仍不能标记为通过。
