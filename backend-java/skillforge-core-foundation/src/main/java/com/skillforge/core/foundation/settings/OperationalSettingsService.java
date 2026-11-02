package com.skillforge.core.foundation.settings;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.application.CryptoService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台运营配置入口。
 *
 * <p>每次变更生成不可变版本，通过当前指针的 compare-and-set 实现乐观并发。
 * 调用方在新动作边界读取 {@link Snapshot}，同一次调用及恢复保留该快照。</p>
 */
public class OperationalSettingsService implements SmartInitializingSingleton {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OperationalSettingsService.class);
    private static final String ENCRYPTED_VALUE = "$encrypted";

    private final OperationalSettingsMapper mapper;
    private final CryptoService crypto;
    private final OperationalSettingsRegistry registry;

    public OperationalSettingsService(OperationalSettingsMapper mapper, CryptoService crypto, OperationalSettingsRegistry registry) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * 在宿主接收请求前建立缺省版本；运行配置捕获只能读取已发布版本。
     *
     * <p>初始化使用独立事务，多个宿主同时启动时由唯一键与 ON CONFLICT 协调。
     * 新 Run 的 REPEATABLE_READ 事务不再竞争写入全平台相同的缺省配置行。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void afterSingletonsInstantiated() {
        for (var definition : registry.groups()) {
            if (mapper.selectCurrent(definition.key()) == null) {
                mapper.insertVersion(definition.key(), 1, encode(definition, definition.defaults()), null);
                mapper.insertHead(definition.key(), 1, null);
            }
            current(definition);
        }
    }

    /** 读取供消费者冻结的当前强类型快照。 */
    @Transactional
    public Snapshot read(String group) {
        Resolved current = current(requireGroup(group));
        return new Snapshot(current.row().getRevision(), current.plainValues());
    }

    /** 按精确版本读取不可变快照；已冻结任务不依赖当前指针或当前版本仍然可读。 */
    @Transactional
    public Snapshot readRevision(String group, long revision) {
        if (revision <= 0) {
            throw invalid("revision 必须是正整数");
        }
        OperationalSettingsRegistry.GroupDefinition definition = requireGroup(group);
        OperationalSettingVersionRow row = mapper.selectVersion(group, revision);
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "运营配置版本不存在");
        }
        rejectRetiredContract(definition, row.getValues());
        return new Snapshot(row.getRevision(), validatedPlain(definition, row.getValues()));
    }

    /** 查询一个配置分组的管理视图。 */
    @Transactional
    public GroupView get(String group) {
        OperationalSettingsRegistry.GroupDefinition definition = requireGroup(group);
        return view(definition, current(definition));
    }

    /** 按注册顺序列出全部配置分组。 */
    @Transactional
    public List<GroupView> list() {
        var definitions = registry.groups();
        if (definitions.isEmpty()) return List.of();
        Map<String, OperationalSettingHeadRow> heads = new LinkedHashMap<>();
        for (var head : mapper.selectCurrents(definitions.stream().map(OperationalSettingsRegistry.GroupDefinition::key).toList()))
            heads.put(head.getGroupKey(), head);
        var captured = heads.values().stream().filter(head -> head.getCurrentRevision() > 0).toList();
        Map<String, OperationalSettingVersionRow> versions = new LinkedHashMap<>();
        if (!captured.isEmpty()) {
            for (var row : mapper.selectVersions(captured)) versions.put(row.getGroupKey(), row);
        }
        List<GroupView> views = new ArrayList<>();
        for (var definition : definitions) {
            var head = heads.get(definition.key());
            if (head == null || head.getCurrentRevision() <= 0)
                throw new IllegalStateException("运营配置当前版本不存在: " + definition.key());
            var row = versions.get(definition.key());
            if (row == null) throw new IllegalStateException("运营配置当前版本快照不存在: " + definition.key());
            views.add(view(definition, new Resolved(row, validatedPlain(definition, row.getValues()))));
        }
        return List.copyOf(views);
    }

    /** 校验并以乐观并发方式创建新版本。 */
    @Transactional
    public GroupView update(
            String group, long expectedRevision, Map<String, Object> values, long actorId) {
        requireActor(actorId);
        OperationalSettingsRegistry.GroupDefinition definition = requireGroup(group);
        Resolved current = current(definition);
        requireExpected(group, expectedRevision, current.row().getRevision());
        Map<String, Object> normalized;
        try {
            normalized = OperationalSettingsRegistry.normalize(
                    definition, values, current.plainValues());
        } catch (OperationalSettingsRegistry.InvalidSettingsException exception) {
            throw invalid(exception.getMessage());
        }
        long newRevision = Math.addExact(expectedRevision, 1);
        if (mapper.compareAndSetHead(group, expectedRevision, newRevision, actorId) != 1) {
            throw conflict(group, expectedRevision);
        }
        Map<String, Object> encoded = encode(definition, normalized);
        if (mapper.insertVersion(group, newRevision, encoded, actorId) != 1) {
            throw new IllegalStateException("运营配置版本写入失败");
        }
        if (mapper.insertAudit(group, "update", expectedRevision, newRevision, null, actorId,
                Map.of("changed_fields", changedFields(current.plainValues(), normalized))) != 1) {
            throw new IllegalStateException("运营配置审计写入失败");
        }
        OperationalSettingVersionRow row = mapper.selectVersion(group, newRevision);
        if (row == null) {
            throw new IllegalStateException("运营配置新版本不可见");
        }
        return view(definition, new Resolved(row, normalized));
    }

    /** 按版本倒序查询不可变历史；敏感字段始终不回显。 */
    @Transactional
    public List<VersionView> history(String group) {
        OperationalSettingsRegistry.GroupDefinition definition = requireGroup(group);
        current(definition);
        return mapper.selectHistory(group).stream()
                .map(row -> historicalView(definition, row))
                .toList();
    }

    /** 退休配置契约仅展示版本元数据，不把旧字段过滤成当前有效配置。 */
    private VersionView historicalView(OperationalSettingsRegistry.GroupDefinition definition,
            OperationalSettingVersionRow row) {
        if (isRetiredContract(definition, row.getValues())) {
            return new VersionView(row.getRevision(), row.getCreatedAt(), actor(row.getActorId()),
                    Map.of(), VersionContractStatus.RETIRED);
        }
        return new VersionView(row.getRevision(), row.getCreatedAt(), actor(row.getActorId()),
                publicValues(definition, decode(definition, row.getValues())), VersionContractStatus.SUPPORTED);
    }

    private boolean isRetiredContract(OperationalSettingsRegistry.GroupDefinition definition,
            Map<String, Object> stored) {
        return stored != null && definition.retired().test(stored);
    }

    private void rejectRetiredContract(OperationalSettingsRegistry.GroupDefinition definition,
            Map<String, Object> stored) {
        if (isRetiredContract(definition, stored)) {
            throw new ApiException(HttpStatus.CONFLICT, definition.key() + "_settings_revision_retired",
                    "该修订的配置格式已停用，无法读取或恢复；版本记录仍保留。", false);
        }
    }

    /** 将历史快照复制为一个新版本，保留原历史不变。 */
    @Transactional
    public GroupView rollback(
            String group, long expectedRevision, long targetRevision, long actorId) {
        requireActor(actorId);
        OperationalSettingsRegistry.GroupDefinition definition = requireGroup(group);
        Resolved current = current(definition);
        requireExpected(group, expectedRevision, current.row().getRevision());
        if (targetRevision <= 0) {
            throw invalid("target_revision 必须是正整数");
        }
        OperationalSettingVersionRow target = mapper.selectVersion(group, targetRevision);
        if (target == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "目标运营配置版本不存在");
        }
        rejectRetiredContract(definition, target.getValues());
        Map<String, Object> plainTarget = validatedPlain(definition, target.getValues());
        long newRevision = Math.addExact(expectedRevision, 1);
        if (mapper.compareAndSetHead(group, expectedRevision, newRevision, actorId) != 1) {
            throw conflict(group, expectedRevision);
        }
        if (mapper.insertVersion(group, newRevision, target.getValues(), actorId) != 1) {
            throw new IllegalStateException("运营配置回滚版本写入失败");
        }
        if (mapper.insertAudit(group, "rollback", expectedRevision, newRevision,
                targetRevision, actorId, Map.of("target_revision", targetRevision)) != 1) {
            throw new IllegalStateException("运营配置回滚审计写入失败");
        }
        OperationalSettingVersionRow row = mapper.selectVersion(group, newRevision);
        if (row == null) {
            throw new IllegalStateException("运营配置回滚版本不可见");
        }
        return view(definition, new Resolved(row, plainTarget));
    }

    private OperationalSettingsRegistry.GroupDefinition requireGroup(String group) {
        try {
            return registry.require(group);
        } catch (OperationalSettingsRegistry.UnknownGroupException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    private Resolved current(OperationalSettingsRegistry.GroupDefinition definition) {
        OperationalSettingHeadRow head = mapper.selectCurrent(definition.key());
        if (head == null || head.getCurrentRevision() <= 0) {
            throw new IllegalStateException("运营配置当前版本不存在: " + definition.key());
        }
        OperationalSettingVersionRow row = mapper.selectVersion(
                definition.key(), head.getCurrentRevision());
        if (row == null) {
            throw new IllegalStateException("运营配置当前版本快照不存在: " + definition.key());
        }
        return new Resolved(row, validatedPlain(definition, row.getValues()));
    }

    private Map<String, Object> validatedPlain(
            OperationalSettingsRegistry.GroupDefinition definition, Map<String, Object> stored) {
        Map<String, Object> decoded = decode(definition, stored);
        try {
            return OperationalSettingsRegistry.normalize(definition, decoded, null);
        } catch (OperationalSettingsRegistry.InvalidSettingsException exception) {
            throw new IllegalStateException(
                    "运营配置持久化快照不符合注册定义: " + definition.key(), exception);
        }
    }

    private Map<String, Object> encode(
            OperationalSettingsRegistry.GroupDefinition definition, Map<String, Object> plain) {
        LinkedHashMap<String, Object> encoded = new LinkedHashMap<>();
        definition.fields().values().forEach(field -> {
            Object value = plain.get(field.key());
            encoded.put(field.key(), field.secret()
                    ? Map.of(ENCRYPTED_VALUE, crypto.encrypt((String) value)) : value);
        });
        return Map.copyOf(encoded);
    }

    private Map<String, Object> decode(
            OperationalSettingsRegistry.GroupDefinition definition, Map<String, Object> stored) {
        if (stored == null) {
            throw new IllegalStateException("运营配置版本 values 为空");
        }
        if (!stored.keySet().equals(definition.fields().keySet()))
            throw new IllegalStateException("运营配置快照字段不符合当前契约: " + definition.key());
        LinkedHashMap<String, Object> plain = new LinkedHashMap<>();
        definition.fields().values().forEach(field -> {
            Object value = stored.get(field.key());
            if (!field.secret()) {
                plain.put(field.key(), value);
                return;
            }
            if (!(value instanceof Map<?, ?> encrypted)
                    || !(encrypted.get(ENCRYPTED_VALUE) instanceof String ciphertext)) {
                throw new IllegalStateException("敏感运营配置未加密: " + field.key());
            }
            plain.put(field.key(), crypto.decrypt(ciphertext));
        });
        return Map.copyOf(plain);
    }

    private GroupView view(
            OperationalSettingsRegistry.GroupDefinition definition, Resolved resolved) {
        List<Field> fields = definition.fields().values().stream()
                .map(field -> new Field(field.key(), field.label(), field.type(), field.unit(),
                        field.minimum(), field.maximum(), field.choices(), field.secret()))
                .toList();
        return new GroupView(definition.key(), definition.label(), resolved.row().getRevision(),
                publicValues(definition, resolved.plainValues()), fields, definition.derived().apply(new Snapshot(resolved.row().getRevision(), resolved.plainValues())));
    }

    private Map<String, Object> publicValues(
            OperationalSettingsRegistry.GroupDefinition definition, Map<String, Object> plain) {
        LinkedHashMap<String, Object> visible = new LinkedHashMap<>();
        definition.fields().values().stream().filter(field -> !field.secret())
                .forEach(field -> visible.put(field.key(), plain.get(field.key())));
        return Map.copyOf(visible);
    }

    private static List<String> changedFields(
            Map<String, Object> previous, Map<String, Object> current) {
        Set<String> changed = new LinkedHashSet<>();
        current.forEach((key, value) -> {
            if (!Objects.equals(previous.get(key), value)) {
                changed.add(key);
            }
        });
        return List.copyOf(changed);
    }

    private static void requireExpected(String group, long expected, long actual) {
        if (expected <= 0) {
            throw invalid("expected_revision 必须是正整数");
        }
        if (expected != actual) {
            throw conflict(group, expected, actual);
        }
    }

    private static void requireActor(long actorId) {
        if (actorId <= 0) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "平台操作者身份无效");
        }
    }

    private static String actor(Long actorId) {
        return actorId == null ? null : actorId.toString();
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private static ApiException conflict(String group, long expected) {
        return new ApiException(HttpStatus.CONFLICT,
                "运营配置版本冲突，请刷新后重试: group=" + group
                        + ", expected_revision=" + expected);
    }

    private static ApiException conflict(String group, long expected, long actual) {
        return new ApiException(HttpStatus.CONFLICT,
                "运营配置版本冲突，请刷新后重试: group=" + group
                        + ", expected_revision=" + expected + ", actual_revision=" + actual);
    }

    private record Resolved(OperationalSettingVersionRow row, Map<String, Object> plainValues) {
        private Resolved {
            plainValues = Map.copyOf(plainValues);
        }
    }

    /** 任务受理时冻结的不可变配置快照。 */
    public record Snapshot(long revision, Map<String, Object> values) {
        public Snapshot {
            if (revision <= 0) {
                throw new IllegalArgumentException("revision 必须是正整数");
            }
            values = Map.copyOf(values);
        }

        public int integer(String key) {
            try {
                return number(key).intValueExact();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("配置值不是 int: " + key, exception);
            }
        }

        public long longValue(String key) {
            try {
                return number(key).longValueExact();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("配置值不是 long: " + key, exception);
            }
        }

        public BigDecimal decimal(String key) {
            return number(key);
        }

        public boolean bool(String key) {
            Object value = required(key);
            if (!(value instanceof Boolean result)) {
                throw new IllegalArgumentException("配置值不是 boolean: " + key);
            }
            return result;
        }

        public String text(String key) {
            Object value = required(key);
            if (!(value instanceof String result)) {
                throw new IllegalArgumentException("配置值不是 string: " + key);
            }
            return result;
        }

        private BigDecimal number(String key) {
            Object value = required(key);
            if (!(value instanceof Number)) {
                throw new IllegalArgumentException("配置值不是 number: " + key);
            }
            return new BigDecimal(value.toString());
        }

        private Object required(String key) {
            if (!values.containsKey(key)) {
                throw new IllegalArgumentException("配置字段不存在: " + key);
            }
            return values.get(key);
        }
    }

    /** 管理端分组视图。 */
    public record GroupView(
            String key, String label, long revision, Map<String, Object> values, List<Field> fields, List<DerivedBudget> derivedBudgets) {
        public GroupView {
            values = Map.copyOf(values);
            fields = List.copyOf(fields);
            derivedBudgets = List.copyOf(derivedBudgets);
        }
    }

    /** 只读角色额度，累计 Token 额度表示资源边界，不表示精确货币成本。 */
    public record DerivedBudget(String scenario, String role, long businessModels, long auxiliaryModels,
            long tools, long knowledge, long totalTokens, long activeSeconds) { }

    /** 管理端字段元数据。 */
    public record Field(
            String key,
            String label,
            String type,
            String unit,
            BigDecimal minimum,
            BigDecimal maximum,
            List<String> choices,
            boolean secret) {
        public Field {
            choices = List.copyOf(choices);
        }
    }

    public enum VersionContractStatus { SUPPORTED, RETIRED }

    /** 管理端不可变历史版本视图；退休契约不提供可执行配置详情。 */
    public record VersionView(
            long revision, Instant createdAt, String actorId, Map<String, Object> values,
            @com.fasterxml.jackson.annotation.JsonProperty("contract_status") VersionContractStatus contractStatus) {
        public VersionView {
            values = Map.copyOf(values);
            Objects.requireNonNull(contractStatus);
            if (contractStatus == VersionContractStatus.RETIRED && !values.isEmpty())
                throw new IllegalArgumentException("retired_configuration_values_not_available");
        }
    }
}
