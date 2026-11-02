package com.skillforge.core.foundation.jobs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.assets.FoundationFileContracts;
import com.skillforge.core.foundation.assets.FoundationFileService;
import com.skillforge.core.foundation.assets.FoundationFileReferenceAuthorizer;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.foundation.catalog.entitlements.ResourceKind;
import com.skillforge.core.foundation.catalog.entitlements.ResourceQuota;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 管理任务公开应用入口与可靠执行；固定处理器、实时撤权、租约和逐行回执共同保护副作用。 */
public class FoundationJobService implements FoundationFileReferenceAuthorizer {
    private static final Logger log=LoggerFactory.getLogger(FoundationJobService.class);
    /** 查询投影最多落后64个行边界或一秒；恢复依据事务内回执位图，不能依赖此投影。 */
    private static final int PROGRESS_ROWS=64;
    /** 进度刷新使用单调时间，墙上时钟调整不改变写入频率。 */
    private static final long PROGRESS_NANOS=java.time.Duration.ofSeconds(1).toNanos();
    private final FoundationJobMapper mapper;
    private final FoundationAccess access;
    private final FoundationFileService files;
    private final FeatureGate features;
    private final ResourceQuota quota;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Map<String,CsvTransferHandler> handlers;
    /** 处理器由代码装配；重复编码阻止启动，不接受客户端实现路径。 */
    public FoundationJobService(FoundationJobMapper mapper,FoundationAccess access,FoundationFileService files,
            FeatureGate features,ResourceQuota quota,ObjectMapper json,PlatformTransactionManager manager,List<CsvTransferHandler> registered) {
        this.mapper=mapper;this.access=access;this.files=files;this.features=features;this.quota=quota;this.json=json;tx=new TransactionTemplate(manager);
        Map<String,CsvTransferHandler> registry=new LinkedHashMap<>();
        for(var handler:registered){if(!handler.code().matches("[a-z][a-z0-9_.-]{0,63}")||handler.columns().isEmpty()||registry.putIfAbsent(handler.code(),handler)!=null)throw new IllegalStateException("管理任务处理器注册冲突");}
        handlers=Map.copyOf(registry);
    }
    /** 当前权限可见的注册处理器；没有权限的动作不在页面开放。 */
    public List<FoundationJobContracts.HandlerView> handlers(){access.require("foundation.jobs.read");return handlers.values().stream().sorted(java.util.Comparator.comparing(CsvTransferHandler::code)).map(h->new FoundationJobContracts.HandlerView(h.code(),h.title(),h.columns(),h.optionFields(),h.importPermission()!=null&&access.allowed(h.importPermission()),h.exportPermission()!=null&&access.allowed(h.exportPermission()))).filter(h->h.canImport()||h.canExport()).toList();}
    /** 预检完整材料，逐行公开可纠正错误；没有任何领域写入。 */
    public FoundationJobContracts.PreflightResult preflight(FoundationJobContracts.Preflight command) {
        access.require("foundation.jobs.create");requireFeature();var handler=handler(command.handler());
        requireOperation(handler,"import");var options=options(command.options());handler.validateOptions(options);
        var rows=input(command.fileId(),handler);var errors=new ArrayList<FoundationJobContracts.RowError>();
        for(int i=0;i<rows.size();i++){try{errors.addAll(handler.validateRow(options,i+2,rows.get(i)));}catch(ApiException failure){errors.add(new FoundationJobContracts.RowError(i+2,"row",safeError(failure)));}}
        return new FoundationJobContracts.PreflightResult(rows.size(),errors.isEmpty(),errors);
    }
    /** 同键同完整请求返回已受理任务，同键异内容明确冲突；仅新受理消耗并发配额。 */
    public FoundationJobContracts.JobView submit(FoundationJobContracts.Submit command) {
        access.require("foundation.jobs.create");requireFeature();var handler=handler(command.handler());
        requireOperation(handler,command.operation());var options=options(command.options());handler.validateOptions(options);
        if(command.idempotencyKey()==null||!command.idempotencyKey().matches("[A-Za-z0-9_.:-]{1,128}"))throw invalid("幂等键无效");
        if("export".equals(command.operation())&&command.fileId()!=null)throw invalid("导出不能指定输入文件");
        String optionsJson=write(new TreeMap<>(options));
        String digest=FoundationFileService.digest(write(List.of(handler.code(),command.operation(),command.fileId()==null?"":command.fileId(),optionsJson)).getBytes(StandardCharsets.UTF_8));
        var old=tx.execute(status->{bind();return mapper.byKey(command.idempotencyKey());});
        if(old!=null)return sameRequest(old,digest);
        int total=0;
        if("import".equals(command.operation())){var checked=preflight(new FoundationJobContracts.Preflight(handler.code(),command.fileId(),options));if(!checked.valid())throw new ApiException(HttpStatus.BAD_REQUEST,"import_preflight_failed","导入预检失败，请先修正逐行错误",false);total=checked.rowCount();}
        int totalRows=total;
        return tx.execute(status->{bind();var existing=mapper.byKey(command.idempotencyKey());if(existing!=null)return sameRequest(existing,digest);
            if(!access.currentSubject().platform())quota.requireCapacity(ResourceKind.ADMIN_TASK,1);var subject=access.currentSubject();var row=new FoundationJobDO();
            row.setId(UUID.randomUUID().toString());row.setTenantId(subject.tenantId()==null?null:Long.valueOf(subject.tenantId()));row.setMemberId(subject.memberId());
            row.setHandler(handler.code());row.setOperation(command.operation());row.setInputFileId(command.fileId());row.setOptionsJson(optionsJson);
            row.setIdempotencyKey(command.idempotencyKey());row.setRequestDigest(digest);row.setTotalRows(totalRows);
            if(mapper.insert(row)==0)return sameRequest(mapper.byKey(command.idempotencyKey()),digest);
            if(row.getInputFileId()!=null)files.attach(row.getInputFileId(),reference(row.getId()));
            log.info("管理任务受理 job_id={} handler={} operation={} tenant_id={}",row.getId(),handler.code(),command.operation(),row.getTenantId());
            return view(mapper.lock(row.getId()));});
    }
    /** 当前作用域列表，默认创建时间和身份倒序。 */
    public FoundationJobContracts.Page list(int page,int size){access.require("foundation.jobs.read");FoundationFileService.validatePage(page,size);return tx.execute(status->{bind();return new FoundationJobContracts.Page(mapper.list((long)(page-1)*size,size).stream().map(FoundationJobService::view).toList(),mapper.count(),page,size);});}
    /** 单任务查询读取已提交状态，不与工作者争抢行锁，也不泄露其他租户身份。 */
    public FoundationJobContracts.JobView detail(String id){access.require("foundation.jobs.read");return tx.execute(status->{bind();FoundationFileService.validateId(id);var row=mapper.find(id);if(row==null)throw new ApiException(HttpStatus.NOT_FOUND,"管理任务不存在");return view(row);});}
    /** 取消 queued 立即终态，running 由租约所有者在行边界确认；不声称回滚已经提交行。 */
    public FoundationJobContracts.JobView cancel(String id){access.require("foundation.jobs.cancel");return tx.execute(status->{bind();ready(id);mapper.cancel(id);return view(mapper.lock(id));});}
    /** 错误按数据行分页，不包含原始单元格或凭据。 */
    public List<FoundationJobContracts.RowError> errors(String id,int page,int size){access.require("foundation.jobs.read");FoundationFileService.validatePage(page,size);return tx.execute(status->{bind();ready(id);var result=new ArrayList<FoundationJobContracts.RowError>();for(String value:mapper.errors(id,(long)(page-1)*size,size))result.addAll(readErrors(value));return List.copyOf(result);});}
    /** 每个宿主只领取自己的平台或机构队列；数据库租约保证同一任务只有一个有效工作者。 */
    public boolean runNext(boolean platform) {
        String owner=UUID.randomUUID().toString();var job=tx.execute(status->{mapper.bindDispatch(platform);return mapper.claim(owner);});
        if(job==null)return false;
        var subject=new FoundationAccess.Subject(job.getTenantId()==null,job.getTenantId()==null?null:job.getTenantId().toString(),job.getMemberId());
        try {access.withSubject(subject,()->{execute(job,owner);return null;});}
        catch(LeaseLost failure){log.info("管理任务租约失效，停止写入 job_id={}",job.getId());}
        catch(RuntimeException failure){log.warn("管理任务失败 job_id={} handler={}",job.getId(),job.getHandler(),failure);
            persistedScope(job,()->tx.executeWithoutResult(status->{bindPersisted(job);if("import".equals(job.getOperation()))mapper.progress(job.getId(),owner);mapper.finish(job.getId(),owner,"failed",safeError(failure),null,job.getTotalRows());}));}
        return true;
    }
    /** 执行保存主体下的受控领域操作，当前撤权或停用立即阻止下一笔副作用。 */
    private void execute(FoundationJobDO job,String owner) {
        if("cancel_requested".equals(job.getState())){finish(job,owner,"cancelled",null,null,job.getTotalRows());return;}
        requireFeature();var handler=handler(job.getHandler());requireOperation(handler,job.getOperation());
        var options=readOptions(job.getOptionsJson());handler.validateOptions(options);
        if("export".equals(job.getOperation())){
            tx.executeWithoutResult(status->{bind();lease(job.getId(),owner);});
            var rows=handler.exportRows(options);byte[] output=FoundationCsv.encode(handler.columns(),rows);
            var result=tx.execute(status->{bind();lease(job.getId(),owner);requireOperation(handler,"export");requireFeature();var file=files.createResult(handler.code()+"-"+job.getId()+".csv",output);files.attach(file.id(),reference(job.getId()));return file;});
            finish(job,owner,"succeeded",null,result.id(),rows.size());return;
        }
        var rows=input(job.getInputFileId(),handler);
        long lastProgress=System.nanoTime();
        for(int i=0;i<rows.size();i++) {
            int rowNumber=i+2;var cells=rows.get(i);
            boolean cancelled=tx.execute(status->{bind();var current=ready(job.getId());return "cancel_requested".equals(current.getState());});
            if(cancelled){finish(job,owner,"cancelled",null,null,rows.size());return;}
            try {tx.executeWithoutResult(status->{bind();lease(job.getId(),owner);requireOperation(handler,"import");requireFeature();
                if(mapper.hasReceipt(job.getId(),rowNumber))return;
                var errors=handler.validateRow(options,rowNumber,cells);
                if(errors.isEmpty())handler.importRow(options,job.getId(),rowNumber,cells);
                recordRow(job.getId(),rowNumber,errors);});}
            catch(LeaseLost failure){throw failure;}
            catch(ApiException failure){
                // 领域行写入回滚后才记录错误；撤权错误终止整个任务，不能把权限失败当成普通坏行继续执行。
                if(failure.getStatus()==HttpStatus.FORBIDDEN||failure.getStatus()==HttpStatus.UNAUTHORIZED)throw failure;
                tx.executeWithoutResult(status->{bind();lease(job.getId(),owner);if(!mapper.hasReceipt(job.getId(),rowNumber))recordRow(job.getId(),rowNumber,List.of(new FoundationJobContracts.RowError(rowNumber,"row",safeError(failure))));});
            }
            if((i+1)%PROGRESS_ROWS==0||System.nanoTime()-lastProgress>=PROGRESS_NANOS){tx.executeWithoutResult(status->{bind();mapper.progress(job.getId(),owner);});lastProgress=System.nanoTime();}
        }
        var current=tx.execute(status->{bind();lease(job.getId(),owner);mapper.progress(job.getId(),owner);return ready(job.getId());});
        String resultId=null;
        if(current.getErrorRows()>0){var errors=allErrors(job.getId());var byRow=new LinkedHashMap<Integer,List<FoundationJobContracts.RowError>>();for(var error:errors)byRow.computeIfAbsent(error.rowNumber(),key->new ArrayList<>()).add(error);
            var output=FoundationCsv.encode(List.of("row_number","errors"),byRow.entrySet().stream().map(e->List.of(Integer.toString(e.getKey()),e.getValue().stream().map(error->error.field()+": "+error.message()).collect(java.util.stream.Collectors.joining("; ")))).toList());
            resultId=tx.execute(status->{bind();lease(job.getId(),owner);var file=files.createResult("import-errors-"+job.getId()+".csv",output);files.attach(file.id(),reference(job.getId()));return file.id();});}
        finish(job,owner,current.getErrorRows()==0?"succeeded":"failed",current.getErrorRows()==0?null:"部分行处理失败，已提交行保留，请查看逐行错误",resultId,rows.size());
    }
    /** 终态写入不能覆盖另一租约；取消请求优先于成功。 */
    private void finish(FoundationJobDO job,String owner,String state,String message,String resultId,int total){persistedScope(job,()->tx.executeWithoutResult(status->{bindPersisted(job);var row=ready(job.getId());if(!owner.equals(row.getLeaseOwner()))throw new LeaseLost();if("import".equals(job.getOperation()))mapper.progress(job.getId(),owner);String finalState="cancel_requested".equals(row.getState())?"cancelled":state;if(mapper.finish(job.getId(),owner,finalState,message,resultId,total)!=1)throw new LeaseLost();}));}
    /** 过期清理仅释放终态材料，保留请求摘要与幂等墓碑。 */
    public void cleanup(boolean platform){for(int batch=0;batch<4;batch++){var jobs=tx.execute(status->{mapper.bindDispatch(platform);return mapper.expired(100);});for(var job:jobs)persistedScope(job,()->tx.executeWithoutResult(status->{bindPersisted(job);var current=mapper.lock(job.getId());if(current==null||current.getExpiresAt()==null||!List.of("succeeded","failed","cancelled").contains(current.getState()))return;
        // 数据库到期CAS先确认所有者退役；引用和回执释放共同提交，失败会恢复完整材料。
        if(mapper.expire(job.getId())==1){files.releaseExpiredJobReferences(job.getId(),job.getTenantId());mapper.deleteReceipts(job.getId());mapper.deleteReceiptBlocks(job.getId());}}));if(jobs.size()<100)break;}}
    /** 领域效果、紧凑身份和错误定位共同提交；身份写入失败不能留下未受恢复保护的业务效果。 */
    private void recordRow(String id,int row,List<FoundationJobContracts.RowError> errors){if(mapper.receipt(id,row)!=1)throw new IllegalStateException("管理任务行回执写入冲突");if(!errors.isEmpty()&&mapper.rowError(id,row,write(errors))!=1)throw new IllegalStateException("管理任务行错误写入冲突");}
    /** 读取完整错误只用于容量有限的结果文件。 */
    private List<FoundationJobContracts.RowError> allErrors(String id){return tx.execute(status->{bind();var out=new ArrayList<FoundationJobContracts.RowError>();for(String value:mapper.errors(id,0,FoundationCsv.MAX_ROWS))out.addAll(readErrors(value));return out;});}
    /** 输入必须是不可变受控 CSV；文件 download 会重新授权并验摘要。 */
    private List<List<String>> input(String fileId,CsvTransferHandler handler){var material=files.download(fileId);if(!"text/csv".equals(material.file().mimeType()))throw invalid("导入仅接受 CSV 文件");return FoundationCsv.parse(material.bytes(),handler.columns());}
    /** 精确操作白名单与所属领域权限。 */
    private void requireOperation(CsvTransferHandler handler,String operation){String permission=switch(operation==null?"":operation){case "import"->handler.importPermission();case "export"->handler.exportPermission();default->throw invalid("任务操作无效");};if(permission==null)throw invalid("处理器不支持此操作");access.require(permission);}
    /** 只接受代码注册处理器。 */
    private CsvTransferHandler handler(String code){var handler=handlers.get(code);if(handler==null)throw invalid("管理任务处理器未注册");return handler;}
    /** 固定容量的字符串领域选项，未知键由所属处理器拒绝。 */
    private static Map<String,String> options(Map<String,String> options){if(options==null)return Map.of();if(options.size()>10)throw invalid("任务选项过多");for(var e:options.entrySet())if(e.getKey()==null||!e.getKey().matches("[a-z][a-z0-9_]{0,63}")||e.getValue()==null||e.getValue().length()>512)throw invalid("任务选项无效");return Map.copyOf(options);}
    /** 同键异请求不覆盖原进度。 */
    private static FoundationJobContracts.JobView sameRequest(FoundationJobDO row,String digest){if(!digest.equals(row.getRequestDigest()))throw new ApiException(HttpStatus.CONFLICT,"job_idempotency_conflict","幂等键已用于其他请求",false);return view(row);}
    /** 数据库行锁和时钟验证写权后才允许副作用；未到续租窗口时无需无变化UPDATE。 */
    private void lease(String id,String owner){if(!mapper.guardLease(id,owner))throw new LeaseLost();}
    /** 主体绑定在事务内完成，平台不会跨读租户队列。 */
    private void bind(){var subject=access.currentSubject();mapper.bind(subject.tenantId()==null?"":subject.tenantId(),subject.platform());}
    /** 套餐只限制机构能力；平台队列保持明确私有范围。 */
    private void requireFeature(){if(!access.currentSubject().platform())features.require("foundation.jobs");}
    /** {@inheritDoc} */ @Override public String ownerKind(){return "foundation.job";}
    /** 结果下载继承任务读取与原领域动作权限；过期或不匹配关联拒绝，Agent共享不会影响此权限。 */
    @Override public void authorizeFile(String ownerId,String fileId){access.require("foundation.jobs.read");tx.executeWithoutResult(status->{bind();var job=mapper.find(ownerId);if(job==null||!fileId.equals(job.getResultFileId())||job.getExpiresAt()==null||job.getExpiresAt().isBefore(java.time.OffsetDateTime.now())||!List.of("succeeded","failed","cancelled").contains(job.getState()))throw new ApiException(HttpStatus.FORBIDDEN,"任务结果不可访问");requireOperation(handler(job.getHandler()),job.getOperation());});}
    /** 调度错误收尾只恢复已持久化作用域，不借助失效 JWT。 */
    private void bindPersisted(FoundationJobDO job){mapper.bind(job.getTenantId()==null?"":job.getTenantId().toString(),job.getTenantId()==null);}
    /** 错误/生命周期收尾恢复已持久化租户并在异常时还原；每条SQL的RLS拦截不能被单次set_config替代。 */
    private static void persistedScope(FoundationJobDO job,Runnable operation){String previous=TenantContext.getTenantId();try{if(job.getTenantId()==null)TenantContext.clear();else TenantContext.setTenantId(job.getTenantId().toString());operation.run();}finally{if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous);}}
    /** 当前作用域对象查找。 */
    private FoundationJobDO ready(String id){FoundationFileService.validateId(id);var row=mapper.lock(id);if(row==null)throw new ApiException(HttpStatus.NOT_FOUND,"管理任务不存在");return row;}
    /** 序列化固定领域材料，不记录任意执行代码。 */
    private String write(Object value){try{return json.writeValueAsString(value);}catch(java.io.IOException e){throw new IllegalStateException("管理任务材料序列化失败",e);}}
    /** 严格读取先前校验并保存的选项。 */
    private Map<String,String> readOptions(String value){try{return options(json.readValue(value,new TypeReference<Map<String,String>>(){}));}catch(java.io.IOException e){throw new IllegalStateException("管理任务选项损坏",e);}}
    /** 明确读取逐行错误结构。 */
    private List<FoundationJobContracts.RowError> readErrors(String value){try{return json.readValue(value,new TypeReference<List<FoundationJobContracts.RowError>>(){});}catch(java.io.IOException e){throw new IllegalStateException("管理任务错误材料损坏",e);}}
    /** 业务错误保留可纠正说明，技术异常不给出内部 SQL/路径/凭据。 */
    private static String safeError(RuntimeException failure){String message=failure instanceof ApiException?failure.getMessage():"任务处理失败，请联系管理员";return message.length()>500?message.substring(0,500):message;}
    /** 任务使用固定引用命名空间，不能被普通 HTTP 任意解绑。 */
    private static FoundationFileContracts.Reference reference(String id){return new FoundationFileContracts.Reference("foundation.job",id);}
    /** 公开投影不包含受理密钥、租约与选项正文。 */
    private static FoundationJobContracts.JobView view(FoundationJobDO row){return new FoundationJobContracts.JobView(row.getId(),row.getHandler(),row.getOperation(),row.getState(),row.getTotalRows(),row.getCompletedRows(),row.getErrorRows(),row.getErrorMessage(),row.getResultFileId(),row.getCreatedAt(),row.getExpiresAt());}
    /** 输入错误采用稳定原因。 */ private static ApiException invalid(String message){return new ApiException(HttpStatus.BAD_REQUEST,"job_input_invalid",message,false);}
    /** 私有控制异常用于停止过期工作者，不作为可重试领域行错误。 */
    private static final class LeaseLost extends RuntimeException { }
}
