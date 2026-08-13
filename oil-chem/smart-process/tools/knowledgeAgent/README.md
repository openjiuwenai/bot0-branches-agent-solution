# 制度问答智能体开发部署 README

## 简介

本文档为 openjiuwen 制度问答智能体 快速开发部署指南，分为两大核心流程：**组件库知识库RagFlow部署对接、智能体配置文件导入**，基于 openGauss \+ RagFlow 架构实现制度文档智能问答能力。

官方参考文档：https://docs.opengauss.org/zh/docs/latest/datavec/opengauss_ragflow.html



## 一、开发中心 \- 组件库（知识库搭建\+RagFlow对接）

### 1\. 环境前置要求

- 基础配置：CPU≥4核、RAM≥16G、磁盘≥50G

- 运行环境：Arm架构 openEuler 等适配系统，Docker≥28\.0\.1、Docker Compose≥2\.33\.1

- 准备素材：企业制度文档（PDF/TXT/Word/PPT等）

### 2\. RagFlow 部署

1. 拉取项目仓库并进入部署目录
`git clone https://gitee.com/kunpeng_compute/KunpengRAG.git
cd KunpengRAG/deployment/docker-compose/ragflow`

2. 启动容器服务
        `docker compose up -d`

3. 验证：执行 `docker ps | grep ragflow`，所有容器状态为 healthy 即部署成功

4. 访问：浏览器打开 `http://服务器IP/login`，注册并登录后台

### 3\. 模型配置（Ollama）

- 嵌入模型：类型 `embedding`，名称 `bge-m3:latest`，最大token 400

- 对话模型：类型 `chat`，名称 `llama3.2:latest`，最大token 400

- 配置完成后设置为系统默认模型

### 4\. 创建并配置知识库

1. RagFlow后台新建知识库，自定义名称（如：制度问答知识库）

2. 上传制度类文档，执行自动解析、分块、向量化

3. 完成检索测试，验证文档召回正常

4. 进入 openjiuwen【开发中心\-组件库】，新建同名知识库

5. 对接RagFlow服务，填写服务IP、端口、API密钥、知识库ID，开启同步

6. 连通测试无误，完成知识库部署对接



## 二、开发中心 \- 智能体管理（导入配置文件）

### 1\. 获取配置文件

从项目代码仓库下载配置文件：`agent_制度问答.jsonl`

### 2\. 导入智能体配置

1. 进入 openjiuwen【开发中心 \- 智能体管理】

2. 点击「导入智能体配置」，上传 `agent_制度问答.jsonl` 文件

3. 系统自动加载智能体角色、提示词、检索规则等配置

4. 绑定第一步已对接完成的制度问答知识库

### 3\. 调试上线

- 进入智能体调试页面，发起制度相关问答测试

- 按需调整检索权重、回答阈值、提示词

- 调试正常后发布智能体，投入业务使用
