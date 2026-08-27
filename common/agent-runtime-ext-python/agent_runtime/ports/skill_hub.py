# coding: utf-8

"""智能体中间件请求代理的端口与契约类型（Feat-Func-005b §2.3.1，洋葱 ports，零框架依赖）。

## 两个替换边界，分开声明

**Provider 管「去哪儿取材」，Installer 管「材料交给谁」**。二者分开的理由是换 Skill Hub
不该动框架适配、换框架不该动 Hub 客户端（详设 §2.3.1）。合成一个接口时，部署方为了换
掉 Hub 客户端必须连带重写移交逻辑，而移交逻辑与 Hub 毫无关系。

第三个协议 `SkillTargetResolver` 是**可选能力协议**：不是每种框架适配件都拿得到可接收
skill 的实例。用独立协议表达「这个适配件能给出目标」，而不是往 `AgentHandler` 端口上加
方法——后者会让所有实现被迫应付一个多数用不上的方法（详设 §3.2）。

## 与上游的对应关系

四个方法名、`LocalSkillEntry` 的两个字段、失败分类的九个取值、连接配置的字段集，全部与
上游扩展实现同名同义（上游 SPI 见 `agent-service-spec-ext` 的 `skillhub` 包）。三处本地化：

| 我方 | 上游 | 等价理由 |
|---|---|---|
| `SkillHubError` | `SkillHubException` | Python 惯例异常以 Error 结尾；Exception 是内置基类名 |
| `download` 返回材料条目 | 返回布尔值 | 上位把 SPI 方法面交给 L2 定义；布尔值会逼出「扫本地目录反推材料集」这个更弱的第二事实源 |
| `token` 为掩码类型 | `String decryptedToken` | 语义相同（都是已解密明文），差别是本实现用类型强制脱敏而非注释约束 |

## 为什么配置类落在端口层

它是 SPI 方法的入参类型。放在适配层则端口层要反向依赖适配层——那是依赖方向的硬违反
（总体设计 §3.3）。上游用两个类解同一道题（纯契约类 + 带框架注解的子类），Python 侧
配置加载器按类型注解绑定普通数据类，不需要注解层，一个类即可（详设 §3.1）。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Optional, Protocol, Sequence, runtime_checkable

from agent_runtime.ports.secret import SecretValue


class SkillHubErrorCategory(Enum):
    """Skill Hub 访问失败的分类。**九个取值与上游枚举逐字一致，一个不裁**。

    取值集合是跨语言互通的词汇表（附录 A.3）：按「我方暂时用不上」裁剪，会让两侧在
    同一件事上说不到一起去——上游报 `UNSUPPORTED`，我方这边没有这个词。

    调用方**读枚举分支，不解析文本**：文本会因语言、版本与脱敏策略而变，枚举不会。
    上游早期只有消息前缀、后来才补了类型化异常与分类字段；本实现从一开始就只提供
    枚举一条判定路径，不保留字符串前缀解析的回退。
    """

    #: endpoint 不可达、网络瞬断、响应体不可解析
    CONNECT_FAILED = "CONNECT_FAILED"
    #: 凭据缺失、无效、过期，或服务端以 401／403 拒绝
    AUTH_FAILED = "AUTH_FAILED"
    #: Skill Hub 拒绝访问该资源
    ACCESS_DENIED = "ACCESS_DENIED"
    #: skill 不存在或不可见
    NOT_FOUND = "NOT_FOUND"
    #: 下载中断、响应体为空、落盘失败
    DOWNLOAD_FAILED = "DOWNLOAD_FAILED"
    #: 摘要不符，或解压后结构校验不通过
    CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH"
    #: 移交未生效（只可能在请求期出现）
    INSTALL_FAILED = "INSTALL_FAILED"
    #: 服务端不提供下载能力，或请求参数被判为非法
    UNSUPPORTED = "UNSUPPORTED"
    #: 未分类异常兜底
    UNKNOWN = "UNKNOWN"


#: 阻断就绪的三个分类（详设 §4.3.1 的分类表）。配置、认证、授权、查找类失败使
#: required skill 不可得，上位要求此时 Agent 不得 ready；取材类失败则相反，
#: 不得阻塞 ready。两条是一对相反的 MUST，必须落成两条不同的代码路径。
FATAL_CATEGORIES = frozenset({
    SkillHubErrorCategory.AUTH_FAILED,
    SkillHubErrorCategory.ACCESS_DENIED,
    SkillHubErrorCategory.NOT_FOUND,
})

#: 可降级但**永不自愈**、故不进重试循环的分类（详设 §4.3.1）。
#:
#: 这是本实现相对上游补的一维——上游把可降级的分类一律纳入重试。摘出它的两处收益：
#: 参数类失败重发多少次结果相同，无效重试会长期占用连接；且默认 Hub 每次成功取到
#: 制品元信息都会累加资产的安装计数，无效重试会持续污染兄弟模块的运营统计。
#:
#: `INSTALL_FAILED` 在此是因为它根本不在启动取材路径上（只可能在请求期移交时出现），
#: 不是因为「重试无用」——两种理由不同，合并写会让读者以为移交失败可以靠重试修好。
NON_RETRYABLE_CATEGORIES = frozenset({
    SkillHubErrorCategory.UNSUPPORTED,
    SkillHubErrorCategory.INSTALL_FAILED,
})


class SkillHubError(RuntimeError):
    """带分类的 Skill Hub 失败。

    `reason` **须已脱敏**：它会进日志与异常消息，而上位明写解密后的凭据、认证头、
    预签名下载地址都不得出现在那里。构造方在传入前完成脱敏，本类不代做——代做会
    让人以为「随便传，这里会洗干净」，而本类无从知道哪一段是敏感的。
    """

    def __init__(self, category: SkillHubErrorCategory, reason: str = "") -> None:
        super().__init__(f"[{category.value}] {reason}" if reason else f"[{category.value}]")
        self.category = category
        self.reason = reason


class SkillHubConfigError(ValueError):
    """装配期的配置失败：配置项缺失、取值非法、扩展点未发现、凭据解不开。

    **与运行期的 `SkillHubError` 分开**，因为处置完全不同：这一类**恒定阻断启动**，
    没有分类、没有降级、没有重试；运行期那一类要按分类分层处置。合成一个类型时，
    调用方就得靠分类字段去区分「这是配错了」还是「这次没取到」。

    **消息中必含配置项的属性路径**（与本仓基座的配置错误同一条纪律）：配置错误发生
    在启动期，而启动期的错误信息是运维唯一的线索。一条「值不合法」与一条
    「`skill_hub.auth_type` 的值 'basic' 不合法，支持 bearer 与 system-token」
    之间的差距，是十分钟与一整天。

    **落在端口层而不是基座的错误模块**：装配工厂按设计落在适配层，而适配层不得反向
    依赖基座（依赖方向门禁按这条判）。它与 `SkillHubConfig` 同处一层是恰当的——
    这个异常说的正是那个配置对象不可用。它继承 `ValueError`，与基座的配置错误同源，
    宿主用一个 `except ValueError` 能同时兜住两者。
    """


@dataclass(frozen=True)
class LocalSkillEntry:
    """一条已下载的本地材料：资产标识 + 本地路径。

    **字段面与上游同名记录逐字一致，不增字段**。上游把 required/optional 标记列为
    「后续版本可能折进本记录」的待定项，本版随上游不做（详设 §13）。

    `local_path` 必须位于配置的落盘根目录之下——越出根目录的条目由协调器拒收
    （详设 §2.3.2 字段级约束表）。该校验落在协调器而非本类：本类是纯数据，
    拿不到根目录；而在构造处校验又会让 Provider 实现各写一遍。
    """

    skill_id: str
    local_path: Path


@dataclass(frozen=True)
class SkillDownloadReport:
    """一次下载的产出与完整性标记。

    **部分成功是合法结果**：`entries` 非空而 `complete` 为假时，调用方既移交已得材料、
    又启动后台重试（详设 §2.3.1）。这正是返回条目序列而非布尔值的价值——布尔值下
    「取到三个、失败一个」与「一个都没取到」是同一个读数。

    `complete` **不给默认值**：忘了赋值的实现会以「默认成功」的形态混过去，
    而调用方据它决定要不要启动后台重试。
    """

    entries: Sequence[LocalSkillEntry]
    complete: bool


@dataclass(frozen=True)
class SkillHubFetchConfig:
    """默认实现的取材参数。**自定义 Provider 不读这一节**。

    上位明写不在特性范围内固定缓存、重试、分页与落盘策略，故本节属实现细节而非 SPI 契约
    （详设 §6.3）。各默认值的出处见详设 §6.2 的属性表。
    """

    #: 列举接口的每页条数。默认 200——兄弟模块对该参数的服务端上界就是 200，
    #: 取满上限使列举的往返次数最少。
    page_size: int = 200
    #: 并行下载路数。与上游同值。
    concurrency: int = 4
    #: 建连超时（秒）。与上游同值。
    connect_timeout_s: float = 10.0
    #: 列举与元信息请求的超时（秒）。与上游同值。
    request_timeout_s: float = 30.0
    #: 单个压缩包的下载超时（秒）。与上游同值——包体大小不受 runtime 控制，
    #: 超时须按最大包体而非平均值取。
    download_timeout_s: float = 600.0
    #: 解压后的累计体积上限（字节）。**这一项上游没有**：高压缩比的构造包可撑满磁盘，
    #: 而磁盘写满会波及同机的其他组件，不只是本特性。默认 512 MiB。
    #: 取值未经压测（详设 §13 已登记）——过小会误伤大体量 skill，故做成可配。
    max_extracted_bytes: int = 512 * 1024 * 1024


@dataclass(frozen=True)
class SkillHubRetryConfig:
    """后台重试参数。取材类失败后在**请求链路外**按此重试。"""

    #: 首次重试前的等待（秒）。与上游同值——首次失败多为服务端尚未就绪。
    initial_delay_s: float = 5.0
    #: 后续重试的固定间隔（秒）。与上游同值。
    #:
    #: **不做指数退避**：取材失败的主因是 Hub 侧不可达，指数退避会把恢复后的可用时刻
    #: 推得很远，而固定间隔的代价只是每 30 秒一次列举请求。
    period_s: float = 30.0
    #: 重试上限。**本实现相对上游多的一项**（上游无上限、永久重试）。
    #: 120 次乘 30 秒约合一小时，足以覆盖 Hub 侧的常规重启与发布窗口；
    #: 超过一小时仍不可达属部署事件，应由运维介入。**配 0 表示不设上限**，即回到上游行为。
    max_attempts: int = 120


@dataclass(frozen=True)
class SkillHubConfig:
    """部署态稳定的连接配置。**同时是配置绑定目标与 SPI 入参类型**（详设 §3.1、§6.3）。

    **不含任何请求级上下文**——上位不承诺按请求维度变更 skill 集合，入参里放用户、
    会话或任务标识会让实现者以为可以那么用（详设 §2.3.1）。这也是本特性不引入
    租户维度的原因：没有租户判定，就没有判错的路径。
    """

    #: 总开关。**默认假**——未配置即整条链路不装配，行为与没有本特性时逐字相同。
    enabled: bool = False
    #: Skill Hub 服务地址。`enabled` 为真时必填，空值在装配期即失败。
    endpoint: str = ""
    #: 认证方式：`bearer` 或 `system-token`。两者互斥，不得同时送出。
    auth_type: str = "bearer"
    #: 加密凭据。**类型是掩码类型而非字符串**：字符串化恒为掩码，取原值须显式 `reveal()`。
    #: 空值表示匿名访问；非空但解密失败时装配期失败，不降级为匿名。
    encrypted_token: SecretValue = field(default_factory=SecretValue)
    #: 材料落盘目录。`enabled` 为真时必填；**不得跨副本共享**（宿主义务）。
    local_dir: str = ""
    #: Skill Hub 访问实现的扩展点名。留空用内建实现；
    #: **指定但未发现时装配期失败**，不静默回落到内建实现。
    provider: str = ""
    fetch: SkillHubFetchConfig = field(default_factory=SkillHubFetchConfig)
    retry: SkillHubRetryConfig = field(default_factory=SkillHubRetryConfig)

    def __post_init__(self) -> None:
        # 类型标注拦不住运行期传进来的裸字符串（配置加载器最可能这么干），在此归一。
        # 少了这一步，掩码类型只是「调用方配合才生效」的约定，不是机制。
        # 与缓存配置的口令字段同法。
        object.__setattr__(self, "encrypted_token", SecretValue.of(self.encrypted_token))


@runtime_checkable
class SkillHubProvider(Protocol):
    """Skill Hub 访问边界。四个方法与上游 SPI 一一对应。

    实现者可以是内建的默认实现，也可以是部署方按扩展点注入的实现。无论哪一种，
    都须遵守同一套凭据保护、错误分类与「校验失败不返回成功」的语义。
    """

    async def start(self, config: SkillHubConfig, token: SecretValue) -> None:
        """建立连接资源（连接池、并发闸门），并记录一条不含凭据的启动诊断。

        参数 config：部署态稳定的连接配置。
        参数 token：**已解密的明文凭据**；空值表示匿名访问。
            实现者**必须**只在构造认证请求头的那一处调用 `reveal()`，
            不得写入日志、异常消息、遥测标签或任何持久化位置。

        实现者必须保证：本方法失败时抛 `SkillHubError` 并带分类；连接资源未建立成功时，
        不得让后续 `download` 以「没启动」的形态静默返回空——那会被调用方读成
        「Hub 上没有可见 skill」，与真实原因南辕北辙。
        """
        ...

    async def download(
        self, config: SkillHubConfig, token: SecretValue
    ) -> SkillDownloadReport:
        """下载本次部署应取的全部 skill 到 `config.local_dir`。

        **取哪些由实现决定，不由调用方传入 skill 标识**——调用方在下载前拿不到标识
        （上游 SPI 注释逐字记载了同一条理由）。

        返回 `SkillDownloadReport`：`entries` 是本次实际产出的材料条目，
        `complete` 表示是否全部成功。

        实现者必须保证：
            一、`entries` 里的每个 `local_path` 都真实存在且可读；
            二、下载失败的条目不出现在 `entries` 里；
            三、致命类失败（认证、拒绝访问、不存在）抛 `SkillHubError` 而非悄悄返回
                `complete=False`——两者在调用方走的是相反的路径。
        """
        ...

    async def verify(self, skill_path: Path) -> bool:
        """校验单个本地材料的完整性。

        参数 skill_path：待校验的本地路径，取自 `download` 返回的条目。
        返回：通过为真，失败为假。**失败也可以抛 `SkillHubError` 带摘要不符分类**——
            两种表达调用方都当作「不通过」处理，差别只在诊断信息的丰富程度。

        实现者必须保证：校验算法可自选（上位明许），但**不得对未校验的路径返回真**。
        """
        ...

    async def stop(self) -> None:
        """释放连接资源。实现者必须保证本方法**可重复调用而不抛出**。"""
        ...


@runtime_checkable
class SkillMaterialInstaller(Protocol):
    """注册材料移交边界：把本地材料交给框架实例。"""

    async def install(self, agent: object, entries: Sequence[LocalSkillEntry]) -> None:
        """把材料条目移交给目标实例。

        参数 agent：框架实例。**类型是 `object` 而非具体框架类**——端口层不得依赖
            任何框架类型（总体设计 §3.3）。
        参数 entries：待移交的材料条目，由协调器保证均已通过校验。

        实现者必须保证：
            一、目标实例不支持接收 skill 时**记录并返回，不抛出**——那不是移交失败，
                是这类实例本就不承载 skill；
            二、移交确实未生效时抛 `SkillHubError` 带移交失败分类，使调用方能在请求线上感知；
            三、本方法**不解析材料内容**——runtime 不解释 skill 包内容。
        """
        ...


@runtime_checkable
class SkillTargetResolver(Protocol):
    """可选能力协议：这个框架适配件能不能给出一个可接收 skill 的实例。

    **独立于 `AgentHandler` 声明**：端口是稳态面，为一个多数实现用不上的能力扩张它，
    等于让全部实现为一个特性买单（详设 §3.2）。适配件按需实现本协议即可，
    不实现的适配件在移交时被判为「不承载 skill」，记诊断并放行。
    """

    async def resolve_skill_target(self) -> Optional[object]:
        """解析出本适配件当前的可接收实例；拿不到时返回空。

        **是异步的**：框架侧的实例解析入口是异步的（按标识取实例要走资源管理器），
        声明成同步会逼实现方在事件循环里阻塞等待。

        实现者必须保证：解析失败**返回空而不抛出**——拿不到实例不是错误，
        是这个部署形态下没有移交对象，请求应照常处理。
        """
        ...


__all__ = [
    "FATAL_CATEGORIES",
    "NON_RETRYABLE_CATEGORIES",
    "LocalSkillEntry",
    "SkillDownloadReport",
    "SkillHubConfig",
    "SkillHubConfigError",
    "SkillHubError",
    "SkillHubErrorCategory",
    "SkillHubFetchConfig",
    "SkillHubProvider",
    "SkillHubRetryConfig",
    "SkillMaterialInstaller",
    "SkillTargetResolver",
]
