# liwanxing-learning-projects

李万兴的 Spring Boot 后端学习项目。

## 技术栈

- Spring Boot 4.0.7 + Java 21
- MyBatis + MySQL 8.0
- Sa-Token 认证授权 + Redis 存储
- AOP 日志切面 + Logback 多环境日志
- Docker + Docker Compose 容器化部署

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
