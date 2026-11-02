package com.skillforge.institution.artifact;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 正式发布文件的公开传输边界；调用者只能选择已授权 Session 的发布版本和文件。 */
@Service
public class DeliverableDownloadService {
    private static final Logger LOG=LoggerFactory.getLogger(DeliverableDownloadService.class);
    private final DeliverableReader reader;
    private final ArtifactAccess access;
    private final ArtifactStorage storage;
    public DeliverableDownloadService(DeliverableReader reader,ArtifactAccess access,ArtifactStorage storage) {
        this.reader=reader;this.access=access;this.storage=storage;
    }

    /** 在输出任何字节前复验发布、当前归属和文件检查；回调只接收安全文件名与格式。 */
    public void transfer(String sessionId,String revisionId,String fileId,Consumer<PublishedFile> headers,
            OutputStream output) throws IOException {
        LOG.debug("正式成果文件传输进入");
        try {
            String tenant=access.tenant();access.authorizeCurrent(tenant,sessionId);
            boolean published=reader.list(sessionId,true).stream().filter(value->value.id().equals(revisionId))
                    .flatMap(value->value.files().stream()).anyMatch(value->value.fileId().equals(fileId));
            if(!published)throw ArtifactStorage.unavailable();
            var grant=reader.file(tenant,revisionId,fileId);
            if(!sessionId.equals(grant.sessionId()))throw ArtifactStorage.unavailable();
            access.authorizeArtifactCurrent(tenant,grant.artifact());
            storage.requirePublicInspection(grant.artifact());
            access.auditCurrent(tenant,"deliverable.download",grant.artifact().id(),"下载正式成果文件",
                    java.util.Map.of("revision_id",revisionId,"file_id",fileId));
            headers.accept(new PublishedFile(grant.downloadName(),grant.format()));
            storage.file(grant.artifact(),grant.path(),output);
        } finally { LOG.debug("正式成果文件传输退出"); }
    }
    public record PublishedFile(String downloadName,String format) {}
}
