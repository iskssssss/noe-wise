package com.skillforge.core.model;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 平台版本与机构选择的持久化边界，SQL 只在对应 XML 中定义。 */
@Mapper
public interface PlatformModelMapper {
    List<String> listCurrent();
    List<String> listDrafts();
    List<PricingDraft> pricingDrafts(@Param("provider") String provider, @Param("model") String model);
    record PricingDraft(String modelId, String storedDraft, Long ruleId) { }
    List<PlatformModelDirectorySummary> listSummaries();
    String draft(@Param("id") String id);
    int createDraft(@Param("id") String id, @Param("config") String config, @Param("ruleId") Long ruleId);
    int saveDraft(@Param("id") String id, @Param("expected") long expected,
            @Param("config") String config, @Param("ruleId") Long ruleId);
    int saveCompatibilityReport(@Param("id") String id, @Param("expected") long expected,
            @Param("report") String report);
    int publishDraft(@Param("id") String id, @Param("expected") long expected,
            @Param("revision") long revision);
    String current(@Param("id") String id);
    String revision(@Param("id") String id, @Param("revision") long revision);
    List<String> history(@Param("id") String id);
    int append(@Param("id") String id, @Param("revision") long revision,
            @Param("config") String config, @Param("actor") String actor, @Param("ruleId") Long ruleId);
    Long draftRuleId(@Param("id") String id);
    Long revisionRuleId(@Param("id") String id, @Param("revision") long revision);
    List<ModelRuleRef> draftRuleRefs();
    List<ModelRuleRef> revisionRuleRefs(@Param("id") String id);
    record ModelRuleRef(String modelId, long revision, Long ruleId) { }
    String bindTenant(@Param("tenant") String tenant);
    ModelSelections selections(@Param("tenant") String tenant);
    int saveSelections(@Param("tenant") String tenant, @Param("s") ModelSelections selections);
    String agentSelection(@Param("tenant") String tenant, @Param("agent") String agent);
    boolean agentExists(@Param("tenant") String tenant, @Param("agent") String agent);
}
