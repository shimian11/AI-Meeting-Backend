<div align="center">

**斑马云面试** - banma 公司 · 基于大语言模型的智能模拟面试平台（后端服务）

[![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.4-green?logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0-green)](https://spring.io/projects/spring-ai)
[![MySQL](https://img.shields.io/badge/MySQL-8.4-blue?logo=mysql)](https://www.mysql.com/)
[![MongoDB](https://img.shields.io/badge/MongoDB-7.0-green?logo=mongodb)](https://www.mongodb.com/)
[![Redis](https://img.shields.io/badge/Redis-7.2-red?logo=redis)](https://redis.io/)

</div>

# 斑马云面试 · 服务端（banma-interview-server）

斑马云面试是 banma 公司旗下的 AI 模拟面试与智能对话平台。本仓库为平台后端服务，提供 AI 面试主链路、智能体会话、实时语音转写与长文本语音合成能力。

## 核心能力

- **模拟面试**：简历驱动出题、多智能体协同评分、追问裁决、神态分析、雷达图报告
- **AI 对话**：Spring AI 统一多模型接入（DeepSeek / OpenAI 兼容协议），SSE 流式输出
- **智能体会话**：可配置智能体（星辰工作流 / 本地 Agent），上下文多轮记忆
- **语音链路**：WebSocket 实时转写（讯飞大模型 AST）+ 长文本语音合成（TTS）
- **稳定性治理**：分布式 Single-flight（防重复 AI 调用）、熔断限流、会话快照恢复

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 语言 / 框架 | Java 17 · Spring Boot 3.4.4 · Maven 多模块（`banma-interview` + `banma-interview-admin`） |
| AI 集成 | Spring AI 1.0.0（OpenAI 兼容协议统一接入） |
| 数据 | MySQL 8.4（业务主数据）· MongoDB 7.0（对话消息 / 面试快照）· Redis 7.2 + Redisson（锁 / 缓存 / 限流） |
| 认证 | Sa-Token（Redis 共享登录态） |
| 规则引擎 | LiteFlow（面试追问裁决链） |
| 语音 | 讯飞大模型 AST / 长文本 TTS（OkHttp + JSR-356 WebSocket） |
| 运维 | Docker Compose 一键部署 · GitHub Actions CI |

## 快速开始

### 环境准备

- JDK 17、Maven 3.6.3+（或使用自带 `mvnw`）、Docker、Node.js ≥ 20（前端）

### 一键部署（推荐）

```bash
cp .env.example .env    # 填入大模型 API Key、讯飞密钥等
docker compose up -d    # 启动 mysql + mongo + redis + backend（首次构建较慢）
curl http://localhost:8002/actuator/health
```

### 本地开发

```bash
docker compose up -d mysql mongo redis   # 只起中间件（SQL 自动初始化）
mvnw spring-boot:run -pl admin           # 启动后端，端口 8002
```

### 必要环境变量（.env）

| 变量 | 说明 |
| --- | --- |
| `SPRING_AI_OPENAI_API_KEY / BASE_URL / MODEL` | 大模型接入（OpenAI 兼容，可指向 DeepSeek） |
| `XUNFEI_APP_ID / API_KEY / API_SECRET` | 讯飞语音（ASR / TTS），启动强校验 |
| `MYSQL_PASSWORD` | 生产环境请使用强密码 |

## 模块结构（DDD 分包）

```
com.banma.interview
├── ai           # AI 对话（Spring AI 封装、SSE 流式、会话管理）
├── agent        # 智能体（配置管理、会话、文件资产）
├── interview    # 模拟面试主链路（出题 / 评分 / 追问 / 神态 / 报告）
├── conversation # 会话共享内核（消息持久化、历史、流式支撑）
├── media        # 语音媒体（实时转写、TTS、WebSocket）
├── auth / user  # 认证（Sa-Token）与用户
├── toolkit      # 讯飞星辰客户端等外部集成
└── common       # 规约（Result / 异常 / 错误码）、配置、限流
```

## 工程文档

架构设计、Agent 规格、接口契约、评估测试与非功能性需求文档统一存放于仓库外层 `docs/server/` 目录（软件工程文档体系）。

## 许可与致谢

本项目基于开源 AI-Meeting 项目二次开发而来，感谢原开源社区的贡献。遵循 MIT 许可证。
