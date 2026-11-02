package com.skillforge.institution.attachment;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface SessionAttachmentMapper {
    int insert(SessionAttachmentRow row);

    SessionAttachmentRow find(@Param("tenant") String tenant, @Param("session") String session,
                              @Param("id") String id);

    SessionAttachmentRow findMetadata(@Param("tenant") String tenant, @Param("session") String session,
                                      @Param("id") String id);

    List<SessionAttachmentRow> findMetadataBatch(@Param("tenant") String tenant, @Param("session") String session,
                                               @Param("ids") List<String> ids);

    List<SessionAttachmentRow> list(@Param("tenant") String tenant,
                                    @Param("session") String session);

    int clearExpiredContent(@Param("tenant") String tenant, @Param("limit") int limit);
}
