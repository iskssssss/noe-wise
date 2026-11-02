package com.skillforge.core.capability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/** 编辑草稿的事务内存取；所有语句显式限定归属，RLS 再做数据库边界保护。 */
@Repository
public class SkillDraftRepository {
    private static final Logger LOG = LoggerFactory.getLogger(SkillDraftRepository.class);
    private static final AtomicLong CALLS = new AtomicLong();

    private final SkillDraftMapper mapper;
    private final ObjectMapper json;

    /** 装配当前领域的 Mapper 依赖；不在构造阶段获取连接或开启事务。 */
    public SkillDraftRepository(SkillDraftMapper mapper, ObjectMapper json) {
        LOG.debug("持久化组件装配进入 component={}", "SkillDraftRepository");
        this.mapper = mapper;
        this.json = json;
        LOG.debug("持久化组件装配退出 component={}", "SkillDraftRepository");
    }

    /** 按已授权归属读取指定记录，可在当前事务中持有行锁；无匹配记录返回空值。 */
    public Optional<Stored> find(CapabilityCatalogScope scope, long id, boolean lock) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "find");
        try {
            return Optional.ofNullable(mapper.find(scope.tenantId(), id, lock)).map(this::read);
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "find", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "find", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 读取指定技能的未发布草稿；归属不匹配或不存在时返回空值。 */
    public Optional<Stored> active(CapabilityCatalogScope scope, long definitionId) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "active");
        try {
            return Optional.ofNullable(mapper.active(scope.tenantId(), definitionId)).map(this::read);
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "active", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "active", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 创建和发布统一先锁定义，避免两个请求各自产生一个活动草稿或版本。 */
    public boolean lockDefinition(CapabilityCatalogScope scope, long id) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "lockDefinition");
        try {
            return mapper.lockDefinition(scope.tenantId(), id) != null;
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "lockDefinition", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "lockDefinition", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 创建归属明确的草稿；JSON 编码失败时拒绝写入，数据库约束失败由事务回滚。 */
    public void insert(CapabilityCatalogScope scope, long id, long definitionId, Long baseVersionId,
            SkillDraftContent content) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "insert");
        try {
            mapper.insert(scope.tenantId(), id, definitionId, baseVersionId, encode(content));
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "insert", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "insert", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 按草稿版本条件保存；返回是否更新成功，版本竞争或已发布时返回 false。 */
    public boolean save(CapabilityCatalogScope scope, long id, long revision, SkillDraftContent content) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "save");
        try {
            return mapper.save(scope.tenantId(), id, revision, encode(content)) == 1;
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "save", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "save", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 按草稿版本记录发布结果；竞争失败返回 false，不重复发布。 */
    public boolean published(CapabilityCatalogScope scope, long id, long revision, SkillDraftPublication publication) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "published");
        try {
            return mapper.published(scope.tenantId(), id, revision,
                    publication.versionId(), publication.definitionRevision()) == 1;
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "published", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "published", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 恢复历史内容覆盖同一工作草稿；稳定身份保留，发布回执在修改后失效。 */
    public boolean restore(CapabilityCatalogScope scope, long id, long revision,
            Long baseVersionId, SkillDraftContent content) {
        return mapper.restore(scope.tenantId(), id, revision, baseVersionId, encode(content)) == 1;
    }

    /** 将持久化草稿转换为领域视图；非法 JSON 抛出 IllegalStateException，不降级为空草稿。 */
    private Stored read(SkillDraftMapper.Row row) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "read");
        try {
            try {
                var content = json.readValue(row.content(), SkillDraftContent.class);
                var view = SkillDraftView.of(row.id(), row.definitionId(), row.baseVersionId(), row.revision(),
                        content, row.publishedVersionId(), row.updatedAt(), null);
                return new Stored(view, row.publishedDefinitionRevision());
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("skill draft content cannot be decoded", exception);
            }
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "read", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "read", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 将草稿内容编码为 JSON；不可编码时抛出 IllegalArgumentException。 */
    private String encode(SkillDraftContent content) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "encode");
        try {
            try {
                return json.writeValueAsString(content);
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("skill draft content cannot be encoded", exception);
            }
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "encode", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "encode", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    public record Stored(SkillDraftView view, Long publishedDefinitionRevision) { }
}
