# 原油加工月计划排产系统（crude_run_planner）

本工程实现原油加工预排产，通过输入一份月度计划 Excel模板，输出常减压（CDU）月度预排产结果：加工批次序列、罐区逐日物流网格、达标核对，并回填成业务方熟悉的 Excel 报表。

---

## 1. 这个工程做什么

根据次月原油加工计划（初稿）、原油到港计划，以及原油、罐、掺炼参数配置，求解多个可执行的月度加工计划，不只是排出加工顺序，还要保证油真的在罐里供得上装置——罐容够不够、船到了有没有地方卸、供料罐里的旧油来不来得及炼完腾位、单管道输油排不排得开。并从可行解中选出优选解。

### 核心方法：Benders 分解两阶段

```
CP-SAT 主问题：排加工序列（炼什么油、各炼多久、何时开工）
        ↓ seq
tank_fill 子问题：把序列落到具体罐、逐日正向仿真罐区物流
        ↓ gaps（某天某油种计划要炼但罐里没油）
generate_benders_cuts：缺口 → 割平面（上界割）
        ↑ 反馈为 CP-SAT 约束，迭代至无缺口
```

外层再套**自适应批次阶梯**（批次上限 4 档逐级放宽）×**多随机种子**，评分择优输出 1~N 个**优选解**。

### 主要产出

- `原油加工月计划_预排产结果_benders_*_{序号}.xlsx`：回填预排产 sheet——供料罐写「可用库存 / 原油品种 / 负荷 / 加工时间 / 加工量 / 到库量」，储罐写「可用库存 / 原油品种 / 到库量」；跨批混合的负荷格挂分段明细批注（如 `610×11h + 260×13h`）。
- Web 界面的 5 个可视化视图：参数输入、Phase0 预处理、排程甘特图、罐物流网格、达标对比。


---

## 2. 目录结构

```
mcp/crude_run_planner/
├── crude_run_planner.py      # 核心：Excel 读取 + CP-SAT 排程 + 罐物流仿真 + 评分 + 回填（单文件）
├── api_server.py             # FastAPI 服务，把排产包成 HTTP 接口（:8100）
├── requirements.txt          # Python 依赖
├── start.sh / stop.sh        # 一键启停前后端
├── web/                      # Next.js 前端（:3100）
│   ├── next.config.js        #   rewrites /api/:path* → 127.0.0.1:8100
│   └── src/app/              #   总览 / 参数输入 / Phase0 / 甘特图 / 罐网格 / 达标对比
├── logs/                     # 运行日志与 pid（gitignore）
└── 原油加工月计划模板_*月规划部.xlsx   # 输入模板
```

---

## 3. 环境依赖

| 项 | 要求 | 说明 |
|---|---|---|
| Python | **>= 3.10** | CP-SAT 求解与仿真 |
| Node.js | **>= 18**（Next.js 14 要求） | 仅 Web 前端需要 |
| 操作系统 | Linux / WSL / macOS | `start.sh`/`stop.sh` 为 bash 脚本 |
| 数据库 | **无** | 本模块不连库，输入输出全走 Excel |

### Python 依赖（`requirements.txt`）

| 包 | 版本 | 用途 |
|---|---|---|
| `ortools` | 9.15.6755 | CP-SAT 求解器（排产核心） |
| `fastapi` | 0.139.0 | HTTP 服务 |
| `uvicorn` | 0.51.0 | ASGI 服务器 |
| `openpyxl` | 3.1.5 | Excel 模板读取 + 结果回填 |
| `pydantic` | 2.13.4 | 请求/响应模型 |


### 前端依赖（`web/package.json`）

`next@14.2.5` + `react@18` + `recharts@2`（图表）+ `tailwindcss@3` + `typescript@5`。

---

## 4. 安装

```bash
cd crude_run_planner

# ① Python 环境
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

# ② 前端依赖（只需 Web 界面时）
cd web && npm install && cd ..
```

> ⚠️ **venv 不可跨目录搬迁**。若把本模块整个目录移动过位置，必须删掉重建：
>
> ```bash
> rm -rf .venv && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
> ```


---

## 5. 运行

### 方式一：一键启停（推荐）

```bash
./start.sh -d      # 后台启动前后端，日志写入 logs/
./stop.sh          # 停止全部
./stop.sh backend  # 仅停后端
./stop.sh frontend # 仅停前端
```

| 服务 | 地址 | 说明 |
|---|---|---|
| 前端 | `http://<本机IP>:3100/` | 绑 `0.0.0.0`，支持远程访问 |
| 后端 | `http://127.0.0.1:8100/` | 仅本机；前端经 `next.config.js` rewrites 代理 |
| 健康检查 | `http://<本机IP>:3100/api/health` | 经前端代理探活，返回 `{"status":"ok"}` |

```bash
tail -f logs/backend.log logs/frontend.log   # 看日志
```

`./start.sh` 不带 `-d` 为前台模式，**只启后端**并占据当前终端；前端需另开终端 `cd web && npm run dev`。脚本自带重复启动检测，已在跑的服务会跳过。

### 方式二：分别手动启动

```bash
# 后端
.venv/bin/uvicorn api_server:app --host 127.0.0.1 --port 8100

# 前端（另开终端）
cd web && npm run dev -- -H 0.0.0.0
```


### 使用流程（Web）

1. 打开 `http://<本机IP>:3100/`
2. 顶栏点「运行排产」→ 弹窗选择输入模板（列出目录下所有 `原油加工月计划模板*.xlsx`）
3. 排产在后台线程跑，界面轮询进度并实时滚动日志（求解耗时取决于模板规模与阶梯档位，通常数分钟）
4. 完成后浏览 5 个视图，或下载结果 xlsx

---

## 6. HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/health` | 探活 |
| `GET` | `/api/templates` | 列出可选输入模板（目录下 `原油加工月计划模板*.xlsx`） |
| `POST` | `/api/run` | 启动排产，body `{"template": "文件名"}`（省略则用默认模板）；已在跑时返回 `already_running` |
| `GET` | `/api/status` | 轮询状态 `running`/`done`/`error` + 实时日志；`done` 时附完整结果 |
| `GET` | `/api/data` | 取上次排产结果缓存 |
| `GET` | `/api/download/{idx}` | 下载第 idx 个结果 xlsx（`idx` 从 1 起，`_1` 为评分最优） |

---

## 7. 输入模板

放在模块根目录，文件名须以 `原油加工月计划模板` 开头、`.xlsx` 结尾。

关键 sheet：

| sheet | 内容 |
|---|---|
| `原油加工计划` | 各油种本月计划加工量、负荷、国内总量小计 |
| `原油到港计划` | 船期、油种、载量 |
| `参数_罐` | 罐容 / 底油 / 罐类型（供料罐 G / 储罐 T）/ 允许油种 |
| `参数_原油` | 进口/国内、可否单炼、**卸油允许混合油种**白名单 |
| `参数_掺炼` | 主料 + 各掺炼组分的负荷档 |
| `参数_全局` | 卸油速度、输油速度、批次时长上下限、换油残余阈值、求解时限、优选解数量等 |
| `技术经济指标` | 月度最大滞期天数（按行标签读 B 列） |
| 名字含`预排产`的 sheet | 罐区月初库存来源 + 结果回填目标（按名字模糊匹配，取首个命中） |


---


## 8. 常见问题

**前端报 `Unexpected token 'I', "Internal S"... is not valid JSON`**
后端没起来。前端 rewrites 代理到 `127.0.0.1:8100` 连不上时，Next 返回纯文本 `Internal Server Error`，页面 `response.json()` 解析失败。先查 `logs/backend.log`；若见 `nohup: failed to run command 'uvicorn'`，是 venv 路径失效，按 §4 重建。

**`./start.sh` 报"未找到虚拟环境 / node_modules"**
按 §4 先装依赖。

**排产跑很久没结果**
正常。阶梯 4 档 × 多种子 × 每轮 Benders 最多 8 次迭代，单轮时限由模板 `单轮求解时限(秒)` 控制（默认 240）。前端「运行排产」弹窗可实时看日志判断进度。

**结果 xlsx 找不到**
输出落在模块根目录，`_1` 为评分最优。两种入口的命名不同：

**`stop.sh` 停不掉**
优先按 `logs/*.pid` 停，无 pid 文件时按进程命令行模式兜底。若 pid 文件残留了失败进程的旧 PID，删掉 `logs/*.pid` 后重试。
