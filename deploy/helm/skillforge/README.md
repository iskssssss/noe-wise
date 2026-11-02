# 灵知 Helm chart

> 当前 chart 是预发布部署骨架，只包含一个 backend 和一个 frontend 工作负载。
> 它尚未表达 Java 多服务拓扑，也不是 Agent Runtime MVP-0 已可生产发布的证据。

## 范围

chart 当前负责：

- backend 与 frontend Deployment/Service；
- 可选 Ingress；
- 应用配置与 Secret 引用；
- live、ready 和 metrics 路由。

PostgreSQL、Redis 和 Elasticsearch 由 chart 外部提供。配置键以
[values.yaml](values.yaml) 与模板为准；README 不维护第二套变量清单。

## 使用前检查

1. 确认待发布镜像与 chart 的单 backend 拓扑匹配。
2. 若部署 Java gateway、institution-service 和 platform-service，先扩展 chart 并通过模板与目标环境验证，不能把它们压缩为未验证的单 backend 镜像。
3. 使用外部 Secret，禁止把生产凭证写入 values。
4. 验证数据库迁移、健康检查、Ingress 路由和网络策略。
5. 涉及 Agent Runtime 时执行 [发布检查清单](../../runbook/checklist-release.md)。

## 渲染验证

```bash
helm lint ./deploy/helm/skillforge
helm template skillforge ./deploy/helm/skillforge \
  --set secrets.existingSecret=skillforge-runtime
```

`helm template` 只证明模板可渲染，不证明镜像、迁移、依赖或 Runtime 验收通过。

## 路由

frontend 容器监听 8080，Service 暴露 80。启用 Ingress 时，`/api`、`/health` 和 `/metrics` 路由到 backend，`/` 路由到 frontend。

`/metrics` 只能对受控采集网络开放。生产环境必须配置 TLS、Ingress Controller、Secret 管理、资源限制和网络策略。
