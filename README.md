# liwanxing-learning-projects

基于 Spring Boot 4 + Spring AI 2.0 的智能助手学习项目，涵盖 RAG 知识库、Agent Function Calling、多层记忆、熔断保护等 AI 应用核心能力。

## 功能一览

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

### 三层记忆系统

| 层级 | 实现 | 特点 |
|------|------|------|
| **短期窗口** | Spring AI ChatMemory（MySQL） | 同一会话内记住上下文，窗口自动裁剪 |
| **对话摘要** | ConversationSummaryService | 消息超窗口时自动压缩旧消息为摘要 |
| **长期记忆** | UserMemoryService（MySQL + Milvus 双写） | AI 自动提取用户偏好，跨所有会话生效 |

![记忆管理](docs/images/memory.png)

### 知识库管理

文档上传（PDF/TXT/DOC/MD）→ Tika 解析 → 切分 → 向量化，支持 TOKEN / FIXED_LENGTH / SEMANTIC 三种切分策略，异步处理 + 状态轮询 + 失败重试。

![知识库管理](docs/images/rag-docs.png)

### 工程实践亮点

- **统一异常处理**：`GlobalExceptionHandler` + `BusinessException` + `ResultCode` 枚举，所有接口返回统一 `{code, message, data}` 格式
- **Sa-Token 认证授权**：RBAC 五表模型 + Redis 会话存储 + 注解级权限校验
- **Resilience4j 熔断保护**：外部服务（Python Agent）自动熔断 + 降级响应
- **Guava 用户级限流**：每用户 QPS 限制，Cache 自动清理不活跃限流器
- **AOP 日志切面**：所有 Controller 方法自动记录入参、耗时、异常
- **Langfuse 可观测性**：AI 调用链路追踪（输入/输出/token 用量/延迟）

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
    └────────┘         └──────────┘        └──────────┘
```

## 快速开始

### 前置要求

- Docker + Docker Compose
- Java 21 + Maven
- Node.js 24 + npm

### 1. 配置 API Key

在项目根目录创建 `application-local.yml`（不提交 git）：

```yaml
spring:
  ai:
    openai:
      api-key: sk-你的通义DashScope密钥

amap:
  api-key: 你的高德地图密钥
```

### 2. 启动基础设施

```bash
docker compose up -d
```

启动 MySQL + Redis + Milvus 三件套。

### 3. 启动后端

IDEA 运行 `LiwanxingLearningProjectsApplication`，或：

```bash
.\mvnw.cmd spring-boot:run
```

访问 http://localhost:8080

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问 http://localhost:5173

### 默认账号

| 用户名 | 密码 | 角色 |
|--------|------|------|
| admin | 123456 | 管理员 |
| zhangsan | 123456 | 编辑者 |
| lisi | 123456 | 访客 |

## 技术栈

| 分类 | 技术 |
|------|------|
| 后端 | Spring Boot 4 + Spring AI 2.0 |
| AI 能力 | Function Calling + RAG + Advisor 链 |
| 数据库 | MySQL 8.0 + Milvus 2.4 + Redis 7 |
| 认证授权 | Sa-Token + RBAC |
| 服务保护 | Resilience4j + Guava RateLimiter |
| 可观测性 | Langfuse |
| 前端 | Vue 3 + Element Plus + SSE 流式 |
| 部署 | Docker + Docker Compose |

## 更多

- [工程设计与思考](DESIGN.md) — 项目中的设计决策与踩坑经验
