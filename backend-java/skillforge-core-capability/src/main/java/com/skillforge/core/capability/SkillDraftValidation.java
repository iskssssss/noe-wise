package com.skillforge.core.capability;

import java.util.List;

/**
 * 草稿格式校验与声明支持提示；校验通过不代表已验证运行环境或授予执行权限。
 * @param valid 标准包与平台配置是否有效，不承诺运行环境可执行
 * @param issues 阻断错误和非阻断提示，空集表示本次检查未发现问题
 */
public record SkillDraftValidation(boolean valid, List<Issue> issues) {
    /** 提示级别：ERROR 阻止发布，WARNING 表示未支持的声明，INFO 说明尚未核实的依赖。 */
    public enum Severity { ERROR, WARNING, INFO }

    /**
     * 文件问题或支持范围提示；未知位置不伪造行号。
     * @param path 包内文件路径，非文件问题为 null
     * @param line 已核实的行号，未知为 null
     * @param severity 发布错误或非阻断提示级别
     * @param code 可区分的问题编码
     * @param message 解释实际检查范围和未支持的行为
     */
    public record Issue(String path, Integer line, Severity severity, String code, String message) { }
}
