# -*- coding: utf-8 -*-
"""
calc_service 日志工具（从 solve/logger.py 复制，独立运行）。

保持与原版相同的单例模式与领域日志函数签名，
确保 solver.py / data_loader.py 等搬迁后日志行为一致。
"""
import logging
import os
from logging.handlers import RotatingFileHandler
from typing import Optional

_LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "refinery.log")


class _SafeRotatingFileHandler(RotatingFileHandler):
    """RotatingFileHandler 的安全子类，捕获 Windows 文件锁冲突。

    在 uvicorn --reload 模式下，新旧进程交替时 refinery.log 可能被旧进程占用，
    导致轮转重命名失败（WinError 32）。此处静默跳过轮转错误，避免反复抛异常拖慢运行。

    死循环修复：rotate 失败后会陷入"文件超阈值→doRollover→失败→文件仍超阈值"死循环。
    通过在 rotate 失败时截断当前文件（清空到 0 字节），使 shouldRollover 下次返回 False，
    从而打破死循环；下次文件再次超阈值时仍会尝试滚动（若锁已释放则成功）。
    """

    def rotate(self, source, dest):
        try:
            super().rotate(source, dest)
        except (PermissionError, OSError):
            # 轮转失败：截断当前文件，使 shouldRollover 下次返回 False，打破死循环。
            # 不设永久标志：下次文件再次超阈值时仍尝试滚动（锁可能已释放）。
            try:
                with open(source, 'w', encoding=self.encoding or 'utf-8') as f:
                    pass
            except (PermissionError, OSError):
                pass

    def emit(self, record):
        try:
            super().emit(record)
        except PermissionError:
            pass
        except OSError:
            pass


class RefineryLogger:
    _instance: Optional['RefineryLogger'] = None
    _initialized: bool = False

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self, log_file: str = _LOG_FILE, verbose: bool = True):
        if RefineryLogger._initialized:
            return

        self.log_file = log_file
        self.verbose = verbose

        self.logger = logging.getLogger('RefineryLoggerV1')
        self.logger.setLevel(logging.DEBUG)

        self.logger.handlers.clear()

        file_formatter = logging.Formatter(
            '%(asctime)s - %(name)s - %(levelname)s - %(message)s',
            datefmt='%Y-%m-%d %H:%M:%S'
        )

        # 轮转文件 handler：单文件 10MB、保留 5 份，封顶 ~60MB。
        # 曾因普通 FileHandler 无上限 + DEBUG 级全量写入，refinery.log 涨到 148MB。
        file_handler = _SafeRotatingFileHandler(
            self.log_file, maxBytes=10 * 1024 * 1024, backupCount=5, encoding='utf-8')
        file_handler.setLevel(logging.INFO)
        file_handler.setFormatter(file_formatter)
        self.logger.addHandler(file_handler)

        console_formatter = logging.Formatter(
            '%(asctime)s - %(levelname)s - %(message)s',
            datefmt='%H:%M:%S'
        )

        console_handler = logging.StreamHandler()
        console_handler.setLevel(logging.INFO if not self.verbose else logging.DEBUG)
        console_handler.setFormatter(console_formatter)
        self.logger.addHandler(console_handler)

        RefineryLogger._initialized = True

    def set_verbose(self, verbose: bool):
        self.verbose = verbose
        for handler in self.logger.handlers:
            if isinstance(handler, logging.StreamHandler):
                handler.setLevel(logging.INFO if not self.verbose else logging.DEBUG)

    def debug(self, msg: str, *args, **kwargs):
        if self.verbose:
            self.logger.debug(msg, *args, **kwargs)

    def info(self, msg: str, *args, **kwargs):
        self.logger.info(msg, *args, **kwargs)

    def warning(self, msg: str, *args, **kwargs):
        self.logger.warning(msg, *args, **kwargs)

    # 兼容原代码中 logger.warn(...) 的调用（logging 已弃用 warn，统一映射到 warning）
    def warn(self, msg: str, *args, **kwargs):
        self.logger.warning(msg, *args, **kwargs)

    def error(self, msg: str, *args, **kwargs):
        self.logger.error(msg, *args, **kwargs)



def get_logger() -> RefineryLogger:
    return RefineryLogger()


# ── 领域日志函数（与原 solve/logger.py 保持一致）──

def log_data_load_start(excel_path: str):
    logger = get_logger()
    logger.info("=" * 80)
    logger.info("数据加载开始")
    logger.info(f"Excel文件路径: {excel_path}")


def log_devices_loaded(devices: dict):
    logger = get_logger()
    logger.debug(f"已加载 {len(devices)} 个装置:")
    for device_id, device in devices.items():
        logger.debug(f"  - {device_id}: {device.name} (安全库存阈值: {device.safety_stock_thrd:.3f}, 类型: {'tank' if device.is_tank else getattr(device, 'type', 'normal')})")


def log_products_loaded(products: dict):
    logger = get_logger()
    logger.debug(f"已加载 {len(products)} 个产品:")
    for product_id, product in products.items():
        logger.debug(f"  - {product_id}: 名称={product.name}, 来源={product.source_device_id}, "
                     f"收率={product.yield_rate*100:.3f}%, 最终产品={product.is_final}")


def log_connections_loaded(material_flows: dict):
    logger = get_logger()
    logger.debug(f"已加载 {len(material_flows)} 个物流边:")
    for fid, flow in material_flows.items():
        special = f", special_var={flow.special_var}" if flow.special_var else ""
        logger.debug(f"  - {fid}: {flow.from_device_id}.{flow.from_product_id} -> {flow.to_device_id} "
                     f"(priority={flow.priority}, unique={flow.is_unique_target}{special})")


def log_scenario_loaded(scenario):
    logger = get_logger()
    logger.info("数据场景加载完成")
    logger.info(f"总装置数: {len(scenario.devices)}, 总产品数: {len(scenario.products)}, "
                f"总物流边数: {len(scenario.material_flows)}")
    logger.info(f"起始装置: {scenario.start_device_id}")
