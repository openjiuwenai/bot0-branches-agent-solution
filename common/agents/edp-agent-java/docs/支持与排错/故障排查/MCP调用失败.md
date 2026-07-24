# MCP调用失败排错指南

## 问题描述

EDPAgent-Java通过`call_mcp`工具调用MCP（Model Context Protocol）SSE服务或执行本地Python脚本时失败。MCP用于执行沙箱脚本、调用外部产品服务（如理财推荐、基金查询等），是EDPAgent与业务数据层交互的核心通道。

EDPAgent的MCP调用有两种形式：
1. **本地Python脚本执行**：通过ProcessBuilder调用Python脚本，脚本通过环境变量获取MCP SSE配置
2. **MCP SSE服务调用**：Python脚本内部连接MCP SSE服务获取数据

**重要提示**：不使用`call_mcp`工具时，MCP相关配置可以全部留空，不影响服务启动和其他功能。

## 常见症状

1. **脚本执行错误**：Agent回复"产品查询失败"、"脚本执行错误"等
2. **MCP连接失败**：Python脚本无法连接到MCP SSE服务器
3. **Python脚本报错**：stderr输出异常堆栈、ImportError等
4. **日志报错**：
   - `McpInterruptRail: local script execution failed`
   - `MCP script exitCode=xxx`
   - `MCP script timeout after 60s`
   - `MCP script stdout is empty`
   - `failed to parse MCP script stdout JSON`
   - Python脚本中的`Connection refused`、`401 Unauthorized`
5. **返回空结果**：products为空列表、total=0，versatile_query是兜底文案

## 可能原因

| 分类 | 可能原因 | 说明 |
|------|----------|------|
| 服务状态 | MCP SSE服务未启动 | 主备MCP服务都未运行 |
| 配置错误 | MASTER_URL配置错误 | `EDP_MCP_MASTER_URL`格式不正确或地址错误 |
| 配置错误 | 主备都不可用 | MASTER和STANDBY都无法连接 |
| 认证配置 | Token错误/缺失 | `EDP_MCP_ACCESS_TOKEN`无效或需要认证时未设置 |
| 依赖问题 | Python依赖缺失 | 脚本依赖的Python包未安装 |
| 脚本问题 | 脚本内部bug | Python脚本代码错误、参数解析失败 |
| 路径问题 | 脚本路径错误 | script_command指向的脚本不存在 |
| 超时问题 | 脚本执行超时 | 默认60秒超时，复杂查询可能不够 |
| 环境问题 | Python版本不兼容 | 使用了Python 2或版本过低 |
| 网络问题 | 网络不通/防火墙 | Python脚本无法访问MCP SSE服务 |
| 灰度路由 | 灰度标志路由错误 | wap_grayFlag提取异常导致路由到错误节点 |
| 输出格式 | 脚本输出格式错误 | 未输出JSON、最后一行不是有效JSON |

## 排查步骤

### 步骤1：检查MCP环境变量配置

| 环境变量 | 默认值 | 必填 | 检查要点 |
|----------|--------|------|----------|
| `EDP_MCP_MASTER_URL` | （空） | 使用时必填 | MCP SSE主URL，格式：`http://host:port/sse` |
| `EDP_MCP_STANDBY_URL` | （空） | 否 | 备节点URL，主节点不可用时自动切换 |
| `EDP_MCP_ACCESS_TOKEN` | （空） | 视服务端要求 | MCP SSE鉴权Token |
| `EDP_MCP_APP_NAME` | （空） | 否 | 应用标识，用于服务端识别调用方 |

验证配置：
```bash
# 查看容器环境变量
docker exec edp-agent env | grep EDP_MCP

# 确认URL格式正确（必须包含/sse路径）
# 正确示例：http://mcp-server:8080/sse
# 错误示例：http://mcp-server:8080 （缺少/sse）
```

查看日志中MCP配置注入信息：
```bash
docker logs edp-agent 2>&1 | grep "MCP SSE env injected"
```
正常会输出类似：`MCP SSE env injected, wapGrayFlag=xxx, serverUrl=http://...`

### 步骤2：测试MCP SSE URL连通性

从EDPAgent所在机器/容器测试MCP SSE服务连通性：

```bash
# 测试端口连通性
telnet mcp-host 8080
nc -zv mcp-host 8080

# 使用curl测试SSE端点
curl -N http://mcp-host:8080/sse \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Accept: text/event-stream" \
  -v
```

如果有主备两个节点，分别测试：
```bash
# 测试主节点
curl -sf -o /dev/null -w "%{http_code}" http://mcp-master:8080/sse

# 测试备节点
curl -sf -o /dev/null -w "%{http_code}" http://mcp-standby:8080/sse
```

在Docker容器内测试：
```bash
docker exec edp-agent curl -v http://mcp-host:8080/sse
```

### 步骤3：查看脚本错误堆栈

McpInterruptRail会记录脚本的stderr输出，查看详细错误：

```bash
# 查看MCP相关错误日志
docker logs edp-agent 2>&1 | grep -A 10 -i "mcp\|script"

# 查找stderr输出
docker logs edp-agent 2>&1 | grep "stderr="
```

常见stderr错误：
- `ModuleNotFoundError: No module named 'xxx'` → Python依赖缺失
- `ConnectionRefusedError: [Errno 111] Connection refused` → MCP服务不可达
- `requests.exceptions.HTTPError: 401 Client Error` → Token无效
- `json.JSONDecodeError` → MCP返回非JSON或解析失败
- `FileNotFoundError` → 脚本路径错误

### 步骤4：本地运行Python脚本调试

**这是最有效的调试方法**。在本地直接运行Python脚本，传入参数测试：

1. **找到脚本位置**：
   - 默认脚本目录：`scenarios/wealth-demo/skills/`
   - 查看skill配置：`scenarios/wealth-demo/skills/*/SKILL.yaml`

2. **设置环境变量**（模拟EDPAgent注入的环境变量）：
   ```bash
   # Linux/macOS
   export MCP_SERVER_URL=http://your-mcp:8080/sse
   export MCP_ACCESS_TOKEN=your-token
   export MCP_APP_NAME=edp-agent
   export SKILL_INPUT='{"param":"value"}'
   export PYTHONIOENCODING=utf-8
   ```
   ```powershell
   # Windows PowerShell
   $env:MCP_SERVER_URL="http://your-mcp:8080/sse"
   $env:MCP_ACCESS_TOKEN="your-token"
   $env:MCP_APP_NAME="edp-agent"
   $env:SKILL_INPUT='{"param":"value"}'
   $env:PYTHONIOENCODING="utf-8"
   ```

3. **运行脚本**：
   ```bash
   cd scenarios/wealth-demo/skills/<skill-name>/scripts
   python run_xxx.py '{"param":"value"}'
   ```

**示例（理财推荐脚本）：**
```bash
cd scenarios/wealth-demo/skills/interact_finance_rec_skill/scripts
export MCP_SERVER_URL=http://localhost:8080/sse
python run_interact_finance_rec_skill.py '{"user_id":"u001","risk_level":"R2"}'
```

观察输出：
- 正常：最后一行输出JSON格式结果
- 异常：查看Python traceback定位具体错误

### 步骤5：检查Python依赖

EDPAgent的MCP Python脚本依赖列在`deploy/requirements-mcp.txt`：
```
mcp
```

其他可能的依赖（查看脚本import语句）：
- `requests`
- `sseclient`/`sseclient-py`
- `mcp`（官方MCP Python SDK）

安装依赖：
```bash
# Docker镜像中应该已安装，本地开发需要手动安装
pip install -r deploy/requirements-mcp.txt

# 如果脚本还有其他依赖，一并安装
pip install requests sseclient-py

# 验证mcp包是否安装
python -c "import mcp; print(mcp.__version__)"
```

检查Python版本：
```bash
python --version
# 推荐Python 3.9+
```

### 步骤6：检查脚本路径和权限

1. **确认script_command正确**：
   - 相对路径：相对于skill的scripts目录
   - 绝对路径：需要确保文件存在
   - Python脚本：格式应为`python scripts/run_xxx.py`或直接`./run_xxx.py`

2. **检查脚本文件存在**：
   ```bash
   # 默认skills目录
   ls -la scenarios/wealth-demo/skills/*/scripts/
   
   # 在容器内检查
   docker exec edp-agent ls -la /app/scenarios/wealth-demo/skills/
   ```

3. **检查执行权限**：
   ```bash
   chmod +x scenarios/wealth-demo/skills/*/scripts/*.py
   ```

### 步骤7：检查灰度路由逻辑

McpInterruptRail根据`wap_grayFlag`决定路由到主节点还是备节点：
- `wap_grayFlag`以`JD`开头 → 路由到`MASTER_URL`
- 其他情况 → 路由到`STANDBY_URL`

查看日志确认路由：
```bash
docker logs edp-agent 2>&1 | grep "wapGrayFlag"
```

如果灰度标志提取失败：
1. 检查`script_params`中`mcp_required_params`格式
2. 支持两种格式：JSON字符串（单引号Python dict格式）或已解析Map
3. 路径：`mcp_required_params.custom_data.inputs.wap_grayFlag`

### 步骤8：增大脚本超时时间（如需要）

默认脚本超时为60秒（硬编码在McpInterruptRail中：`SCRIPT_TIMEOUT = Duration.ofSeconds(60)`）。

如果复杂查询需要更长时间：
1. 优化脚本逻辑，减少调用时长
2. 检查是否可以分页或异步处理
3. 如需修改超时，需修改代码：`engine/src/main/java/com/huawei/ascend/edp/rail/McpInterruptRail.java:37`

### 步骤9：验证脚本输出格式

Python脚本**最后一行必须输出有效的JSON**，McpInterruptRail会取stdout最后一行`{...}`作为结果。

**正确输出示例**：
```json
{"status": "success", "products": [...], "total": 10, "versatile_query": "推荐理财产品", "result_key": "mcp_products_data"}
```

**错误输出示例**：
- 最后一行不是JSON
- 输出了多余的日志到stdout（日志应输出到stderr）
- JSON格式错误（逗号、引号问题）

脚本调试提示：
- 调试日志输出到stderr（`print("debug", file=sys.stderr)`）
- 只有最终结果JSON输出到stdout
- 确保最后一行单独是JSON

### 步骤10：检查防火墙和网络策略

如果MCP SSE服务在其他机器：
1. 确认安全组开放MCP端口（如8080）
2. 确认网络ACL允许EDPAgent到MCP的访问
3. 如果有HTTP代理，确认Python脚本配置了代理
4. 检查是否需要VPN或专线访问

测试容器内网络：
```bash
docker exec edp-agent bash -c "curl -v http://mcp-host:8080/sse"
```

## 解决方案

### 方案1：启动并配置MCP SSE服务

确保MCP SSE服务已启动并正常运行：
```bash
# 如果MCP是Docker部署
docker ps | grep mcp
docker logs mcp-server --tail 50

# 测试健康检查（如有）
curl http://mcp-host:8080/health
```

### 方案2：设置正确的MCP环境变量

**最小配置（单节点）：**
```bash
EDP_MCP_MASTER_URL=http://mcp-server:8080/sse
EDP_MCP_ACCESS_TOKEN=your-token-if-required
EDP_MCP_APP_NAME=edp-agent
```

**主备高可用配置：**
```bash
EDP_MCP_MASTER_URL=http://mcp-master:8080/sse
EDP_MCP_STANDBY_URL=http://mcp-standby:8080/sse
EDP_MCP_ACCESS_TOKEN=your-token
EDP_MCP_APP_NAME=edp-agent
```

**不使用MCP（留空即可）：**
```bash
# 不设置或留空
EDP_MCP_MASTER_URL=
EDP_MCP_STANDBY_URL=
```
注意：不使用MCP时不要在actrule中启用call_mcp工具。

### 方案3：安装Python依赖

**本地开发环境：**
```bash
pip install -r deploy/requirements-mcp.txt
# 安装其他脚本依赖
pip install requests sseclient-py
```

**Docker镜像：**
确保Dockerfile中已安装依赖（查看deploy/Dockerfile），如果缺失需要修改Dockerfile重建镜像。

### 方案4：本地调试并修复脚本bug

使用步骤4中的方法本地运行脚本，根据错误信息修复：

常见脚本问题修复：
1. **ImportError** → `pip install`缺失的包
2. **Connection refused** → 检查MCP_URL和服务状态
3. **401错误** → 检查ACCESS_TOKEN
4. **参数解析错误** → 检查SKILL_INPUT格式
5. **KeyError** → 检查输入参数是否完整
6. **JSON输出错误** → 确保最后一行是纯JSON，日志走stderr

参考脚本示例：`scenarios/wealth-demo/skills/interact_finance_rec_skill/scripts/`

### 方案5：修正脚本路径配置

检查skill配置中的script_command：
1. 查看`SKILL.yaml`或`scriptconfig.yaml`
2. 确认路径相对于skills目录正确
3. 如果是Python脚本，确保命令格式正确（`python scripts/xxx.py`）

路径解析逻辑（McpInterruptRail.java:250-270）：
1. 优先使用绝对路径
2. 然后相对于skillsDir（场景的skills目录）
3. 然后相对于当前工作目录
4. 最后相对于默认路径`../scenarios/wealth-demo/skills/`

### 方案6：处理MCP SSE鉴权

如果MCP服务需要Token认证：
1. 获取有效的Access Token
2. 设置`EDP_MCP_ACCESS_TOKEN`环境变量
3. Token会通过`MCP_ACCESS_TOKEN`环境变量注入Python脚本
4. Python脚本需要在请求MCP时带上该Token（通常作为Authorization头）

参考示例脚本中的Token使用方式。

### 方案7：修复脚本输出格式

确保Python脚本正确输出：
```python
import sys
import json

def main():
    # ... 业务逻辑 ...
    result = {
        "status": "success",
        "products": [...],
        "total": len(products),
        "versatile_query": "推荐理财产品",
        "result_key": "mcp_products_data"
    }
    # 调试信息输出到stderr
    print(f"Found {len(products)} products", file=sys.stderr)
    # 最终结果输出到stdout（最后一行必须是JSON）
    print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__":
    main()
```

注意：
- 使用`ensure_ascii=False`保证中文正常
- 不要在stdout输出其他内容
- 错误信息输出到stderr

### 方案8：网络和防火墙问题排查

1. **同一Docker网络**：EDPAgent和MCP在同一Docker网络时使用服务名访问
   ```yaml
   # docker-compose.yml示例
   services:
     mcp-server:
       image: mcp-server:latest
     edp-agent:
       image: edp-agent:latest
       environment:
         - EDP_MCP_MASTER_URL=http://mcp-server:8080/sse
   ```

2. **跨主机访问**：使用IP地址或正确的主机名，确保防火墙开放端口
3. **K8s部署**：使用Service名称访问，确认NetworkPolicy允许

## 脚本本地调试方法汇总

标准调试流程：

```bash
# 1. 进入脚本目录
cd scenarios/wealth-demo/skills/interact_finance_rec_skill/scripts

# 2. 设置环境变量
export MCP_SERVER_URL=http://your-mcp:8080/sse
export MCP_ACCESS_TOKEN=your-token
export MCP_APP_NAME=edp-agent
export PYTHONIOENCODING=utf-8

# 3. 直接运行脚本，传入JSON参数
python run_interact_finance_rec_skill.py '{"risk_level":"R2","amount":100000}'

# 4. 如果报错，添加-v查看详细输出（如脚本支持）
# 或使用Python调试器
python -m pdb run_interact_finance_rec_skill.py '{"risk_level":"R2"}'
```

调试技巧：
- 在脚本中添加`print(var, file=sys.stderr)`打印调试信息
- 使用try-except捕获异常并打印完整堆栈
- 先用curl测试MCP SSE接口确认服务正常
- 对比Mock数据和真实返回数据的差异

## 相关配置/日志关键词

### 配置项
- `edpa.agent.mcpsse.master-url` - MCP主节点URL
- `edpa.agent.mcpsse.standby-url` - MCP备节点URL
- `edpa.agent.mcpsse.access-token` - 鉴权Token
- `edpa.agent.mcpsse.app-name` - 应用名

### 环境变量注入到Python脚本
- `MCP_SERVER_URL` - 选中的MCP URL（主或备）
- `MCP_ACCESS_TOKEN` - 鉴权Token
- `MCP_APP_NAME` - 应用名
- `SKILL_INPUT` - 脚本入参JSON
- `PYTHONIOENCODING` - 编码（utf-8）

### 日志关键词
- `McpInterruptRail: intercepting call_mcp` - 开始执行
- `McpInterruptRail: execute script command=` - 执行的命令
- `McpInterruptRail: MCP SSE env injected` - 环境变量注入
- `McpInterruptRail: script exitCode=` - 脚本退出码
- `McpInterruptRail: MCP script timeout after 60s` - 超时
- `McpInterruptRail: local script execution failed` - 执行失败
- `failed to parse MCP script stdout JSON` - JSON解析失败
- `MCP script stdout is empty` - 无输出
- `stored call_mcp result to ToolDataChannel` - 执行成功

### 代码位置
- MCP中断Rail：`engine/src/main/java/com/huawei/ascend/edp/rail/McpInterruptRail.java`
- MCP工具定义：`engine/src/main/java/com/huawei/ascend/edp/tools/CallMcpTool.java`
- MCP依赖：`deploy/requirements-mcp.txt`
- 示例脚本：`scenarios/wealth-demo/skills/interact_finance_rec_skill/scripts/`
- MCP SSE客户端示例：`scenarios/wealth-demo/skills/interact_finance_rec_skill/scripts/mcp_sse_client.py`

## 预防措施

1. **本地脚本优先调试**：所有Python脚本必须先在本地调试通过再部署
2. **Mock MCP服务**：开发环境使用Mock MCP服务，不依赖真实服务
3. **依赖固化**：使用requirements.txt固定Python依赖版本
4. **脚本输出规范**：
   - 日志输出到stderr
   - 结果输出到stdout且最后一行是JSON
   - 统一错误格式
5. **超时合理设置**：脚本设计考虑超时，避免无限等待
6. **主备高可用**：生产环境配置MASTER和STANDBY双节点
7. **异常捕获**：Python脚本内部做好异常捕获，返回友好错误
8. **参数校验**：脚本入口校验必填参数，提前返回明确错误
9. **日志规范**：脚本添加足够的日志（stderr）便于排查
10. **端到端测试**：每次修改脚本后从EDPAgent发起完整调用测试

## 参考链接

- [call_mcp工具特性文档](../../getting-started/features/call_mcp工具特性.md)
- [Python脚本配置参考](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/resources/governance/scriptconfig.yaml)
- [MCP Python SDK文档](https://github.com/modelcontextprotocol/python-sdk)
- [环境变量参考](../../reference/环境变量参考.md#mcp-sse-配置)
- [McpInterruptRail代码](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/rail/McpInterruptRail.java)
- [示例MCP客户端脚本](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/scenarios/wealth-demo/skills/interact_finance_rec_skill/scripts/mcp_sse_client.py)
