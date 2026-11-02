package com.skillforge.core.foundation.institution;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.shared.web.DecimalStringIdDeserializer;
import com.skillforge.core.shared.web.StrictLongDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/** 机构关系安全协议；关系管理不开放下级身份、业务或费用信息。 */
public final class InstitutionRelationContracts {
    private InstitutionRelationContracts() { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Relation(String tenantId,String parentTenantId,String name,String status,long revision,long treeRevision) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Page(List<Relation> items,long total,int page,int pageSize,long treeRevision) {
        public Page {items=List.copyOf(items);}
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Change(
            @Schema(type="string",pattern="[1-9][0-9]*") @JsonDeserialize(using=DecimalStringIdDeserializer.class) @NotNull @Positive Long childTenantId,
            @Schema(type="string",pattern="[1-9][0-9]*") @JsonDeserialize(using=DecimalStringIdDeserializer.class) @Positive Long parentTenantId,
            @JsonDeserialize(using=StrictLongDeserializer.class) @Min(1) long expectedRevision) { }
}
