package com.skillforge.core.knowledge;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.core.knowledge.mapper.KnowledgeObservationAccessMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.KnowledgeSourceVersion;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 历史知识轨迹正文的当前来源授权；只核验既有事实，不检索、不改换冻结版本。 */
@Service
public class KnowledgeObservationAccess {
    private final FoundationAccess access;
    private final PlatformObservationAccess platformAccess;
    private final KnowledgeObservationAccessMapper mapper;

    public KnowledgeObservationAccess(FoundationAccess access, PlatformObservationAccess platformAccess,
            KnowledgeObservationAccessMapper mapper) {
        this.access = access;
        this.platformAccess = platformAccess;
        this.mapper = mapper;
    }

    /**
     * 调用方先证明实际执行的Agent/Session/target闭包；此处继续核对当前对象权限和精确来源可读性。
     * 平台特权必须来自当前平台主体，不能由布尔参数单独获得。
     */
    @Transactional(readOnly = true)
    public void requireRead(TenantId tenantId, AgentId agentId, SessionRef sessionRef,
            FrozenKnowledgeTarget target, Set<KnowledgeSourceVersion> sourceVersions, boolean platformPrivilege) {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(agentId);
        Objects.requireNonNull(sessionRef);
        Objects.requireNonNull(target);
        sourceVersions = Set.copyOf(sourceVersions);
        if (!tenantId.equals(sessionRef.tenantId()) || !positiveId(tenantId.value())
                || !positiveId(target.descriptorId()) || target.sourceVersions().isEmpty()
                || target.sourceVersions().stream().anyMatch(source -> !source.sourceId().equals(target.descriptorId()))
                || !target.sourceVersions().containsAll(sourceVersions)) {
            throw denied();
        }
        var subject = access.currentSubject();
        if (platformPrivilege) {
            if (!subject.platform()) throw denied();
            access.require("foundation.sessions.read");
        } else {
            if (subject.platform() || !tenantId.value().equals(subject.tenantId())) throw denied();
            access.requireResource("knowledge", target.descriptorId(), "read");
        }
        var targetVersions = ordered(target.sourceVersions());
        var selectedVersions = ordered(sourceVersions);
        if (!platformAccess.read(Long.parseLong(tenantId.value()),
                () -> mapper.readable(tenantId.value(), target, targetVersions, selectedVersions)))
            throw denied();
    }

    private static List<KnowledgeSourceVersion> ordered(Set<KnowledgeSourceVersion> versions) {
        return versions.stream().sorted(Comparator.comparing(KnowledgeSourceVersion::sourceId)
                .thenComparing(KnowledgeSourceVersion::sourceVersion)).toList();
    }

    private static boolean positiveId(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(value) > 0; }
        catch (NumberFormatException invalid) { return false; }
    }

    private static ApiException denied() {
        return new ApiException(HttpStatus.FORBIDDEN, "knowledge.observation.denied", "当前无法读取该知识来源", false);
    }
}
