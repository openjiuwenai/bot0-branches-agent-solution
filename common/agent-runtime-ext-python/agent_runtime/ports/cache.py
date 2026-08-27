# coding: utf-8

"""统一 Redis 端口 RuntimeRedisClient（洋葱内圈，零框架依赖，FEAT-003）。

**Redis client 抽象（Redis-only）**，命令面对齐 java `spec/spi/RuntimeRedisClient`
（get/set/setex/setnx/del/exists/expire/mget/scanIter）——runtime 必需的最小集，不承诺
Redis 全命令。序列化、key 选取与 **TTL 取值**由**各消费方**决定，本端口只做 **bytes I/O + TTL
施加**（数据视图 §1.2 物理模型）。

**此处原写「TTL 策略在 application」，与实际落位不符**：TTL 是各存储用途各自的策略，
它们本就长在各自的消费方上，application 一处都没有——
`adapters/inbound/a2a/task_store.py` 的任务快照存活期、
`adapters/inbound/a2a/push_receiver.py` 的回调判重窗口、
`adapters/inbound/a2a/webhook.py` 的回调配置存活期、
`adapters/inbound/session_context.py` 的会话上下文存活期，四处四个值、
各自对应一件事该活多久。把它们上收到 application 不会让任何一处更正确，
只会让「这个值为什么是这个数」离它的理由更远。改的是这句话，不是落位。默认 in-memory 路径**不经**本端口（走 a2a-sdk
InMemoryTaskStore，Feat-Func-003b §4.1）。

**无 `cas`**（总体设计 §8.2「Task 推进的并发正确性」：不做 CAS，对齐上游 Java）：Task 推进的并发正确性由 **a2a-sdk 单写者 per Task + 部署实例
亲和**保证（overview §9.2），不做 CAS——原「必须 CAS」经核实：① 偏离 java（其端口无
cas，只有 setnx/expire）；② 在 protobuf 快照上做整值字节比较不成立（序列化非规范化 →
假失败/活锁）；③ 只盖 runtime 一层（agent-core 自有 Redis checkpointer 盖不住）。
亲和破坏窗口的残余风险明写接受（同 resilience R1）；版本化乐观并发属 draft 未来、归平台。
"""
from __future__ import annotations

from typing import AsyncIterator, Optional, Protocol, runtime_checkable


@runtime_checkable
class RuntimeRedisClient(Protocol):
    """Redis 操作面（bytes 端口）。切换单机/集群/集成方封装只换实现，业务代码不变。"""

    async def get(self, key: str) -> Optional[bytes]:
        ...

    async def set(self, key: str, value: bytes, *, ttl_s: int) -> None:
        """带过期写入，等价 `SETEX`。过期值由消费方给出（数据架构视图 §2.1「统一过期时间」）。

        **`ttl_s` 必填，不是可选参数**——这是「所有写入一律带过期、无例外键」这条保证
        在端口层面成立的方式。可选参数拦不住任何人：省略一个默认参数不会报错、不会被
        类型检查拦下、评审时也看不出来，而它写出的键永不过期。本仓曾有一处无过期写入
        正是这样混进去的（中断续接的等待点键，已随该模块一并删除）。

        收窄相对上游 Java 的理由见总体设计 §4 该端口的注：权威把方法面交给 L2 设计
        （`Technical-AF/docs/develop/02-features/FEAT-003-agent-task-state-cache.md` §3），
        本设计据此不保留无过期的通用写入形态。
        """
        ...

    async def setex(self, key: str, ttl_s: int, value: bytes) -> None:
        """带过期写入（对齐 java `setex`）。"""
        ...

    async def setnx(self, key: str, value: bytes, *, ttl_s: int) -> bool:
        """键不存在才写入（对齐 java `setnx`）；返回是否写入成功。

        用于「一次性领取」类语义（如等待点领取、到期转终态领取）——单写者+亲和下已足够，
        无需 CAS —— 总体设计 §8.2 明写不做 CAS（对齐上游 Java），并发正确性靠单写者与实例亲和。

        `ttl_s` 同样必填，理由见 `set`。实现必须以**单命令原子**完成（`SET key value NX EX ttl`）。
        分成 `setnx` + `expire` 两步是缺陷：崩在两步之间会留下**永不过期的领取键**，
        使该资源的领取永久失败（独立验收实测发现）。
        """
        ...

    async def delete(self, key: str) -> None:
        ...

    async def exists(self, key: str) -> bool:
        ...

    async def write_externally_governed(self, key: str, value: bytes) -> None:
        """**具名的外部治理写入**：不带过期，键的回收由外部统一管理。

        **仅供注入给 agent-core 的存储对象使用**（DeepAgent Todolist）。除该注入路径外
        任何调用都是违规——本方法是端口上唯一能写出永不过期键的入口。

        依据：权威 `Technical-AF/docs/develop/02-features/FEAT-003-agent-task-state-cache.md`
        §5.1.4 明写 Todolist 用 `BaseKVStore.set()` 写入、**不调用** `setex` 或 `expire`，
        过期时间由外部 Redis 连接池配置或运维侧统一管理；同文 §2 与 §3 把 Todolist 的
        过期时间排除在本特性的配置项之外。故 §6「默认配置下避免无 TTL 写入」那条强制项的
        对象（A2A Task 快照与 Agent checkpoints）**不含 Todolist**，本方法不违反它。

        **为什么是具名而非给通用写入加可选过期参数**：可选参数省一个就无声产出永不过期的
        键，且省略不会报错、类型检查不拦、评审看不出。具名形态把这条路径收敛到一个可被
        静态审计的入口——越出注入路径即为违规，扫一次调用点就能查。

        **方法名刻意不以 `set` 开头**：守门判据按前缀扫描通用写入面，具名方法若混在其中，
        判据要么误报、要么被迫加白名单，而白名单会被下一个人继续加长。
        """
        ...

    async def mget(self, keys: list[str]) -> list[Optional[bytes]]:
        ...

    def scan(self, match: str, *, count: int = 100) -> AsyncIterator[bytes]:
        """只读遍历既有键（对齐 java `scanIter`，返回异步迭代器）。"""
        ...

    async def aclose(self) -> None:
        """释放底层连接资源。

        **对齐上游**：其同名端口继承自「可自动关闭」——释放是契约的一部分，
        不是实现方各自加的额外能力（`openJiuwen/agent-runtime-java` 的
        `spec/spi/RuntimeRedisClient extends AutoCloseable`）。

        此前端口没有这一条，包装件靠属性探测决定要不要穿透释放。后果是：
        按端口写出来的实现没有该方法，探测落空、底层连接**一次也不会被关**，
        而这件事零日志零信号。

        **无资源可释放时空实现即可**——空实现与「探测不到」是两回事：前者是
        「确实没事可做」，后者是「有没有能力不知道」。
        """
        ...
