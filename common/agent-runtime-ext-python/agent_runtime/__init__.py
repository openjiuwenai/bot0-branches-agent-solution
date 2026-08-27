# coding: utf-8

"""agent-runtime Python 版（TO-BE）。

按洋葱架构组织：领域核心在最内层且零框架依赖，一层层向外是 application、
ports、adapters，依赖只能从外向内（adapters→ports→application→domain）。
与存量 a2a_service 并存，按 FEAT/用例绞杀者式逐个迁移（L2-overview §7）。
"""

import logging as _logging

# 日志门面（overview §3.4，与 java 侧 slf4j-api 同构）：库只挂 NullHandler，
# **不配置 root、不加真实 handler、不设级别**——格式/级别/落地权全部归宿主。
# 挂它只为避免 "No handlers could be found" 警告；propagate 保持 True，日志照常
# 冒泡到宿主的 logger 链。各模块用 logging.getLogger(__name__) 取层级 logger，
# 宿主即可按 `agent_runtime.adapters.*` 这样的包粒度单独调级别。
_logging.getLogger(__name__).addHandler(_logging.NullHandler())
