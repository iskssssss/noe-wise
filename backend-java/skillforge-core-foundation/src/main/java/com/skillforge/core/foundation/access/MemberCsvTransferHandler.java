package com.skillforge.core.foundation.access;

import com.skillforge.core.foundation.jobs.CsvTransferHandler;
import com.skillforge.core.foundation.jobs.FoundationJobContracts;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 成员组织资料 CSV 更新，不创建账号、不改变角色/状态，不导出或导入密码与安全材料。 */
@Component
public final class MemberCsvTransferHandler implements CsvTransferHandler {
    private final FoundationOrganizationService organization;
    /** 通过组织公开服务复用范围、CAS、事务和提交后撤权。 */
    public MemberCsvTransferHandler(FoundationOrganizationService organization) { this.organization=organization; }
    /** 稳定处理器。 */ @Override public String code(){return "member_profiles";}
    /** 用户可见名称。 */ @Override public String title(){return "成员组织资料";}
    /** 固定表头，所有身份保持文本。 */ @Override public List<String> columns(){return List.of("username","display_name","email","department_id","position_id");}
    /** 已有成员资料更新权限。 */ @Override public String importPermission(){return AccessPermissions.ORGANIZATION_MANAGE;}
    /** 当前组织范围资料导出权限。 */ @Override public String exportPermission(){return AccessPermissions.ORGANIZATION_MANAGE;}
    /** 此处理器无选项，未知键拒绝。 */ @Override public void validateOptions(Map<String,String> options){if(options==null || !options.isEmpty()) throw AccessValidation.bad("成员资料没有额外导入选项");}
    /** 读取真实对象并校验同体系启用部门/岗位，预检不写入。 */
    @Override public List<FoundationJobContracts.RowError> validateRow(Map<String,String> options,int rowNumber,List<String> cells){
        try { organization.validateMemberProfile(command(cells)); return List.of(); }
        catch(RuntimeException failure){return List.of(new FoundationJobContracts.RowError(rowNumber,"row",failure instanceof com.skillforge.core.shared.exception.ApiException?failure.getMessage():"成员资料不符合导入规则"));}
    }
    /** 外层工作者事务将行写入与回执一起提交，角色/状态沿用实时已有事实。 */
    @Override public void importRow(Map<String,String> options,String jobId,int rowNumber,List<String> cells){organization.updateMember(command(cells));}
    /** 同一授权范围查询最多一万行，不导出安全材料。 */
    @Override public List<List<String>> exportRows(Map<String,String> options){return organization.exportMembers().stream().map(member->List.of(member.username(),member.displayName(),empty(member.email()),empty(member.departmentId()),empty(member.positionId()))).toList();}
    /** 空单元格只在明确可空资料字段表示清空，不用于身份字段。 */
    private AccessCommands.MemberUpdate command(List<String> cells){
        if(cells==null || cells.size()!=columns().size()) throw AccessValidation.bad("CSV列数不匹配");
        AccessViews.Member member=organization.findMemberByUsername(cells.get(0)); AccessValidation.text(cells.get(1),128,"display_name");
        String department=nullable(cells.get(3)),position=nullable(cells.get(4));
        if(department!=null && organization.departments().stream().noneMatch(value->value.id().equals(department)&&value.enabled())) throw AccessValidation.bad("部门不存在、已停用或跨账号体系");
        if(position!=null && organization.positions().stream().noneMatch(value->value.id().equals(position)&&value.enabled())) throw AccessValidation.bad("岗位不存在、已停用或跨账号体系");
        String email=nullable(cells.get(2));
        if(email!=null && (email.length()>255 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) throw AccessValidation.bad("邮箱格式无效");
        return new AccessCommands.MemberUpdate(member.id(),cells.get(1),email,department,position,member.status(),member.lockVersion());
    }
    /** 导出可空资料为空单元格。 */ private String empty(String value){return value==null?"":value;}
    /** 仅CSV明确声明的可空字段允许空单元格表示清空。 */ private String nullable(String value){return value==null || value.isEmpty()?null:value;}
}
