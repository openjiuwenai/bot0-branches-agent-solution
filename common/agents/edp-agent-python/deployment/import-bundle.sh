#!/usr/bin/env bash
#
# import-bundle.sh — 在客户侧离线环境中导入镜像
#
# 前置：
#   - 已安装 Docker（≥ 20.10）
#   - 已解压 bundle，并 cd 到 bundle 目录
#
# 执行：./import-bundle.sh 镜像包名
#
set -euo pipefail

# 1. 获取第一个入参作为镜像包名
# 如果用户没传参数，打印错误提示并退出
if [ $# -eq 0 ]; then
    echo "❌  错误：缺少镜像包名参数。"
    echo "用法: $0 <镜像包名>"
    echo "示例: $0 my_image.tar"
    exit 1
fi

IMAGE_TAR="$1"

# 2. 校验文件是否存在
if [ ! -f "$IMAGE_TAR" ]; then
    echo "❌  未找到文件：$IMAGE_TAR"
    exit 1
fi

echo "[1/2] docker load < $IMAGE_TAR"
docker load -i "$IMAGE_TAR"


echo ""
echo "[2/2] 校验镜像"
docker images edpagent --format "   {{.Repository}}:{{.Tag}}   {{.Size}}"

echo ""
echo "✅ 镜像已导入。"
echo ""
echo "下一步："
echo "  1. 按企业环境检查并编辑 config/a2a_service.env"
echo "  2. 按企业环境检查并编辑 config/versatile_adapter.env"
echo "     注意：Docker 单容器模式下 REDIS_HOST 不能填 localhost/127.0.0.1"
echo "  3. ./run.sh                   # 启动单容器"
echo "  4. curl http://localhost:8090/health   # 业务端到端测试请咨询现场同事"
