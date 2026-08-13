# -*- coding: utf-8 -*-
"""化工炼化物料平衡数据分析系统 - 启动脚本

使用方法:
    python run.py

首次运行时自动检测并安装缺失依赖，启动后自动打开浏览器。
端口被占用时自动切换到下一个可用端口。
"""

import sys
import os
import subprocess
import webbrowser
import threading
import time
import socket

REQUIRED = [
    "fastapi==0.115.0",
    "uvicorn[standard]==0.30.6",
    "openpyxl==3.1.5",
    "pandas==2.2.3",
    "python-multipart==0.0.9",
]

def check_and_install():
    """检查依赖是否齐全，缺失则自动 pip install"""
    missing = []
    for item in REQUIRED:
        pkg = item.split("==")[0].split("[")[0]
        try:
            __import__(pkg.replace("-", "_"))
        except ImportError:
            missing.append(item)

    if not missing:
        return True

    print("检测到缺失依赖，正在自动安装...")
    print("  " + ", ".join(missing))
    print()
    try:
        subprocess.check_call(
            [sys.executable, "-m", "pip", "install"] + missing,
            stdout=sys.stdout,
            stderr=sys.stderr,
        )
        print("\n依赖安装完成。\n")
        return True
    except subprocess.CalledProcessError:
        print("\n依赖自动安装失败，请手动执行：")
        print("  pip install -r requirements.txt")
        return False

def find_available_port(start=8765, count=20):
    """从 start 开始找可用端口"""
    for port in range(start, start + count):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                s.bind(("127.0.0.1", port))
                return port
        except OSError:
            continue
    return None

def main():
    os.chdir(os.path.dirname(os.path.abspath(__file__)))

    if not check_and_install():
        input("按回车键退出...")
        sys.exit(1)

    host = "127.0.0.1"
    port = find_available_port(8765)
    if port is None:
        print("未找到可用端口（8765-8784 均被占用），请释放端口后重试。")
        input("按回车键退出...")
        sys.exit(1)

    url = f"http://{host}:{port}/"

    def open_browser():
        time.sleep(2)
        webbrowser.open(url)

    threading.Thread(target=open_browser, daemon=True).start()

    print("=" * 60)
    print("  化工炼化物料平衡数据分析系统")
    print("=" * 60)
    print()
    print(f"  服务地址: {url}")
    if port != 8765:
        print(f"  （端口 8765 被占用，已切换到 {port}）")
    print(f"  按 Ctrl+C 停止服务")
    print()

    import uvicorn
    uvicorn.run("app.main:app", host=host, port=port, reload=False)

if __name__ == "__main__":
    main()
