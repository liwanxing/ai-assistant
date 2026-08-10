# frontend

李万兴的前端学习项目（Vue 3 + Vite），配合后端 Spring Boot 使用。

## 技术栈

- Vue 3 + Vite 8
- Element Plus（UI 组件库，国内最常用的 Vue 3 组件库）
- Axios（HTTP 请求工具，前端调后端 API 的标准选择）
- Vue Router（前端路由，做多页面跳转）

## 创建步骤（从零搭建脚手架）

```bash
# 1. 切到后端项目根目录（frontend 会在根目录下生成）
#    命令在哪个目录执行，就在哪个目录下面生成 frontend 文件夹
cd D:\Code\liwanxing-learning-projects

# 2. 用 Vite 官方模板生成 Vue 3 项目
#    --template vue 指定使用 Vue 模板，跳过交互式选择
npm create vite@latest frontend -- --template vue

# 3. 进入项目目录，安装基础依赖
cd frontend
npm install

# 4. 安装项目需要的额外依赖
#    element-plus: UI 组件库（表格、表单、按钮等）
#    axios: HTTP 请求库（调用后端 API）
#    vue-router: 前端路由（页面跳转）
npm install element-plus axios vue-router
```

## 开发命令

```bash
# 启动开发服务器（热更新，改代码自动刷新）
# 启动后访问 http://localhost:5173
npm run dev

# 打包生产版本（输出到 dist/ 目录，是纯静态文件）
npm run build

# 预览生产版本（本地跑一下 build 后的产物，确认效果）
npm run preview
```

## 目录结构

```
frontend/
├── index.html              # 网页入口（浏览器打开的第一个文件）
├── package.json            # 依赖配置（类似后端的 pom.xml）
├── vite.config.js          # Vite 构建配置 + 代理配置
├── .gitignore              # 忽略 node_modules、dist 等
├── public/                 # 静态资源（不经过编译，直接复制到产物）
│   ├── favicon.svg
│   └── icons.svg
└── src/                    # 前端源码
    ├── main.js             # 前端启动入口（类似后端的 LearningApplication）
    ├── App.vue             # 根组件（所有页面都挂在这下面）
    ├── style.css           # 全局样式
    ├── assets/             # 图片等资源（会被 Vite 编译处理）
    └── components/          # 组件目录
        └── HelloWorld.vue  # 示例组件（后续替换）
```

## 与后端配合

### 开发时

- 前端跑在 `http://localhost:5173`（Vite 开发服务器）
- 后端跑在 `http://localhost:8080`（Spring Boot）
- 前端请求 `/api` 开头的地址时，Vite 会自动代理转发到后端 8080 端口
- 代理配置在 `vite.config.js` 的 `server.proxy` 中：
  - 前端请求 `/api/user/list` → 后端收到 `/user/list`
  - `rewrite` 去掉 `/api` 前缀，后端不需要感知前端用了代理

### 部署时（Docker + Nginx）

- **Nginx 容器托管**：`npm run build` 生成静态文件，Nginx 容器托管 `dist/`
- **Nginx 反向代理**：`/api` 请求转发到 `app:8080/`（自动去掉 `/api` 前缀）
- **Docker Compose 统一编排**：前端服务在 `docker-compose.yml` 中定义，与后端、数据库统一管理

详细部署流程见项目根目录 [README.md](../README.md#前端容器化部署)。

## 概念说明

> **package.json** = 类似后端的 pom.xml，声明项目依赖和脚本命令
> **node_modules** = 依赖包的存放目录（类似 Maven 的本地仓库），不提交到 Git
> **npm install** = 根据 package.json 下载依赖到 node_modules
> **npm run dev** = 启动开发服务器，支持热更新（改代码自动刷新页面）
> **npm run build** = 编译打包成静态文件，部署时用
> **.vue 文件** = Vue 单文件组件，一个文件包含 HTML + CSS + JavaScript
