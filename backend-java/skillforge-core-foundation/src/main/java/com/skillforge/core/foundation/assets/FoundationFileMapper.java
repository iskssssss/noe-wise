package com.skillforge.core.foundation.assets;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 文件和引用的域内持久化，所有普通操作均在已绑定主体的事务中执行。 */
@Mapper
public interface FoundationFileMapper {
    /** 设置事务级 RLS 身份。 */ void bind(@Param("tenantId") String tenantId, @Param("platform") boolean platform);
    /** 保存已完成落盘的元数据。 */ int insert(FoundationFileDO file);
    /** 锁定文件防止引用和删除竞争。 */ FoundationFileDO lock(@Param("id") String id);
    /** 分页读取当前作用域。 */ List<FoundationFileDO> list(@Param("offset") long offset, @Param("size") int size);
    /** 统计当前作用域。 */ long count();
    /** 幂等添加同租户引用。 */ int attach(@Param("id") String id, @Param("kind") String kind, @Param("owner") String owner);
    /** 删除所属业务已解除的引用。 */ int detach(@Param("id") String id, @Param("kind") String kind, @Param("owner") String owner);
    /** 将零引用文件标记为可靠删除意图。 */ int markDeleting(@Param("id") String id);
    /** 完成删除收尾。 */ int markDeleted(@Param("id") String id);
    /** 后台只读取需要清理的元数据；调度上下文不向 HTTP 暴露。 */ List<FoundationFileDO> cleanupCandidates(@Param("olderThan") java.time.OffsetDateTime olderThan, @Param("size") int size);
    /** 开启调度元数据扫描身份。 */ void bindMaintenance();
    /** 元数据受理前崩溃的物理对象检查；只读扫描不获取写锁。 */ boolean exists(@Param("id") String id);
    /** 受控生成结果的真实业务引用。 */ List<FoundationFileContracts.Reference> references(@Param("id") String id);
    /** 任务所有者确认终态过期后，释放其固定命名空间引用。 */ int releaseJobReferences(@Param("jobId") String jobId);
}
