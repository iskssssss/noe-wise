package com.skillforge.core.agent.dto.response;

import java.util.List;

/** 发布与安装共用的校验结果；path 定位到模板编辑字段。 */
public record AgentTemplateValidationResponse(boolean valid, List<Issue> issues) {
    public AgentTemplateValidationResponse { issues = List.copyOf(issues); }
    public record Issue(String path, String code, String message) { }
}
