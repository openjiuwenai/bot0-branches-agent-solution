# DFW-RAG 容器化构建部署手册

> 服务端口：`4398`

本手册为通用部署指引。使用前请将文档中所有尖括号占位符替换为实际环境的对应值。

---

## 1. 前置条件

- 已持有服务器 SSH 私钥文件：`<SSH_KEY_PATH>`
- 本地可访问目标服务器 `<SERVER_IP>` 的 22 端口
- 目标服务器已安装 Docker，且版本支持当前 Dockerfile 中使用的语法
- 已确认服务器架构（如 `x86_64`/`amd64` 或 `aarch64`/`arm64`），必要时调整基础镜像或构建方式

---

## 2. 登录服务器

### 2.1 Windows PowerShell / Git Bash

```bash
ssh -i "<SSH_KEY_PATH>" -o StrictHostKeyChecking=no <SSH_USER>@<SERVER_IP>
```

### 2.2 切换到 root 用户（推荐）

登录后执行：

```bash
sudo su -
```

提示输入密码时输入 root 密码 `<SUDO_PASSWORD>`。

> 注意：Docker 构建和运行建议使用 root 用户，避免权限问题。

---

## 3. 源码位置

将项目源码推送或上传至服务器的部署目录，例如：

```text
<DEPLOY_DIR>/
```

目录结构示例：

```text
<DEPLOY_DIR>/
├── Dockerfile
├── gunicorn.conf.py
├── requirements.txt
├── .dockerignore
├── rag_extract_split/
├── web/
├── data/
├── models/
└── logs/
```

---

## 4. 构建 Docker 镜像

### 4.1 进入项目目录

```bash
cd <DEPLOY_DIR>
```

### 4.2 构建镜像

如果服务器架构与本地开发机不一致（例如 ARM64 服务器与 x86 开发机），**建议在目标服务器上直接构建镜像**，避免架构不兼容问题。

```bash
docker build -t dfw-ragwheel:<TAG> .
```

其中 `<TAG>` 为镜像标签，例如 `latest`、`v1.0` 或日期版本 `0708`。

### 4.3 构建预期

- 基础镜像：`python:3.13-slim-bookworm`
- 因依赖 `numpy`、`pandas`、`scikit-learn`、`sentence-transformers`，使用 Debian bookworm 可避免 Alpine 上的源码编译问题
- 国内环境可在 Dockerfile 中配置清华 apt/pip 镜像加速
- 构建时间约 5~15 分钟，取决于网络速度

### 4.4 构建失败排查

| 现象 | 处理方案 |
|------|----------|
| `apt-get update` 失败 | 检查服务器能否访问配置的 apt 源，必要时替换为其他可用源 |
| pip 安装极慢/超时 | 检查 pip 镜像可用性，或改为阿里云镜像 `https://mirrors.aliyun.com/pypi/simple/` |
| `sentence-transformers` 编译失败 | 确认已安装 `gcc`、`cmake`、`libffi-dev`（Dockerfile 已包含） |
| 内存不足导致构建失败 | 减少 gunicorn worker 数或增大服务器 swap |

---

## 5. 运行容器

### 5.1 首次运行

```bash
cd <DEPLOY_DIR>

# 创建持久化目录
mkdir -p data logs uploads

# 启动容器
docker run -d \
  -p 4398:4398 \
  -v <DEPLOY_DIR>/data:/app/data \
  -v <DEPLOY_DIR>/logs:/app/logs \
  -v <DEPLOY_DIR>/uploads:/app/web/uploads \
  --name dfw-ragwheel \
  dfw-ragwheel:<TAG>
```

### 5.2 查看运行状态

```bash
docker ps -a | grep dfw-ragwheel
docker logs -f dfw-ragwheel
```

### 5.3 验证服务

```bash
curl http://127.0.0.1:4398/
```

预期返回首页 HTML。

---

## 6. 外网访问验证

如果服务器安全组/防火墙已放行 4398 端口，可在浏览器访问：

```text
http://<SERVER_IP>:4398/
```

或在其他机器执行：

```bash
curl http://<SERVER_IP>:4398/
```

---

## 7. 重新部署

### 7.1 停止并删除旧容器

```bash
docker stop dfw-ragwheel
docker rm dfw-ragwheel
```

### 7.2 删除旧镜像（可选）

```bash
docker rmi dfw-ragwheel:<TAG>
```

### 7.3 重新构建并启动

```bash
cd <DEPLOY_DIR>
docker build -t dfw-ragwheel:<TAG> .
docker run -d \
  -p 4398:4398 \
  -v <DEPLOY_DIR>/data:/app/data \
  -v <DEPLOY_DIR>/logs:/app/logs \
  -v <DEPLOY_DIR>/uploads:/app/web/uploads \
  --name dfw-ragwheel \
  dfw-ragwheel:<TAG>
```

---

## 8. 常见问题

### 8.1 容器启动后立即退出

查看日志：

```bash
docker logs dfw-ragwheel
```

常见原因：
- 端口 4398 被占用：检查 `netstat -tlnp | grep 4398` 或 `docker ps`
- 数据目录权限不足：确认 `<DEPLOY_DIR>/data` 对容器可写

### 8.2 数据丢失

生产环境务必使用 `-v` 挂载宿主机目录。未挂载时，容器删除后数据会丢失。

### 8.3 修改环境变量未生效

`docker restart` 不会重新加载环境变量。必须 `docker rm` 后重新 `docker run`。

### 8.4 Docker 版本兼容性

当前 Dockerfile 使用的基础镜像和指令均兼容常见 Docker 版本。若未来升级 Dockerfile 或使用 BuildKit 等新特性，请注意目标服务器 Docker API 版本兼容性。

---

## 9. 环境变量参考

| 环境变量 | 说明 | 默认值 |
|----------|------|--------|
| `DFW_RAG_PORT` | Flask 开发模式端口 | `4398` |
| `DFW_RAG_DEBUG` | 调试模式 | `false` |
| `DFW_RAG_HOME` | 项目根目录 | `/app` |
| `GUNICORN_WORKERS` | gunicorn worker 数量 | `CPU * 2 + 1` |

---

## 10. 联系与回滚

如需回滚到上一版本，保留旧镜像标签：

```bash
docker tag dfw-ragwheel:<TAG> dfw-ragwheel:<TAG>-backup
```

出现问题时可快速恢复：

```bash
docker stop dfw-ragwheel
docker rm dfw-ragwheel
docker rmi dfw-ragwheel:<TAG>
docker tag dfw-ragwheel:<TAG>-backup dfw-ragwheel:<TAG>
docker run -d -p 4398:4398 --name dfw-ragwheel dfw-ragwheel:<TAG>
```
