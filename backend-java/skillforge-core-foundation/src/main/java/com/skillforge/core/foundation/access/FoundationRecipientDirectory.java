package com.skillforge.core.foundation.access;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 公告与通知通过此目录解析当前同体系启用收件人，不跨域读取 IAM Mapper。 */
@Service
public final class FoundationRecipientDirectory {
    private final FoundationAccess access;
    private final FoundationOrganizationMapper mapper;
    /** 注入实时授权和组织目录。 */
    public FoundationRecipientDirectory(FoundationAccess access, FoundationOrganizationMapper mapper) { this.access=access; this.mapper=mapper; }
    /**
     * 可公开的当前收件人资料。
     * @param memberId 当前同体系账号字符串 ID
     * @param email 当前邮箱，可空表示不能电子邮件投递
     * @param displayName 当前显示名称
     */
    public record Recipient(String memberId,String email,String displayName) { }
    /**
     * 解析当前组织范围内启用成员；all 也受发送人范围限制。
     * 不存在或已停用的目标不会通过旧角色/部门缓存扩张收件范围。
     */
    public List<Recipient> resolve(FoundationAccess.Subject sender,String targetType,List<String> targetIds) {
        AccessValidation.choice(targetType,Set.of("member","department","role","all"),"target_type");
        if(targetIds==null || targetIds.size()>1000 || new HashSet<>(targetIds).size()!=targetIds.size()) throw AccessValidation.bad("通知目标必须为不重复且最多1000项的数组");
        if("all".equals(targetType) && !targetIds.isEmpty()) throw AccessValidation.bad("全部成员目标不能携带ID");
        if(!"all".equals(targetType) && targetIds.isEmpty()) throw AccessValidation.bad("请至少选择一个通知目标");
        targetIds.forEach(id->AccessValidation.id(id,"target_id")); access.snapshot(sender);
        return FoundationAccess.inScope(sender,()-> {
            FoundationAccess.DataScope filter=sender.platform()?new FoundationAccess.DataScope(true,sender.memberId(),Set.of()):access.dataScope(sender);
            List<AccessRows.Member> rows=mapper.recipients(sender.scope(),targetType,targetIds,filter);
            if(rows.size()>10_000) throw AccessValidation.bad("单次通知最多10000名成员");
            return rows.stream().map(row->new Recipient(row.getId().toString(),row.getEmail(),row.getDisplayName())).toList();
        });
    }
}
