# liwanxing-learning-projects

基于 Spring AI 的智能助手学习项目，涵盖 RAG 知识库、Agent Function Calling、长期记忆、跨语言 Agent 协作等 AI 应用核心能力。

## 功能概览

### 智能助手（Agent 模式）

模型根据用户问题自主选择调用哪个工具，无需手写路由逻辑：

| 工具 | 能力 | 触发示例 |
|------|------|---------|
| **RagTool** | 知识库向量检索 + Rerank 重排序 | "请假怎么请？" |
| **TimeTool** | 查询当前时间 | "现在几点？" |
| **WeatherTool** | 高德 API 查天气 | "北京天气怎么样？" |
| **UserQueryTool** | 查询系统用户、角色、权限 | "系统有多少用户？" |
| **GraphTool** | 经营分析（调 Java Graph 工作流） | "分析最近销售趋势" |
| **ResearchTool** | 深度调研（调 Python LangGraph Agent） | "调研主流的 Java AI 框架" |

![智能助手](docs/images/chat.png)

### 记忆系统（三层）

| 层级 | 实现 | 特点 |
|------|------|------|
| **短期窗口** | Spring AI ChatMemory（MySQL） | 同一会话内记住上下文，窗口自动裁剪 |
| **对话摘要** | ConversationSummaryService | 消息超窗口时自动压缩旧消息为摘要 |
| **长期记忆** | UserMemoryService（MySQL + Milvus 双写） | AI 自动提取用户偏好，跨所有会话生效 |

长期记忆支持前端管理页面查看、编辑、删除，修改删除同步向量库。

![记忆管理](docs/images/memory.png)

### 知识库管理

- 文档上传（PDF/TXT/DOC/MD），Tika 解析 + 自动切分 + 向量化
- 多种切分策略（TOKEN / FIXED_LENGTH / SEMANTIC）
- 异步处理 + 状态轮询 + 失败重试
- 文档删除同步清理 Milvus 向量

![知识库管理](docs/images/rag-docs.png)

### 用户管理（RBAC）

- 用户/角色/权限五表模型
- Sa-Token 认证授权 + Redis 存储
- BCrypt 密码加密

<!-- 截图：用户管理页面 -->
<!-- ![用户管理](docs/images/user.png) -->

### Langfuse 可观测性

通过 Langfuse 追踪每次 Agent 调用的完整链路（输入/输出/token 用量/延迟），支持调试和成本监控。

![Langfuse 评测](docs/images/langfuse-eval.png)

## 架构图

```
┌──────────────────────────────────────────────────────────┐
│                      前端（Vue 3 + Element Plus）          │
│    智能助手 │ 知识库管理 │ 记忆管理 │ 用户管理 │ 登录      │
└──────────────────────┬───────────────────────────────────┘
                       │ HTTP / SSE
┌──────────────────────┴───────────────────────────────────┐
│                   后端（Spring Boot 4 + Spring AI 2.0）    │
│                                                           │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │ Agent 对话   │  │  Advisor 链   │  │  记忆系统       │  │
│  │ 6 工具注册   │  │  短期+摘要+长期│  │  MySQL+Milvus  │  │
│  └──────┬──────┘  └──────────────┘  └─────────────────┘  │
│         │                                                 │
│  ┌──────┴──────────────────────────────────────────────┐ │
│  │              Function Calling 工具                   │ │
│  ├── RagTool ──→ Milvus 向量检索 + Rerank              │ │
│  ├── GraphTool ──→ HTTP → graph-learning-java (8081)   │ │
│  ├── ResearchTool → HTTP → Python Agent (8000)         │ │
│  ├── UserQueryTool ──→ MySQL                          │ │
│  ├── WeatherTool ──→ 高德 API                         │ │
│  └── TimeTool ──→ 系统时间                            │ │
└───────────────────────────────────────────────────────┘
         │                    │                    │
    ┌────┴────┐         ┌────┴─────┐        ┌────┴─────┐
    │ MySQL   │         │ Milvus   │        │ Redis    │
    │ 8.0    │         │ 2.4     │        │ 7       │
    └─────────┘         └──────────┘        └──────────┘
```

## 技术栈

### 后端

- Spring Boot 4.0 + Java 21
- Spring AI 2.0 + 通义 DashScope（Chat + Embedding + Function Calling）
- Milvus 2.4 向量数据库（RAG 检索 + 用户记忆存储）
- MyBatis + MySQL 8.0
- Sa-Token 认证授权 + Redis 存储
- BCrypt 密码加密（spring-security-crypto）
- Guava RateLimiter 限流
- AOP 日志切面 + Logback 多环境日志

### 前端

- Vue 3 + Vite
- Element Plus UI 组件库
- Markdown 渲染（marked + highlight.js + DOMPurify）
- Web Speech API 语音输入
- SSE 流式对话（原生 fetch + ReadableStream）

### 基础设施

- Docker + Docker Compose 容器化部署
- Milvus 三件套（milvus-standalone + etcd + minio）

## 快速开始

### 前置要求

- Docker + Docker Compose
- Java 21 + Maven（后端本地开发）
- Node.js 24 + npm（前端本地开发）

### 配置 API Key

在项目根目录创建 `application-local.yml`（不提交 git），填入真实 API Key：

```yaml
spring:
  ai:
    openai:
      api-key: sk-你的通义DashScope密钥

amap:
  api-key: 你的高德地图密钥
```

### 一键启动基础设施

```bash
docker compose up -d
```

启动 MySQL + Redis + Milvus 三件套。

### 启动后端

IDEA 中直接运行 `LiwanxingLearningProjectsApplication`，或命令行：

```bash
.\mvnw.cmd spring-boot:run
```

访问 http://localhost:8080

### 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问 http://localhost:5173（热更新）

### 默认账号

| 用户名 | 密码 | 角色 |
|--------|------|------|
| admin | 123456 | 管理员 |
| zhangsan | 123456 | 编辑者 |
| lisi | 123456 | 访客 |

## 项目结构

```
src/main/java/com/liwx/learning/
├── agent/                        # Agent 模块（Function Calling）
│   ├── controller/AgentController.java   # Agent 对话入口（/agent/chat）
│   └── tool/                     # 6 个工具
│       ├── RagTool.java          # 知识库检索
│       ├── TimeTool.java         # 时间查询
│       ├── WeatherTool.java      # 天气查询（高德 API）
│       ├── UserQueryTool.java    # 用户信息查询
│       ├── GraphTool.java        # 经营分析（→ graph-learning-java）
│       └── ResearchTool.java     # 深度调研（→ Python Agent）
├── rag/                          # RAG + 记忆模块
│   ├── advisor/                  # Advisor 链（记忆注入 + 摘要压缩）
│   │   ├── UserMemoryAdvisor.java
│   │   └── ConversationSummaryAdvisor.java
│   ├── service/                  # 业务逻辑（已从 Advisor 解耦）
│   │   ├── RagService.java       # 文档处理 + 向量检索
│   │   ├── RerankService.java    # DashScope Rerank 重排序
│   │   ├── UserMemoryService.java        # 长期记忆（MySQL+Milvus 双写）
│   │   └── ConversationSummaryService.java # 对话摘要
│   ├── controller/
│   │   ├── RagController.java    # 知识库管理 + 会话管理
│   │   └── MemoryController.java # 长期记忆管理（查看/编辑/删除）
│   ├── entity/                   # 实体类
│   └── mapper/                   # MyBatis Mapper
├── user/                         # 用户模块（RBAC）
├── config/                       # AiConfig + SaToken + PasswordConfig
├── common/                       # Result 统一响应 + Assert 断言
├── exception/                    # 全局异常处理
└── aspect/                       # AOP 日志切面

frontend/src/
├── views/
│   ├── RagView.vue              # 智能助手（SSE 流式 + Markdown + 语音输入）
│   ├── MemoryView.vue           # 记忆管理
│   ├── RagDocsView.vue          # 知识库管理
│   ├── UserView.vue             # 用户管理
│   └── LoginView.vue            # 登录
├── layout/MainLayout.vue        # 布局外壳
└── router/index.js              # 路由配置

sql/
├── rbac.sql                     # RBAC 五表 + 初始化数据
├── rag.sql                      # RAG + 记忆表
└── graph.sql                    # 经营分析表（product/customer/orders/order_item）
```

## 跨语言 Agent 协作

本项目作为"主 Agent"，通过 HTTP 调用外部独立 Agent 服务：

| 外部服务 | 语言 | 框架 | 端口 | 功能 |
|---------|------|------|------|------|
| graph-learning-java | Java | Spring AI Alibaba Graph | 8081 | 经营分析工作流 |
| Python Agent | Python | LangGraph | 8000 | 深度调研 |

调用方式统一为 `POST /接口名 + {"query": "..."}`，后端到后端通信，无需 CORS。

## 本地开发

### 混合部署（推荐）

1. Docker Compose 启动 MySQL + Redis + Milvus
2. IDEA 运行后端（断点调试）
3. `npm run dev` 运行前端（热更新）

### 完整容器化部署

```bash
docker compose -f docker-compose.prod.yml up -d
```

- 前端：http://localhost:8081
- 后端：http://localhost:8080

## 截图说明

需要的截图：
- `chat.png` — 智能助手对话（含 Markdown 渲染）
- `memory.png` — 记忆管理页面
- `rag-docs.png` — 知识库管理页面
