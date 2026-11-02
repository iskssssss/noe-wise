package com.skillforge.core.foundation.assets;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import com.skillforge.core.foundation.catalog.entitlements.ResourceKind;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 公共后台文件公开应用入口；身份来自 FoundationAccess，业务引用由所有者先授权再维护。 */
public class FoundationFileService {
    private static final Logger log=LoggerFactory.getLogger(FoundationFileService.class);
    private final FoundationFileMapper mapper;
    private final FoundationAccess access;
    private final FeatureGate features;
    private final ResourceQuota quota;
    private final TransactionTemplate tx;
    private final Path root;
    private final long maxBytes;
    private final int orphanHours;
    private final ObjectProvider<FoundationFileReferenceAuthorizer> referenceOwners;
    /** 绑定持久目录与依赖；非法部署参数阻止能力启动。 */
    public FoundationFileService(FoundationFileMapper mapper, FoundationAccess access, FeatureGate features,
            ResourceQuota quota, PlatformTransactionManager manager, FoundationFileProperties properties,
            ObjectProvider<FoundationFileReferenceAuthorizer> referenceOwners) {
        this.mapper=mapper;this.access=access;this.features=features;this.quota=quota;this.tx=new TransactionTemplate(manager);this.referenceOwners=referenceOwners;
        if(properties.getMaxBytes()<1 || properties.getMaxBytes()>100*1024*1024L || properties.getOrphanHours()<1)
            throw new IllegalArgumentException("公共文件容量或保留时间配置无效");
        maxBytes=properties.getMaxBytes();orphanHours=properties.getOrphanHours();
        root=Path.of(properties.getRoot()).toAbsolutePath().normalize();
        try {Files.createDirectories(root);if(Files.isSymbolicLink(root))throw new IOException("文件根目录不可为符号链接");}
        catch(IOException e){throw new IllegalStateException("公共文件持久目录不可用",e);}
    }
    /** 上传当前主体的公共文件，字节读取受界限保护；元数据失败则回收本次物理文件。 */
    public FoundationFileContracts.FileView upload(String name, InputStream stream) {
        access.require("foundation.files.upload");requireFeature();FoundationFilePolicy.validateName(name);
        byte[] bytes;
        try {bytes=stream.readNBytes(Math.toIntExact(maxBytes+1));}
        catch(IOException e){throw new ApiException(HttpStatus.BAD_REQUEST,"文件读取失败",e);}
        if(bytes.length>maxBytes)throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"file_too_large","文件超过允许容量",false);
        String mime=FoundationFilePolicy.detect(name,bytes);
        return store(name,mime,bytes,"upload");
    }
    /** 受控业务结果文件写入；调用者已完成业务授权，仍检查上传权限、功能及配额。 */
    public FoundationFileContracts.FileView createResult(String name,byte[] bytes) {
        access.require("foundation.files.upload");requireFeature();
        if(bytes.length>maxBytes)throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"file_too_large","导出超过单文件容量",false);
        return store(name,FoundationFilePolicy.detect(name,bytes),bytes,"job_result");
    }
    /** 保存随机对象键，创建事务同时检查配额；无跨租户内容去重。 */
    private FoundationFileContracts.FileView store(String name,String mime,byte[] bytes,String sourceKind) {
        String key=UUID.randomUUID().toString();Path path=path(key);
        try {
            Files.write(path,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            return tx.execute(status->{bind();if(!access.currentSubject().platform())quota.requireCapacity(ResourceKind.FILE_BYTES,bytes.length);
                var subject=access.currentSubject();var row=new FoundationFileDO();row.setId(key);row.setBlobKey(key);
                row.setTenantId(subject.tenantId()==null?null:Long.valueOf(subject.tenantId()));row.setCreatedBy(subject.memberId());
                row.setName(name);row.setMimeType(mime);row.setSizeBytes(bytes.length);row.setSha256(digest(bytes));row.setState("ready");row.setSourceKind(sourceKind);
                mapper.insert(row);return view(mapper.lock(key));});
        } catch(IOException e){throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"文件存储不可用",e);}
        catch(RuntimeException e){try{Files.deleteIfExists(path);}catch(IOException cleanup){e.addSuppressed(cleanup);log.error("公共文件受理回收失败 file_id={}",key,cleanup);}throw e;}
    }
    /** 当前作用域管理列表采用稳定创建时刻和文件身份排序。 */
    public FoundationFileContracts.Page list(int page,int size) {
        access.require("foundation.files.read");validatePage(page,size);
        return tx.execute(status->{bind();return new FoundationFileContracts.Page(mapper.list((long)(page-1)*size,size).stream().map(FoundationFileService::view).toList(),mapper.count(),page,size);});
    }
    /** 每次下载重新检查当前权限与租户；响应只能下载，不提供可执行 inline 页面。 */
    public FoundationFileContracts.Download download(String id) {
        access.require("foundation.files.read");
        return tx.execute(status->{bind();var row=ready(id);authorizeGenerated(row);try{
            byte[] bytes=Files.readAllBytes(path(row.getBlobKey()));
            if(bytes.length!=row.getSizeBytes() || !digest(bytes).equals(row.getSha256()))
                throw new ApiException(HttpStatus.CONFLICT,"file_integrity_failed","文件内容校验失败",false);
            return new FoundationFileContracts.Download(view(row),bytes);
        }catch(IOException e){throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"文件读取不可用",e);}});
    }
    /** 公开消费者须先在所属领域验证 owner 的写权，引用键不能来自未授权的请求。 */
    public void attach(String id,FoundationFileContracts.Reference reference) {
        access.require("foundation.files.read");validateReference(reference);
        tx.executeWithoutResult(status->{bind();ready(id);mapper.attach(id,reference.ownerKind(),reference.ownerId());});
    }
    /** 由同一业务所有者在解除关系的事务中调用，文件本体不因此立即删除。 */
    public void detach(String id,FoundationFileContracts.Reference reference) {
        access.require("foundation.files.read");validateReference(reference);
        tx.executeWithoutResult(status->{bind();ready(id);mapper.detach(id,reference.ownerKind(),reference.ownerId());});
    }
    /** 零引用文件先记录删除意图，磁盘失败保留 deleting 供调度重试。 */
    public void delete(String id) {
        access.require("foundation.files.delete");
        var row=tx.execute(status->{bind();var locked=ready(id);if(mapper.markDeleting(id)!=1)
            throw new ApiException(HttpStatus.CONFLICT,"file_referenced","文件仍被业务引用，不能删除",false);return locked;});
        removePhysical(row);
    }
    /** 只扫描旧孤立文件和可靠删除意图；调度身份只用于文件元数据清理。 */
    public void cleanup() {
        var rows=tx.execute(status->{mapper.bindMaintenance();return mapper.cleanupCandidates(OffsetDateTime.now().minusHours(orphanHours),100);});
        for(var row:rows) {
            boolean selected=tx.execute(status->{bindScope(row.getTenantId());var locked=mapper.lock(row.getId());
                return locked!=null && ("deleting".equals(locked.getState()) || mapper.markDeleting(row.getId())==1);});
            if(selected)try{removePhysical(row);}catch(RuntimeException e){log.warn("公共文件清理保留待重试 file_id={}",row.getId(),e);}
        }
        // 元数据受理前进程崩溃留下的随机文件只在保留窗后回收；已登记对象始终由上面的引用检查保护。
        try(var entries=Files.list(root)){entries.filter(p->p.getFileName().toString().endsWith(".blob")).limit(1000).forEach(p->{
            try{if(Files.getLastModifiedTime(p,LinkOption.NOFOLLOW_LINKS).toInstant().isAfter(java.time.Instant.now().minusSeconds(orphanHours*3600L)))return;
                String key=p.getFileName().toString().replace(".blob","");UUID.fromString(key);
                boolean absent=tx.execute(status->{mapper.bindMaintenance();return !mapper.exists(key);});
                if(absent)Files.deleteIfExists(p);
            }catch(Exception e){log.warn("公共文件孤立对象收尾失败 name={}",p.getFileName(),e);}
        });}catch(IOException e){log.warn("公共文件根目录清理扫描失败",e);}
    }
    /** 只供任务生命周期所有者在确认终态到期且锁定任务的事务中调用；此入口不对 HTTP 暴露。 */
    public void releaseExpiredJobReferences(String jobId,Long tenantId){validateId(jobId);tx.executeWithoutResult(status->{bindScope(tenantId);mapper.releaseJobReferences(jobId);});}
    /** 物理释放严格限制在自有目录；收尾失败可幂等重试。 */
    private void removePhysical(FoundationFileDO row) {
        try {Files.deleteIfExists(path(row.getBlobKey()));tx.executeWithoutResult(status->{bindScope(row.getTenantId());mapper.markDeleted(row.getId());});}
        catch(IOException e){throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"文件删除等待重试",e);}
    }
    /** 绑定可信当前主体，NULL 租户只表示独立平台作用域。 */
    private void bind(){var subject=access.currentSubject();mapper.bind(subject.tenantId()==null?"":subject.tenantId(),subject.platform());}
    /** 独立平台资源不属于租户套餐；租户上传需实时检查功能授权。 */
    private void requireFeature(){if(!access.currentSubject().platform())features.require("foundation.files");}
    /** 生成结果仍属于业务权威；没有已注册引用所有者时失败关闭，不能仅靠 files.read 放行。 */
    private void authorizeGenerated(FoundationFileDO row){if(!"job_result".equals(row.getSourceKind()))return;var refs=mapper.references(row.getId());if(refs.isEmpty())throw new ApiException(HttpStatus.FORBIDDEN,"生成文件没有有效业务引用");for(var ref:refs){var owners=referenceOwners.stream().filter(owner->owner.ownerKind().equals(ref.ownerKind())).toList();if(owners.size()!=1)throw new ApiException(HttpStatus.FORBIDDEN,"文件业务授权不可用");owners.getFirst().authorizeFile(ref.ownerId(),row.getId());}}
    /** 清理服务只使用已持久化归属恢复数据库作用域。 */
    private void bindScope(Long tenantId){mapper.bind(tenantId==null?"":tenantId.toString(),tenantId==null);}
    /** 不存在、其他租户和已删除统一不暴露对象信息。 */
    private FoundationFileDO ready(String id){validateId(id);var row=mapper.lock(id);if(row==null||!"ready".equals(row.getState()))throw new ApiException(HttpStatus.NOT_FOUND,"文件不存在");return row;}
    /** 随机 UUID 键解析后再检查规范化路径与符号链接。 */
    private Path path(String key){validateId(key);Path result=root.resolve(key+".blob").normalize();if(!result.getParent().equals(root)||Files.isSymbolicLink(result))throw new IllegalStateException("公共文件路径不安全");return result;}
    /** 文件身份不接受任意路径和非规范 UUID。 */
    public static void validateId(String id){try{if(id==null||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new ApiException(HttpStatus.BAD_REQUEST,"文件标识无效");}}
    /** 页号越界拒绝，避免改变用户原始请求。 */
    public static void validatePage(int page,int size){if(page<1||size<1||size>200)throw new ApiException(HttpStatus.BAD_REQUEST,"分页参数无效");}
    /** 固定领域代码和稳定资源键均有限长。 */
    private static void validateReference(FoundationFileContracts.Reference ref){if(ref==null||ref.ownerKind()==null||!ref.ownerKind().matches("[a-z][a-z0-9_.-]{0,63}")||ref.ownerId()==null||ref.ownerId().isBlank()||ref.ownerId().length()>128)throw new ApiException(HttpStatus.BAD_REQUEST,"引用键无效");}
    /** 明确投影，物理对象键与创建主体不向下载页面泄露。 */
    private static FoundationFileContracts.FileView view(FoundationFileDO row){return new FoundationFileContracts.FileView(row.getId(),row.getName(),row.getMimeType(),row.getSizeBytes(),row.getSha256(),row.getReferenceCount(),row.getCreatedAt());}
    /** 原始内容摘要用于存储与下载一致性；不是访问令牌。 */
    public static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
