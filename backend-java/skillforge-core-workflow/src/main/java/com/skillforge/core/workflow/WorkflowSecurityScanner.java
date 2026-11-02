package com.skillforge.core.workflow;

import com.skillforge.core.workflow.WorkflowAssetService.Asset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工作流包安全扫描器。
 *
 * <p>工作流定义已结构化入库（workflow_assets），扫描对象为入库资产内容而非磁盘
 * 文件：检查工作流存在 workflow.yaml 资产，并对全部文本资产做危险命令与脚本模式
 * 静态扫描；返回是否通过、问题列表与 workflow.yaml 校验和。
 * workflow.yaml 全文参与危险模式扫描
 * （正文含 eval( 等触发词会被拒发布，属指令注入防护的有意行为）。
 */
@Component
public class WorkflowSecurityScanner {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(WorkflowSecurityScanner.class);

    /** 危险模式列表，命中即拒绝发布。 */
    private static final List<String> DANGEROUS_PATTERNS = List.of(
            "os.system", "subprocess", "eval(", "exec(", "rm -rf", "curl | sh",
            "powershell -", "cmd /c", "base64 -d", "wget | sh");

    /** 工作流资源资产服务（从 workflow_assets 读取资产内容扫描）。 */
    private final WorkflowAssetService workflowAssetService;

    /**
     * 构造安全扫描器。
     *
     * @param workflowAssetService 工作流资源资产服务
     */
    public WorkflowSecurityScanner(WorkflowAssetService workflowAssetService) {
        this.workflowAssetService = workflowAssetService;
    }

    /**
     * 扫描工作流包（读取 workflow_assets 入库资产内容）。
     *
     * @param workflowId 工作流业务 ID
     * @return 扫描结果：passed / issues / checksum
     */
    public ScanResult scan(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return scanResult(false, List.of("工作流 ID 为空"), null);
        }
        List<Asset> assets = workflowAssetService.listByWorkflow(workflowId);
        if (assets.isEmpty()) {
            return scanResult(false, List.of("工作流包资源不存在（未上传或未入库）"), null);
        }
        boolean hasWorkflowYaml = assets.stream()
                .anyMatch(asset -> "workflow.yaml".equals(asset.assetName()));
        if (!hasWorkflowYaml) {
            return scanResult(false, List.of("工作流包缺少 workflow.yaml"), null);
        }
        List<String> issues = new ArrayList<>();
        for (Asset asset : assets) {
            if (!isScannable(asset.assetName())) {
                continue;
            }
            scanContent(asset.assetName(), asset.content(), issues);
        }
        if (!issues.isEmpty()) {
            return scanResult(false, issues, null);
        }
        return scanResult(true, List.of(), checksum(assets));
    }

    /**
     * 资产名是否参与内容扫描（文本类扩展名）。
     *
     * @param name 资产名
     * @return true 表示扫描
     */
    private boolean isScannable(String name) {
        return name != null && name.toLowerCase(Locale.ROOT)
                .matches(".*\\.(md|yaml|yml|json|py|sh|js|ts)$");
    }

    /**
     * 扫描单个资产内容中的危险模式。
     *
     * @param assetName 资产名
     * @param content   资产字节内容
     * @param issues    问题收集列表
     */
    private void scanContent(String assetName, byte[] content, List<String> issues) {
        String text = new String(content, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        for (String pattern : DANGEROUS_PATTERNS) {
            if (text.contains(pattern)) {
                issues.add(assetName + " 包含危险模式: " + pattern);
                return;
            }
        }
    }

    /**
     * 计算 workflow.yaml 资产的 SHA-256 校验和（包校验和）。
     *
     * @param assets 工作流资产列表
     * @return 十六进制校验和；workflow.yaml 缺失时返回 null
     */
    private String checksum(List<Asset> assets) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Asset asset : assets) {
                if ("workflow.yaml".equals(asset.assetName())) {
                    return HexFormat.of().formatHex(digest.digest(asset.content()));
                }
            }
        } catch (Exception e) {
            log.warn("工作流包校验和计算失败: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * 构建扫描结果，允许校验和为空。
     *
     * @param passed   是否通过
     * @param issues   问题列表
     * @param checksum 工作流包校验和（可空）
     * @return 扫描结果 Map
     */
    private ScanResult scanResult(boolean passed, List<String> issues, String checksum) {
        return new ScanResult(passed, issues, checksum);
    }

    /** 扫描器固定输出；校验和仅在安全扫描通过时存在。 */
    public record ScanResult(boolean passed, List<String> issues, String checksum) {
        public ScanResult { issues = List.copyOf(issues); }
    }
}
