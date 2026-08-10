# liwanxing-learning-projects

李万兴的 Spring Boot 后端学习项目。

## 技术栈

- Spring Boot 4.0.7 + Java 21
- MyBatis + MySQL 8.0
- Sa-Token 认证授权 + Redis 存储
- AOP 日志切面 + Logback 多环境日志
- Docker + Docker Compose 容器化部署

## 快速开始

### 前置要求

- Docker + Docker Compose
- （可选）Java 21 + Maven（本地开发用）
- （可选）Node.js 24 + npm（前端本地开发用）

### 一键启动（推荐）

```bash
# 启动全部服务（app + mysql + redis + frontend）
docker compose up -d
```

启动后访问：
- 前端：http://localhost:8081
- 后端：http://localhost:8080

### 停止服务

```bash
# 停止并删除所有容器
docker compose down

# 停止并删除所有容器 + 数据卷（会清空 MySQL 数据）
docker compose down -v
```

### 查看日志

```bash
# 查看所有服务日志
docker compose logs -f

# 查看指定服务日志
docker compose logs -f app
```

## 项目结构

```
src/main/java/com/liwx/learning/
├── config/          # Sa-Token 路由拦截配置
├── controller/       # 控制器（Hello、Auth、User）
├── service/          # 业务逻辑层
├── mapper/           # MyBatis 数据访问层
├── entity/           # 实体类
├── dto/              # 数据传输对象（请求/响应 DTO）
├── aspect/           # AOP 日志切面
├── exception/        # 全局异常处理
└── LearningApplication.java  # 启动类

src/main/resources/
├── application.yml   # 配置文件（含 dev/prod profile）
├── logback-spring.xml    # 日志配置（多环境）
└── mapper/           # MyBatis XML 映射文件

sql/
└── rbac.sql          # 建表 + 初始化数据脚本

Dockerfile            # 应用镜像构建文件
docker-compose.yml    # 一键部署编排文件
```

## 本地开发

### 方式一：IDEA 直接运行

1. 用 Docker Compose 启动 MySQL + Redis：
   ```
   docker compose up -d mysql redis
   # -d 表示后台运行；后面跟服务名表示只启动指定服务，不跟则启动全部
   ```
2. 在 IDEA 中直接运行 `LearningApplication`

### 方式二：Docker Compose 全容器化运行

```
docker compose up -d   # 不跟服务名 = 启动全部服务（app + mysql + redis）
```

## 应用更新流程（改代码后重新部署）

修改 Java 代码后，只需重新构建并更新 APP 镜像，不影响 MySQL 和 Redis：

```
# 1. Maven 打包：把 .java 源码编译成 jar 包
#    方式A：本地已安装 Maven（配了环境变量）
mvn clean package -DskipTests
#    方式B：本地没装 Maven，用项目自带的 Maven Wrapper（即开即用）
# .\mvnw.cmd clean package -DskipTests

# 2. Docker 构建：把 jar 包 + JRE 运行环境 打包成 Docker 镜像
#    -t 指定镜像名，必须和 docker-compose.yml 里 app 服务的 image 名一致
docker build --pull=false -t liwanxing-learning-projects-app .

# 3. Compose 部署：用新镜像启动 app 容器
#    -d 后台运行；不跟服务名表示启动全部服务
#    镜像名 liwanxing-learning-projects-app:latest 与 docker-compose.yml 中 app.image 一致
#    Compose 在本地找到第2步构建的镜像；mysql/redis 已在运行则不受影响，只重建 app
docker compose up -d
```

三步产出对比：

| 步骤 | 命令 | 输入 | 输出 |
|---|---|---|---|
| Maven 打包 | `mvn clean package` | .java 源码 | target/*.jar |
| Docker 构建 | `docker build` | jar 包 | Docker 镜像（image） |
| Compose 部署 | `docker compose up` | 镜像 | 运行中的容器（container） |

> jar 包 = 编译后的 Java 代码
> Docker 镜像 = jar 包 + JRE + 操作系统层，完整的运行环境快照
> 容器 = 镜像运行起来的实例

## IDEA 数据库连接

连接 Docker 中的 MySQL 需要配置以下参数：

- Host: `localhost`
- Port: `3306`
- Database: `liwx_learning`
- User: `root`
- Password: `123456`
- Advanced 选项卡中设置 `allowPublicKeyRetrieval = true`（MySQL 8.0 认证需要）

## 前端容器化部署

前端使用多阶段构建 Docker 镜像，内部执行 `npm run build` 后用 Nginx 托管静态文件。

### 核心文件说明

- `Dockerfile`：多阶段构建配置
  - 第一阶段：用 Node.js 镜像编译前端，产出 `dist/` 静态文件
  - 第二阶段：用 Nginx 镜像托管 `dist/`，最终镜像很小（只有 Nginx + 静态文件）

- `nginx.conf`：Nginx 配置
  - 静态托管：前端页面直接返回静态文件
  - API 代理：`/api` 开头请求转发到 `app:8080/`（去掉 `/api` 前缀）
  - Vue Router 支持：找不到的路径回退到 `index.html`（刷新页面不 404）

- `.dockerignore`：构建时忽略 `node_modules`、`dist`、`*.md` 等

### 部署流程（从零部署）

```bash
# 一键启动全部服务（app + mysql + redis + frontend）
docker compose up -d
```

访问地址：
- 前端：http://localhost:8081
- 后端：http://localhost:8080
- MySQL：localhost:3306
- Redis：localhost:6379

### 前端应用更新流程（改代码后重新部署）

修改前端代码后，只需重新构建并更新 frontend 镜像，不影响其他服务：

```bash
# 1. Docker 构建：在 frontend 目录执行，把前端代码打包成 Docker 镜像
#    -t 指定镜像名，必须和 docker-compose.yml 里 frontend 服务的 image 名一致
cd frontend
docker build --pull=false -t liwanxing-learning-projects-frontend .
cd ..

# 2. Compose 部署：用新镜像启动 frontend 容器
#    镜像名 liwanxing-learning-projects-frontend:latest 与 docker-compose.yml 中 frontend.image 一致
#    Compose 在本地找到第1步构建的镜像；其他服务已在运行则不受影响，只重建 frontend
docker compose up -d frontend
```

### 前后端开发与部署对比

| 场景 | 前端运行方式 | API 代理配置 | 访问地址 |
|------|------------|------------|---------|
| 本地开发 | `npm run dev`（Vite 开发服务器） | Vite 代理（vite.config.js） | http://localhost:5173 |
| 生产部署 | Nginx 容器托管静态文件 | Nginx 反向代理（nginx.conf） | http://localhost:8081 |

开发时前端请求 `/api/user/list`：
- **Vite 代理**：前端 → Vite（5173）→ 重写为 `/user/list` → 后端（8080）
- **Nginx 代理**：前端 → Nginx（80）→ 转发到 `app:8080/`（自动去掉 `/api`）→ 后端容器（8080）

两者效果一致，后端都不需要感知前端的 `/api` 前缀。

## 完整部署架构

```
浏览器
  ↓ 8081 端口
frontend 容器（Nginx + 静态文件）
  ↓ /api 开头请求
app 容器（Spring Boot 应用）
  ↓ 读写数据库
mysql 容器（MySQL 8.0）
  ↓ 存储 Token
redis 容器（Redis 7）
```

所有服务通过 Docker Compose 一键编排，容器间通过服务名互相访问（如 `app`、`mysql`、`redis`），不用关心容器内部 IP。

## 常见问题

### 前端页面空白或 404

**原因**：Vue Router history 模式刷新页面时，Nginx 找不到对应路由文件

**解决**：确保 `nginx.conf` 配置了 `try_files $uri $uri/ /index.html;`

### 前端 API 请求失败

**检查项**：
1. 前端请求路径是否以 `/api` 开头
2. `nginx.conf` 中 `proxy_pass http://app:8080/;` 末尾是否有 `/`（去掉 `/api` 前缀）
3. 后端服务是否正常启动（`docker compose ps` 查看状态）
4. 后端 CORS 配置是否允许跨域（容器内不需要，但本地开发需要）

### MySQL 连接失败

**错误信息**：`Unknown database 'liwx_learning'`

**原因**：Docker MySQL 容器初始化需要时间，应用可能在数据库就绪前就启动了

**解决**：
1. 等待 10-30 秒让 MySQL 初始化完成
2. 或修改 `docker-compose.yml`，给 app 服务添加健康检查（healthcheck）

**错误信息**：`Access denied for user 'root'@'localhost'`

**解决**：检查 `docker-compose.yml` 中 `MYSQL_ROOT_PASSWORD` 和 `SPRING_DATASOURCE_PASSWORD` 是否一致

### Redis 连接失败

**检查项**：
1. `docker compose ps` 确认 redis 容器运行正常
2. `docker compose logs redis` 查看 Redis 日志
3. 检查密码配置：`SPRING_DATA_REDIS_PASSWORD` 和 `redis` 服务的 `command: --requirepass` 是否一致

### 容器一直重启

**查看日志**：
```bash
docker compose logs app
```

**常见原因**：
- MySQL/Redis 未就绪，应用启动失败
- 端口冲突（8080、3306、6379 被占用）
- 配置错误（如数据库密码不匹配）

### 修改代码后容器没更新

**原因**：Docker 镜像没重新构建

**解决**：
```bash
# 后端
mvn clean package -DskipTests
docker build --pull=false -t liwanxing-learning-projects-app .
docker compose up -d app

# 前端
cd frontend
docker build --pull=false -t liwanxing-learning-projects-frontend .
cd ..
docker compose up -d frontend
```

### 如何清空数据库重新开始

```bash
# 停止并删除所有容器 + 数据卷（会清空 MySQL 数据）
docker compose down -v

# 重新启动（会重新初始化数据库）
docker compose up -d
```

> 注意：`-v` 参数会删除所有数据卷，包括 MySQL 数据，请谨慎使用
