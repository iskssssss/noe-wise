package com.skillforge.core.chat.trajectory;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL归属本模块XML；所有深层读取都携带显式租户和已验证的运行集合。 */
@Mapper
public interface TrajectoryMapper {
    TrajectoryRepository.ProductSession productSession(@Param("tenant") String tenant, @Param("session") String session);
    List<TrajectoryRepository.RunFact> runs(@Param("tenant") String tenant,
            @Param("session") String session, @Param("root") String root);
    List<TrajectoryRepository.Edge> edges(@Param("tenant") String tenant, @Param("runs") List<String> runs);
    List<TrajectoryRepository.EventFact> events(@Param("tenant") String tenant,
            @Param("runs") List<TrajectoryRepository.RunFact> runs, @Param("executionEventType") String executionEventType);
    List<TrajectoryRepository.AttemptFact> attempts(@Param("tenant") String tenant,
            @Param("runs") List<String> runs, @Param("modelRequestSchema") String modelRequestSchema, @Param("modelManifestSchema") String modelManifestSchema);
    List<TrajectoryRepository.AuxiliaryAttemptFact> auxiliaryAttempts(@Param("tenant") String tenant,
            @Param("runs") List<String> runs);
    List<TrajectoryRepository.ModelMaterialFact> modelMaterials(@Param("tenant") String tenant,
            @Param("run") String run, @Param("request") String request, @Param("references") List<String> references);
    TrajectoryRepository.MaterialFact material(@Param("tenant") String tenant, @Param("reference") String reference);
    TrajectoryRepository.CitationEvent citationEvent(@Param("tenant") String tenant, @Param("session") String session,
            @Param("message") String message);
    List<TrajectoryRepository.CitationAttempt> citationAttempts(@Param("tenant") String tenant, @Param("run") String run,
            @Param("result") String result);
    Instant readAt();
}
