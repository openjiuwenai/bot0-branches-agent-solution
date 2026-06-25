# agent-solution

> TODO: 项目简介

## 快速开始

本项目临时通过 git submodule 引入 `agent-runtime-java`：

```text
vendor/agent-runtime-java
```

当前 submodule 来源和跟踪分支以 [.gitmodules](D:/Code/openJiuwen/agent-solution/.gitmodules) 为准：

```text
url = https://gitcode.com/x00550472/agent-runtime-java
branch = feature/a2a-design
```

首次拉取或 CI 构建前执行：

```powershell
.\scripts\update-agent-runtime.ps1
```

脚本会先根据 `.gitmodules` 同步 submodule URL，更新 `vendor/agent-runtime-java` 到配置分支的最新源码，然后执行 Maven install：

```powershell
mvn install -Dmaven.compiler.release=17 -Dmaven.compiler.source=17 -Dmaven.compiler.target=17 -DskipTests
```

如果需要执行测试：

```powershell
.\scripts\update-agent-runtime.ps1 -WithTests
```

如果需要指定 Maven 本地仓库位置：

```powershell
.\scripts\update-agent-runtime.ps1 -LocalRepository "D:\path\to\.m2\repository"
```

## 项目结构

```text
.
|-- README.md
|-- LICENSE
|-- scripts/
|   `-- update-agent-runtime.ps1
`-- vendor/
    `-- agent-runtime-java
```

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
