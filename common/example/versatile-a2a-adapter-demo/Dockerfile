# Versatile A2A Adapter Demo 镜像
#
# 基础镜像: eclipse-temurin:17-jdk-alpine
#
# 一个容器同时可调试两个 demo:
#   1) versatile-a2a-adapter-demo（对接远端 Versatile, :18080）
#      /app/target/versatile-a2a-adapter-demo-0.1.0.jar   Spring Boot 可执行 jar
#      /app/script/start.sh                               启动本 demo 服务
#      /app/script/send-requests.sh                       用 curl 发送三轮 A2A 请求
#      /app/src/main/resources/a2a-requests/*.json        三轮请求体 JSON
#   2) versatile-controller-handoff-demo（FEAT-002 控制器意图转调, 自包含 mock, 无外部依赖）
#      /app/controller-handoff-demo/target/versatile-controller-handoff-demo-0.1.0.jar
#      /app/script/start-handoff.sh                       启动 L1(:18091)/L2(:18092) 双 runtime
#      /app/controller-handoff-demo/scripts/local-e2e.sh  十场景旅程验收（SKIP_BUILD=1 运行）
#
# 构建:  ./script/build-image.sh（handoff demo jar 缺失时自动在宿主机构建并 stage）
# 运行（容器只用于调测，不自动启动服务，进入后自行操作）:
#   # 方式一 (Linux 推荐): 容器用宿主机网络, 容器内 127.0.0.1 即宿主机的 127.0.0.1
#   docker run -d --name versatile-demo --network host versatile-a2a-adapter-demo:latest
#   docker exec -it versatile-demo /bin/sh                 # 进入容器
#     进入后: /app/script/start.sh                         # demo1: 对接远端 Versatile (:18080)
#             /app/script/send-requests.sh                 #   调测发请求
#             /app/script/start-handoff.sh                 # demo2: controller-handoff L1/L2
#             SKIP_BUILD=1 /app/controller-handoff-demo/scripts/local-e2e.sh   # 十场景验收
#             /app/script/start.sh --stop                  # 停止服务
#             /app/script/start-handoff.sh --stop
#
#   # 方式二 (Docker Desktop / 端口映射): 需要把远端 Versatile 地址指向宿主机
#   docker run -d --name versatile-demo -p 18080:18080 -p 18091:18091 -p 18092:18092 \
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
# curl: send-requests.sh 发请求、start.sh/start-handoff.sh 做就绪探测
RUN apk add --no-cache bash curl

WORKDIR /app

# 保持仓库相对路径, 脚本里的 DEMO_DIR/SCRIPT_DIR/REQ_DIR 计算原样可用
COPY target/versatile-a2a-adapter-demo-*.jar /app/target/
COPY script/start.sh script/send-requests.sh script/start-handoff.sh /app/script/
COPY src/main/resources/a2a-requests/ /app/src/main/resources/a2a-requests/

# versatile-controller-handoff-demo（build-image.sh 先 stage 到 target/image-extra/，
# 保持 local-e2e.sh 期望的 <模块根>/{target,scripts} 布局）
COPY target/image-extra/controller-handoff-demo/ /app/controller-handoff-demo/

RUN chmod +x /app/script/*.sh /app/controller-handoff-demo/scripts/*.sh

# 18080: a2a-adapter-demo A2A 入口 (http://<host>:18080/a2a/)
# 18091: controller-handoff-demo 一级 runtime (/v1/query 调试入口)
# 18092: controller-handoff-demo 二级 runtime
EXPOSE 18080 18091 18092

# 容器只用于调测：不自动启动服务，仅保持存活，进入后自行 start.sh / start-handoff.sh
CMD ["tail", "-f", "/dev/null"]
