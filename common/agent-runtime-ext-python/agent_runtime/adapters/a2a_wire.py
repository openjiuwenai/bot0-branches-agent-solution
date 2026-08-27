# coding: utf-8

"""A2A wire 上与上游共用的元数据键。

## 为什么在适配层的根，而不在领域层、也不在任一侧适配器内

**不在领域层**：它是 A2A artifact 元数据的键面约定，取值含协议实现名——领域层的准入
是「L1 权威定义的领域对象」，wire 键不在其列；上游也把它放在 A2A 控制器层
（`A2aPartContent`），不放 `spec/dto/QueryChunk`。

**不在任一侧适配器内**：入站 A2A 执行器给终答 artifact 打标、出站远端客户端识标、
远端事件轨在多跳投射时剥标——三处共用一个来源。放进任一侧，其余两侧就要横向依赖它，
那是同层横向依赖（`layer_lateral_guard` 禁止）。

故落在适配层的根，与 `shadow.py`（影子任务标识）、`task_state_mapping.py` 同档：
**跨适配器共用的约定**。
"""
from __future__ import annotations

#: 终答 artifact 的终态标记键，**逐字取自上游** `A2aPartContent.TERMINAL_RESULT_METADATA`
#: （`openJiuwen/agent-runtime-java` 的 `service/agent-service-app` 的 A2A 控制器层）。
#: 上游远端客户端 `extractTaskResult` 两级取结果的第一级只认这个键；多跳投射时上游
#: `RemoteInvocationBatchCoordinator.forwardRemoteArtifact` 剥的也是它。
TERMINAL_RESULT_META_KEY = "_agentcore_terminal"
