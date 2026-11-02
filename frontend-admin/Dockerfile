# syntax=docker/dockerfile:1
# 前端镜像构建（frontend-admin）。构建上下文必须为仓库根目录：
#   docker build -f frontend-admin/Dockerfile -t skillforge-admin .

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
COPY frontend-admin/index.html frontend-admin/tsconfig.app.json frontend-admin/tsconfig.json frontend-admin/tsconfig.node.json frontend-admin/vite.config.ts ./frontend-admin/
COPY frontend-admin/public/ ./frontend-admin/public/
COPY frontend-admin/src/ ./frontend-admin/src/
COPY frontend-shared/src/ ./frontend-shared/src/
COPY tools/frontend/ ./tools/frontend/
ARG VITE_ARTIFACT_PREVIEW_ORIGIN=""
ENV VITE_ARTIFACT_PREVIEW_ORIGIN=${VITE_ARTIFACT_PREVIEW_ORIGIN}
RUN pnpm --filter frontend-admin build

FROM nginx:1.27-alpine AS runtime
ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata
COPY frontend-admin/nginx.conf /etc/nginx/nginx.conf
COPY --from=build /app/frontend-admin/dist /usr/share/nginx/html

EXPOSE 8080
USER nginx
CMD ["nginx", "-g", "daemon off;"]
