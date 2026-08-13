# Versatile A2A Adapter Demo 镜像
#
# 基础镜像: eclipse-temurin:17-jdk-alpine
#
# 镜像内目录结构与仓库保持一致, 使 script/ 下的脚本在容器内无需改动即可运行:
#   /app/target/versatile-a2a-adapter-demo-0.1.0.jar   Spring Boot 可执行 jar
#   /app/script/start.sh                               启动 demo 服务
#   /app/script/send-requests.sh                       用 curl 发送三轮 A2A 请求
#   /app/src/main/resources/a2a-requests/*.json        三轮请求体 JSON
#
# 构建:  ./script/build-image.sh
# 运行（容器只用于调测，不自动启动服务，进入后自行操作）:
#   # 方式一 (Linux 推荐): 容器用宿主机网络, 容器内 127.0.0.1 即宿主机的 127.0.0.1
#   docker run -d --name versatile-demo --network host versatile-a2a-adapter-demo:latest
#   docker exec -it versatile-demo /bin/sh                 # 进入容器
#     进入后: /app/script/start.sh                        # 手动启动服务
#             /app/script/send-requests.sh                # 调测发请求
#             /app/script/start.sh --stop                 # 停止服务
#
#   # 方式二 (Docker Desktop / 端口映射): 需要把远端 Versatile 地址指向宿主机
#   docker run -d --name versatile-demo -p 18080:18080 \
#     -e VERSATILE_URL=http://host.docker.internal:31113/v1/0/agents/main_planner/conversations/{conversation_id} \
#     versatile-a2a-adapter-demo:latest
#   docker exec -it versatile-demo /bin/sh && 同上进入后操作
#
#   # 或直接交互式: docker run -it --entrypoint /bin/sh versatile-a2a-adapter-demo:latest
#
# 【请求体独立于镜像】send-requests.sh 优先读 /app/a2a-requests/（镜像内置的兜底在
#   /app/src/main/resources/a2a-requests/）。生产环境把请求体放宿主机目录挂载覆盖，
#   改请求体不需要重建/重拷镜像:
#     mkdir -p ~/versatile-demo/a2a-requests
#     把你自己的 request-1.json 等放进去（或任意文件名用 --file 指定）
#     docker run -d --name versatile-demo -p 18080:18080 \
#       -v ~/versatile-demo/a2a-requests:/app/a2a-requests:ro \
#       -e VERSATILE_URL=http://host.docker.internal:31113/v1/0/agents/main_planner/conversations/{conversation_id} \
#       versatile-a2a-adapter-demo:latest
#     docker exec versatile-demo /app/script/send-requests.sh --file /app/a2a-requests/my-request.json
#   之后改宿主机 ~/versatile-demo/a2a-requests/ 下的 JSON 即可，镜像保持不变。
FROM eclipse-temurin:17-jdk-alpine

# bash: 脚本使用 bash 语法 (alpine 默认是 busybox sh)
# curl: send-requests.sh 发请求、start.sh 做就绪探测
RUN apk add --no-cache bash curl

WORKDIR /app

# 保持仓库相对路径, 脚本里的 DEMO_DIR/SCRIPT_DIR/REQ_DIR 计算原样可用
COPY target/versatile-a2a-adapter-demo-*.jar /app/target/
COPY script/start.sh /app/script/start.sh
COPY script/send-requests.sh /app/script/send-requests.sh
COPY src/main/resources/a2a-requests/ /app/src/main/resources/a2a-requests/

RUN chmod +x /app/script/*.sh

# A2A 入口端口: http://<host>:18080/a2a/
EXPOSE 18080

# 容器只用于调测：不自动启动服务，仅保持存活，进入后自行 start.sh / send-requests.sh
CMD ["tail", "-f", "/dev/null"]
