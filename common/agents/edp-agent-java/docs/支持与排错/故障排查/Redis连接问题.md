# Redis连接失败排错指南

## 问题描述

EDPAgent-Java服务启动或运行过程中无法连接到Redis服务器，导致服务启动失败或会话状态无法持久化。Redis是EDPAgent的核心依赖，用于存储Todo任务列表（RedisTodoStore）和会话Checkpoint（中断恢复点）。

## 常见症状

1. **服务启动失败**：Spring Boot容器启动过程中抛出异常，进程立即退出
2. **日志报Connection refused**：`Unable to connect to Redis`、`Connection refused: no further information`
3. **健康检查失败**：Docker HEALTHCHECK标记为unhealthy状态
4. **启动日志中出现关键错误**：
   - `[EDPA-DIAG] REDIS_HEALTH CHECK FAILED`
   - `Redis Checkpointer registration failed`
   - `Redis health check failed`
5. **版本不兼容错误**：`Redis version too low: x.x.x, require >= 5.0`

## 可能原因

| 分类 | 可能原因 | 说明 |
|------|----------|------|
| 服务状态 | Redis服务未启动 | Redis进程未运行或已崩溃 |
| 网络配置 | 地址/端口配置错误 | `EDPA_REDIS_HOST`或`EDPA_REDIS_PORT`设置不正确 |
| 认证配置 | 密码错误 | `EDPA_REDIS_PASSWORD`与Redis实际密码不符 |
| 网络连通 | 网络不通/防火墙阻断 | 网络ACL、防火墙、安全组阻止连接 |
| Docker网络 | 容器网络问题 | Docker容器无法访问宿主机Redis（需用`host.docker.internal`） |
| 部署模式 | Redis模式配置错误 | `EDPA_REDIS_MODE`设置与实际部署模式不匹配（single/sentinel/cluster） |
| 版本要求 | Redis版本过低 | EDPAgent要求Redis版本 >= 5.0 |
| 超时配置 | 连接/读写超时过短 | 跨网络部署时默认超时（5s连接/10s读写）不足 |

## 排查步骤

### 步骤1：检查Redis服务状态

**本地部署：**
```bash
# Windows
netstat -ano | findstr :6379
tasklist | findstr redis

# Linux/macOS
ps aux | grep redis
netstat -tlnp | grep 6379
systemctl status redis
```

**Docker部署Redis：**
```bash
docker ps | grep redis
docker logs redis --tail 50
```

### 步骤2：使用redis-cli测试连接

```bash
# 无密码
redis-cli -h <host> -p <port> ping

# 有密码
redis-cli -h <host> -p <port> -a <password> ping
# 预期返回：PONG

# 测试版本（需要>=5.0）
redis-cli -h <host> -p <port> info server | grep redis_version
```

如果ping返回PONG但仍连接失败，继续下一步检查。

### 步骤3：检查环境变量配置

检查以下环境变量是否正确设置：

| 环境变量 | 默认值 | 检查要点 |
|----------|--------|----------|
| `EDPA_REDIS_HOST` | `localhost` | Docker连宿主机需用`host.docker.internal`（Win/Mac）或宿主机IP（Linux） |
| `EDPA_REDIS_PORT` | `6379` | 确认Redis实际监听端口 |
| `EDPA_REDIS_PASSWORD` | （空） | 无密码时不要设置或留空 |
| `EDPA_REDIS_DB` | `0` | 确认数据库编号正确 |
| `EDPA_REDIS_MODE` | `single` | single/sentinel/cluster必须与实际部署一致 |

验证配置：
```bash
# 查看容器环境变量
docker exec edp-agent env | grep EDPA_REDIS
```

### 步骤4：检查Docker网络连通性（仅Docker部署）

如果EDPAgent运行在Docker中而Redis在宿主机：

1. **Windows/Mac Docker Desktop**：使用`host.docker.internal`作为主机名
   ```bash
   EDPA_REDIS_HOST=host.docker.internal
   ```

2. **Linux Docker**：使用宿主机的实际IP地址，或使用`--network host`模式
   ```bash
   # 查看宿主机IP（在容器内执行）
   docker exec edp-agent ping host.docker.internal
   # 或查看docker0网桥IP
   ip addr show docker0
   ```

3. **测试容器到Redis的连通性**：
   ```bash
   docker exec edp-agent curl -v telnet://<redis-host>:6379
   # 或安装redis-cli测试
   docker exec edp-agent apt-get update && apt-get install -y redis-tools
   docker exec edp-agent redis-cli -h host.docker.internal ping
   ```

### 步骤5：检查Redis模式配置

如果使用哨兵或集群模式，需正确配置相关参数：

**哨兵模式（sentinel）**：
- `EDPA_REDIS_MODE=sentinel`
- 需要配置sentinel节点列表和master名称（在TodoRedisProperties中配置）

**集群模式（cluster）**：
- `EDPA_REDIS_MODE=cluster`
- 需要配置所有集群节点列表

验证模式匹配：查看启动日志中的`[EDPA-DIAG] REDIS_CONFIG`行，确认mode参数正确。

### 步骤6：检查防火墙和网络策略

```bash
# 本地端口连通性测试
telnet <redis-host> 6379
# 或
nc -zv <redis-host> 6379

# 云环境检查：
# - 安全组是否开放6379端口
# - VPC网络ACL是否允许访问
# - Redis白名单是否包含EDPAgent所在机器IP
```

### 步骤7：查看Redis日志

```bash
# Redis默认日志位置
tail -f /var/log/redis/redis-server.log
# 或Docker日志
docker logs redis -f
```

关注以下错误：
- 认证失败：`WRONGPASS invalid username-password pair`
- 最大连接数：`max number of clients reached`
- 内存不足：`OOM command not allowed when used memory > 'maxmemory'`

### 步骤8：检查超时配置（跨网络部署）

如果Redis与EDPAgent跨机房/跨VPC部署，默认超时可能不足：

```bash
# 增大超时（生产环境建议）
EDPA_REDIS_CONNECT_TIMEOUT=10000
EDPA_REDIS_SOCKET_TIMEOUT=20000
```

## 解决方案

### 方案1：启动Redis服务

**本地启动：**
```bash
# Windows：下载Redis for Windows后启动
redis-server.exe

# Linux
systemctl start redis
# 或
redis-server /etc/redis/redis.conf
```

**Docker启动Redis：**
```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine
```

### 方案2：修正环境变量配置

**本地开发（.env文件）：**
```bash
EDPA_REDIS_HOST=localhost
EDPA_REDIS_PORT=6379
EDPA_REDIS_PASSWORD=
EDPA_REDIS_DB=0
EDPA_REDIS_MODE=single
```

**Docker Compose示例（同网络）：**
```yaml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
  
  edp-agent:
    image: edp-agent:latest
    environment:
      - EDPA_REDIS_HOST=redis  # 使用服务名
      - EDPA_REDIS_PORT=6379
    depends_on:
      - redis
```

**Docker连宿主机Redis：**
```bash
docker run -d --name edp-agent \
  -e EDPA_REDIS_HOST=host.docker.internal \
  -p 8190:8190 \
  edp-agent:latest
```

### 方案3：验证并修复密码

1. 确认Redis是否设置了密码：
   ```bash
   # 查看redis.conf中requirepass配置
   grep requirepass /etc/redis/redis.conf
   ```

2. 如果设置了密码，确保`EDPA_REDIS_PASSWORD`与之完全一致
3. 如果不需要密码，清空该环境变量（不要设为空格）

### 方案4：升级Redis版本

EDPAgent要求Redis版本 >= 5.0。如果版本过低：

```bash
# 检查当前版本
redis-cli info server | grep redis_version

# 升级到Redis 7.x（推荐）
# Docker方式最简单
docker run -d --name redis -p 6379:6379 redis:7-alpine
```

### 方案5：调整超时和连接参数

跨网络部署时增大超时：
```bash
EDPA_REDIS_CONNECT_TIMEOUT=10000    # 连接超时10秒
EDPA_REDIS_SOCKET_TIMEOUT=30000     # 读写超时30秒
```

### 方案6：检查并调整Redis内存配置

如果Redis日志显示OOM：
1. 增大Redis `maxmemory`配置
2. 设置合适的淘汰策略（如`allkeys-lru`）
3. 检查EDPAgent的TTL配置：
   - `EDPA_REDIS_CHECKPOINTER_TTL=60`（Checkpoint TTL，分钟）
   - `EDPA_REDIS_TODO_TTL=3600`（Todo TTL，秒）

## 相关配置/日志关键词

### 配置项
- `edpa.agent.redis.host` - Redis主机
- `edpa.agent.redis.port` - Redis端口
- `edpa.agent.redis.password` - Redis密码
- `edpa.agent.redis.mode` - Redis模式（single/sentinel/cluster）
- `edpa.agent.redis.connect-timeout-ms` - 连接超时（毫秒）
- `edpa.agent.redis.socket-timeout-ms` - 读写超时（毫秒）

### 日志关键词（启动时搜索这些）
- `[EDPA-DIAG] REDIS_CONFIG` - Redis配置打印
- `[EDPA-DIAG] REDIS_CHECKPOINTER registered` - Checkpointer注册成功
- `[EDPA-DIAG] REDIS_HEALTH CHECK passed` - 健康检查通过
- `[EDPA-DIAG] REDIS_HEALTH CHECK FAILED` - 健康检查失败
- `[EDPA-DIAG] REDIS_VERSION_TOO_LOW` - 版本过低
- `Redis health check failed` - 健康检查异常堆栈
- `Connection refused` - 连接被拒绝
- `WRONGPASS` - 密码错误
- `NOAUTH` - 需要认证

### 代码位置
- Redis配置类：`engine/src/main/java/com/huawei/ascend/edp/config/RedisConfig.java`
- Redis健康检查：`engine/src/main/java/com/huawei/ascend/edp/todo/RedisTodoStore.java:52-76`
- TodoRedis属性配置：`engine/src/main/java/com/huawei/ascend/edp/config/TodoRedisProperties.java`

## 预防措施

1. **启动前测试连接**：在启动脚本中先执行redis-cli ping确认Redis可用
2. **配置健康检查**：利用Docker HEALTHCHECK或外部监控持续检测
3. **Redis高可用部署**：
   - 生产环境使用Redis Sentinel或Cluster模式
   - 配置主从复制和自动故障转移
   - 设置合理的内存淘汰策略
4. **连接池参数调优**：根据实际并发调整连接池大小
5. **监控告警**：
   - Redis存活监控
   - 内存使用率告警
   - 连接数监控
   - 慢查询日志监控
6. **定期备份**：根据业务需要配置RDB/AOF持久化策略

## 参考链接

- [环境变量完整参考](../../reference/环境变量参考.md#redis-连接)
- [健康检查与日志分析](../../operations/健康检查与日志.md)
- [Docker部署指南](../../operations/Docker部署指南.md)
- [Redis配置类](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/config/RedisConfig.java)
