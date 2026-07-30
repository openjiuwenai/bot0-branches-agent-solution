# agent-core-ext-java 冒烟测试脚本

## 目标

为 `common/agent-core-ext-java` 模块新增冒烟测试脚本 `smoke.sh`，执行 Maven 单元测试并在终端输出格式化冒烟报告。

## 设计方案

### 位置

`common/agent-core-ext-java/smoke.sh`

### 流程

1. 记录开始时间
2. 从 `pom.xml` 提取项目名、版本、核心依赖
3. 执行 `mvn -f common/agent-core-ext-java/pom.xml clean test`
4. 扫描各子模块 `target/surefire-reports/TEST-*.xml`
5. 用内嵌 python3 脚本解析 XML，提取用例明细
6. 打印三块报告：执行总体信息、执行摘要、用例明细
7. 按 mvn 退出码退出

### 依赖

- bash
- mvn
- python3（标准库 `xml.etree.ElementTree`）

### 报告格式

#### 一、执行总体信息

- 项目名称（含版本）
- 子模块列表
- 执行时间
- 耗时
- 构建结果（通过/失败 + 退出码）
- 核心依赖

#### 二、执行摘要

- 总用例数
- 通过
- 失败
- 跳过
- 通过率（百分比）

#### 三、用例明细

每行一条，格式：`[状态] 类名 - 用例名`，失败/跳过附加原因。

状态用 ANSI 颜色区分（绿色 PASS、红色 FAIL、黄色 SKIP），自动检测 tty。

### 测试补充

当前 `react-rails` 子模块已有 111 个用例（单元 + E2E），覆盖率充足，无需新增测试。

若后续新增子模块没有测试类，脚本会自动报告空模块并提示需要补充。

### 错误处理

- `mvn test` 即使失败也继续解析报告（部分测试可能已执行）
- 最终退出码与 mvn 一致，确保 CI 可感知

## 验收标准

- [x] `./smoke.sh` 可执行，运行 `mvn test` 后打印格式化报告
- [x] 报告包含项目信息、依赖、耗时、结果
- [x] 报告包含摘要（总/通过/失败/跳过/通过率）
- [x] 报告包含逐用例明细
- [x] 脚本退出码与 mvn 一致
- [x] 无测试缺失 — 现有 111 用例全部通过
