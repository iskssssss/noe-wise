package com.skillforge.institution.artifact;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在同一只读事务内绑定 RLS tenant 并读取 Agent 委派父子 Run 关系。 */
@Service
public class StudioArtifactProjectionStore {
    private final StudioArtifactProjectionMapper mapper;

    public StudioArtifactProjectionStore(StudioArtifactProjectionMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Transactional(readOnly = true)
    public List<StudioArtifactProjectionMapper.Row> children(String tenant, String productSession) {
        mapper.bindTenant(tenant);
        List<StudioArtifactProjectionMapper.Row> rows = mapper.children(tenant, productSession);
        if (rows == null) {
            throw new IllegalStateException("studio_artifact_projection_missing");
        }
        return List.copyOf(rows);
    }

    @Transactional(readOnly = true)
    public Optional<StudioArtifactProjectionMapper.Row> find(String tenant, String childSession, String childRun) {
        mapper.bindTenant(tenant);
        return Optional.ofNullable(mapper.find(tenant, childSession, childRun));
    }
}
