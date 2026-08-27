# coding: utf-8

"""FEAT-017 的领域层：事件信封、事件族、准入记录、投影事件。

**零外部依赖**——本包只用标准库。权威
`Technical-AF/docs/develop/02-features/`CL-fb7d2e87b0a9``
是 MUST：接口与业务处理器不得依赖 RocketMQ、Kafka、topic、offset、consumer group、
broker 重试或 outbox 表。依赖一旦从这一层漏进来，换 broker 就要改领域代码。

判据 `agent_runtime/tests/test_bus_domain.py` 的 `TestDomainPurity` 按 AST 判导入面，
并对字段名做 broker 概念黑名单——**类型上看不出来的那一类泄漏**（一个 `str` 字段
叫 `topic`，静态检查一句话都不会说）由后者兜。
"""
