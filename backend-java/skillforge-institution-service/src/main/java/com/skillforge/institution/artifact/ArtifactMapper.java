package com.skillforge.institution.artifact;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ArtifactMapper {
    /** 会话产物及其显式输入来源中的真实私有身份，不读取业务正文或相对文件名。 */
    ArtifactRow find(@Param("tenant") String tenant, @Param("id") String id);
    ArtifactRow invocation(@Param("tenant") String tenant, @Param("invocation") String invocation);
    List<ArtifactRow> list(@Param("tenant") String tenant, @Param("session") String session,
                           @Param("policyVersion") String policyVersion);
    long countToolAvailable(@Param("tenant") String tenant, @Param("session") String session,
                            @Param("policyVersion") String policyVersion);
    List<ArtifactRow> listToolPage(@Param("tenant") String tenant, @Param("session") String session,
                                  @Param("policyVersion") String policyVersion,
                                  @Param("offset") int offset, @Param("limit") int limit);
    List<ArtifactInputRow> inputsForArtifacts(@Param("tenant") String tenant,
                                             @Param("artifactIds") List<String> artifactIds);
    List<ArtifactRow> listSessions(@Param("tenant") String tenant,
                                   @Param("sessions") List<String> sessions, @Param("policyVersion") String policyVersion);
    List<ArtifactInputRow> inputs(@Param("tenant") String tenant, @Param("artifact") String artifact);
    List<ArtifactInputRow> sessionInputs(@Param("tenant") String tenant, @Param("session") String session);
    List<ArtifactInputRow> sessionInputsForSessions(@Param("tenant") String tenant,
                                                    @Param("sessions") List<String> sessions);
    int insert(ArtifactRow row);
    int insertFiles(@Param("row") ArtifactRow row, @Param("files") List<ArtifactMetadata.File> files);
    int insertInspections(@Param("tenant") String tenant, @Param("artifact") String artifact,
                          @Param("inspections") List<ArtifactInspectionRow> inspections);
    boolean inspected(@Param("tenant") String tenant, @Param("artifact") String artifact,
                      @Param("path") String path, @Param("sha256") String sha256,
                      @Param("size") long size, @Param("scopeDigest") String scopeDigest,
                      @Param("policyVersion") String policyVersion);
    boolean publiclyInspected(@Param("tenant") String tenant, @Param("artifact") String artifact,
                              @Param("policyVersion") String policyVersion);
    boolean fileReadable(@Param("tenant") String tenant,@Param("artifact") String artifact,@Param("path") String path,
                         @Param("policyVersion") String policyVersion);
    boolean anyFileReadable(@Param("tenant") String tenant,@Param("artifact") String artifact,
                            @Param("policyVersion") String policyVersion);
    List<String> readableArtifactIds(@Param("tenant") String tenant,
                                    @Param("artifactIds") List<String> artifactIds,
                                    @Param("policyVersion") String policyVersion);
    List<ArtifactFileRow> expiredFiles(@Param("tenant") String tenant);
    String lockFile(@Param("tenant") String tenant,@Param("id") String id);
    int markFileExpired(@Param("tenant") String tenant,@Param("id") String id);
    int markFileDeleted(@Param("tenant") String tenant,@Param("id") String id);
    int insertInputs(@Param("tenant") String tenant, @Param("artifact") String artifact,
                     @Param("inputs") List<ArtifactProvenance> inputs);
    List<ArtifactRow> expired(@Param("tenant") String tenant);
    int expire(@Param("tenant") String tenant, @Param("id") String id);
    boolean canChannelAccess(@Param("tenant") String tenant, @Param("channel") String channel, @Param("session") String session);
    String sessionEndUser(@Param("tenant") String tenant, @Param("session") String session);
}
