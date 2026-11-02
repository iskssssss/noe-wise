package com.skillforge.core.foundation.settings;

import static org.assertj.core.api.Assertions.*;
import static com.skillforge.core.foundation.settings.OperationalSettingsRegistry.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 注册缺失、冲突和业务校验必须在基础持久化之外显式成立。 */
class OperationalSettingsRegistryTest {
    @Test void absentContributorDoesNotCreateAHiddenBusinessGroup() {
        var registry = new OperationalSettingsRegistry(List.of(new FoundationOperationalSettings()));
        assertThatThrownBy(() -> registry.require("task_budget")).isInstanceOf(UnknownGroupException.class);
        assertThatThrownBy(() -> registry.require("prompts")).isInstanceOf(UnknownGroupException.class);
        assertThat(registry.require("calls").defaults()).containsKey("external_retry_attempts");
    }

    @Test void duplicateGroupFailsInsteadOfSelectingAProviderByOrder() {
        var contributor = new FoundationOperationalSettings();
        assertThatThrownBy(() -> new OperationalSettingsRegistry(List.of(contributor, contributor)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("注册重复");
    }

    @Test void registeredRelationValidationRunsAfterFieldNormalization() {
        var definition = group("example", "示例", values -> {
            if (number(values, "overlap").longValueExact() >= number(values, "size").longValueExact())
                throw new InvalidSettingsException("重叠必须小于长度");
        }, stored -> false, integer("size", "长度", null, 1, 100, 10), integer("overlap", "重叠", null, 0, 100, 1));
        assertThatThrownBy(() -> normalize(definition, Map.of("size", 2, "overlap", 3), null))
                .isInstanceOf(InvalidSettingsException.class).hasMessage("重叠必须小于长度");
        assertThat(normalize(definition, Map.of("size", 2, "overlap", 1), null))
                .containsEntry("size", 2L).containsEntry("overlap", 1L);
    }
}
