# Git 工作流

## Commit 格式

```
type(scope): description

[optional body]
```

type: `feat` | `fix` | `refactor` | `test` | `docs` | `chore`

## 分支命名

- `feature/<description>` — 新功能
- `fix/<description>` — 修复
- `docs/<description>` — 文档

## 提交前

1. `make lint` 通过
2. `make test` 通过
3. 有意 staging：不要把不相关的改动混在一个 commit 里

## 注意

- 当前主开发分支: `dev_enterprise_evolution`
- Commit message 末尾加 `Co-Authored-By`
