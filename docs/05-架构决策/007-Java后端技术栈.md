# ADR-007：Java 后端技术栈（Spring Cloud Alibaba）

> **状态**：Accepted · **日期**：2026-08-05 · **Owner**：架构

本文保留 Java 技术栈决策；依赖版本以 `backend-java/gradle/libs.versions.toml` 和各模块构建文件为准。运行时行为、任务调度和外部调用以当前源码与 [Harness Design](../../harness-design/README.md) 为准，不以历史迁移描述推断已实现能力。

## 背景

现有后端为 Python/FastAPI。项目决定将后端重写为 Java，构建工具使用 Gradle，
并采用 Spring Cloud Alibaba 微服务拓扑（网关 + 业务服务 + Nacos）。

## 决策

- 语言与构建：Java 21（LTS）、Gradle 8.14.3（Kotlin DSL）、多模块工程。
- 基础框架：Spring Boot 3.5.16 + Spring WebMVC。
- 微服务：Spring Cloud 2025.0.3 + Spring Cloud Alibaba 2025.0.0.0；
  拓扑为 Spring Cloud Gateway（8080）→ 业务服务（8000），Nacos 注册/配置中心可选。
- 数据访问：MyBatis-Plus 3.5.17 + PostgreSQL 16（pgvector），Flyway 管理迁移。
- 缓存与调度：Redis 7（Spring Data Redis）；定时与异步任务按所属服务的调度、持久状态及恢复实现执行，不统一假定使用 Redis Streams 或 Quartz。
- 鉴权：Sa-Token 1.45.0 + JWT + RBAC；租户 RLS 由 MyBatis-Plus 租户拦截器 +
  事务 `set_config('app.tenant_id', ...)` 实现。
- AI：Agent Runtime model adapter 按提供方协议访问配置端点；OpenAI 兼容 Chat Completions 与 Responses 适配使用 OpenAI Java SDK，具体可用能力以[模型适配器](../../backend-java/agent-runtime-adapter-model/src/main/java/com/skillforge/runtime/adapter/model/)及配置为准。
- 实时：SSE 用 `SseEmitter`，坐席 WebSocket 用 Spring WebSocket，网关透传。
- 可观测：Micrometer + Prometheus + OTel OTLP + Logback JSON；springdoc-openapi。

## 影响

- 新增 `backend-java/`；Python 后端已于 2026-08-05 删除（双后端并存阶段结束，
  仓库仅保留 Java 体系）。
- 前端拆分为 frontend-admin / frontend-platform；接口与数据模型服从当前设计，不以保留 Python 时代契约为约束。
- 迁移工具由 Alembic 切换为 Flyway，以当前 schema 生成 `V1__baseline.sql`。
- 后台任务由各所属服务的调度与持久化机制承接；工作流与智能体执行由 Workflow/Agent Runtime +
  项目自有 Provider 与 model adapter 替代（见 [ADR-013](013-工作流节点执行器模式.md)）。
