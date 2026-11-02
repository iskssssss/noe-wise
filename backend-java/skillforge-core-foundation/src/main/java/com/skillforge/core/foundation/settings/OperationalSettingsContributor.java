package com.skillforge.core.foundation.settings;

import java.util.List;

/** 领域显式贡献配置定义；基础存储只负责类型校验、版本和凭据保护。 */
public interface OperationalSettingsContributor {
    List<OperationalSettingsRegistry.GroupDefinition> groups();
}
