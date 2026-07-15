# Skill 挂载根目录（skills_root）

业务 Agent 将容器内 `skills/` 目录**绑定挂载**到本目录下以 `agent_name` 命名的子目录。

## 布局

```text
data/skills/
  edp_agent/                    # 已配置于 agent_adapter_config.yaml
    product_recommend_skill/
    interact_finance_rec_skill/
    product_select_skill/
    fund_planning_skill/
  demo_agent/                   # 样例 Agent（未接入配置，仅供本地演示）
    greeting_skill/
    faq_skill/
  {agent_name}/
    {skill_name}/
      SKILL.md
      scripts/          # 可选
```

## EDPAgent 示例

宿主机路径（示例）：

```text
agent-store/community/EDPAgent/skills/  →  data/skills/edp_agent/
```

Docker：

```text
-v .../EDPAgent/skills:/app/data/skills/edp_agent:rw
```

配置 `agents[].name` 必须为 `edp_agent`，与契约中 `agent_name` 一致。

## 注意

- Adapter 通过读写此目录实现 `skill_list` / `skill_content` / `update_skill`。
- 与 edpagent、jiuwenbox **共享同一宿主机目录**时，热更新对 `read_file` / 沙箱脚本生效。
- 目录内实际 skill 内容通常来自挂载，**不提交到 git**（见仓库 `.gitignore`）。

详见：`docs/skills-storage-design.md`
