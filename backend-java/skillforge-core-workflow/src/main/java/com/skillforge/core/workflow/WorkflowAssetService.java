package com.skillforge.core.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.workflow.mapper.WorkflowAssetMapper;
import com.skillforge.core.workflow.model.WorkflowAssetDO;
import com.skillforge.core.workflow.model.WorkflowTemplateDO;
import com.skillforge.core.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 工作流资源资产服务，负责工作流关联资源的存储与读取。
 *
 * <p>工作流包资源权威入库。写入与审核共用模板行锁，只允许修改草稿，读取直接来自数据库。
 */
@Service
public class WorkflowAssetService {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(WorkflowAssetService.class);






    /** 资源表映射器。 */
    private final WorkflowAssetMapper assetMapper;

    /**
     * 构造资源资产服务。
     *
     * @param assetMapper 资源表映射器
     */
    public WorkflowAssetService(WorkflowAssetMapper assetMapper) {
        this.assetMapper = assetMapper;
    }

    /**
     * 批量写入草稿资源；同名资源覆盖，并推进模板修订。
     *
     * @param tenantId    租户 ID，可空（平台级工作流）
     * @param workflowId     工作流业务 ID
     * @param assets      资源名 → 字节内容
     */
    @Transactional
    public void upsertAssets(
            String tenantId, String workflowId, Map<String, byte[]> assets) {
        if (assets == null || assets.isEmpty()) {
            return;
        }
        WorkflowTemplateDO template = lockDraft(workflowId);
        String owner = template.getTenantId() == null ? null : template.getTenantId().toString();
        if (!java.util.Objects.equals(owner, tenantId)) throw new ApiException(HttpStatus.FORBIDDEN, "资源所属机构与模板不一致");
        Instant now = Instant.now();
        List<WorkflowAssetDO> rows = assets.entrySet().stream().map(entry -> {
            WorkflowAssetDO row = new WorkflowAssetDO();
            row.setId(SnowflakeIds.nextId());
            row.setTenantId(tenantId == null ? null : Long.parseLong(tenantId));
            row.setWorkflowId(workflowId);
            row.setAssetName(entry.getKey());
            row.setContent(entry.getValue());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            return row;
        }).toList();
        assetMapper.upsertBatch(rows);
        assetMapper.touchTemplate(template.getId());
        log.info("工作流资源写入: workflowId={} assets={}", workflowId, assets.size());
    }

    /**
     * 删除草稿全部资源，并推进模板修订；审核中和已发布资源不可直接清空。
     *
     * @param workflowId 工作流业务 ID
     */
    @Transactional
    public void deleteByWorkflow(String workflowId) {
        WorkflowTemplateDO template = lockDraft(workflowId);
        int affected = assetMapper.delete(new LambdaQueryWrapper<WorkflowAssetDO>()
                .eq(WorkflowAssetDO::getWorkflowId, workflowId));
        assetMapper.touchTemplate(template.getId());
        log.info("工作流资源删除: workflowId={} assets={}", workflowId, affected);
    }

    /**
     * 读取数据库当前工作流资源；无资源返回 null。
     *
     * @param workflowId   工作流业务 ID
     * @param assetName 资源名
     * @return 资源字节；不存在返回 null
     */
    public byte[] readAsset(String workflowId, String assetName) {
        WorkflowAssetDO row = assetMapper.selectOne(
                new LambdaQueryWrapper<WorkflowAssetDO>()
                        .eq(WorkflowAssetDO::getWorkflowId, workflowId)
                        .eq(WorkflowAssetDO::getAssetName, assetName)
                        .last("LIMIT 1"));
        if (row == null) {
            return null;
        }
        return row.getContent();
    }

    /**
     * 读取工作流资源为 UTF-8 文本（workflow.yaml / WORKFLOW.md）。
     *
     * @param workflowId   工作流业务 ID
     * @param assetName 资源名
     * @return 文本内容；不存在返回 null
     */
    public String readTextAsset(String workflowId, String assetName) {
        byte[] content = readAsset(workflowId, assetName);
        return content == null ? null : new String(content, StandardCharsets.UTF_8);
    }

    /**
     * 列出工作流全部资源。
     *
     * @param workflowId 工作流业务 ID
     * @return 资源列表
     */
    public List<Asset> listByWorkflow(String workflowId) {
        return assetMapper.selectList(
                new LambdaQueryWrapper<WorkflowAssetDO>()
                        .eq(WorkflowAssetDO::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowAssetDO::getAssetName)).stream()
                .map(row -> new Asset(row.getId(), row.getAssetName(), row.getContent(), row.getCreatedAt())).toList();
    }

    private WorkflowTemplateDO lockDraft(String workflowId) {
        WorkflowTemplateDO template = assetMapper.lockTemplate(workflowId);
        if (template == null) throw new ApiException(HttpStatus.NOT_FOUND, "工作流不存在");
        if (!"draft".equals(template.getStatus())) throw new ApiException(HttpStatus.CONFLICT, "仅草稿可修改资源，请先创建新的草稿修订");
        return template;
    }
    /** 只读资源快照；字节数组在边界复制，不能修改领域持久对象。 */
    public record Asset(Long id, String assetName, byte[] content, java.time.Instant createdAt) {
        public Asset { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }

}
