# syntax=docker/dockerfile:1
# 前端镜像构建（frontend-platform）。构建上下文必须为仓库根目录：
#   docker build -f frontend-platform/Dockerfile -t skillforge-platform .

FROM node:22.18-alpine AS build
ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata
WORKDIR /app

# 先复制根 workspace 与所有成员 manifest，使依赖安装可复用缓存层。
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY frontend-admin/package.json ./frontend-admin/package.json
COPY frontend-platform/package.json ./frontend-platform/package.json
COPY frontend-chat/package.json ./frontend-chat/package.json
COPY frontend-shared/package.json ./frontend-shared/package.json
RUN corepack enable && pnpm install --frozen-lockfile

# 只复制构建所需的前端源码和工作区依赖，避免把仓库其他内容或本地密钥带入镜像上下文。
COPY frontend-platform/index.html frontend-platform/tsconfig.app.json frontend-platform/tsconfig.json frontend-platform/tsconfig.node.json frontend-platform/vite.config.ts ./frontend-platform/
COPY frontend-platform/public/ ./frontend-platform/public/
COPY frontend-platform/src/ ./frontend-platform/src/
COPY frontend-shared/src/ ./frontend-shared/src/
COPY tools/frontend/ ./tools/frontend/
RUN pnpm --filter frontend-platform build

FROM nginx:1.27-alpine AS runtime
ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata
COPY frontend-platform/nginx.conf /etc/nginx/nginx.conf
COPY --from=build /app/frontend-platform/dist /usr/share/nginx/html

EXPOSE 8080
USER nginx
CMD ["nginx", "-g", "daemon off;"]
