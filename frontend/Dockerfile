# 多阶段构建：第一阶段编译前端，第二阶段用 Nginx 托管
#
# 跟后端 Dockerfile 类比：
#   后端：mvn package → jar → JRE 运行
#   前端：npm run build → dist 静态文件 → Nginx 托管

# ==================== 第一阶段：构建 ====================
# 用 Node.js 镜像编译前端代码，产出 dist 静态文件
FROM node:24-alpine AS build
WORKDIR /app

# 先复制 package.json，利用 Docker 缓存加速
# 只要 package.json 不变，npm install 这一层就走缓存
COPY package*.json ./
RUN npm install

# 复制源代码并构建
COPY . .
RUN npm run build

# ==================== 第二阶段：托管 ====================
# 用 Nginx 镜像托管构建产物，最终镜像很小（只有 Nginx + 静态文件）
FROM nginx:alpine

# 把第一阶段的 dist 复制到 Nginx 静态文件目录
COPY --from=build /app/dist /usr/share/nginx/html

# 复制 Nginx 配置（静态托管 + API 代理）
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
