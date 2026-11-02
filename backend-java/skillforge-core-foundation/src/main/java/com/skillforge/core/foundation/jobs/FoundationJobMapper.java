package com.skillforge.core.foundation.jobs;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 管理任务域内 SQL；领取、续租、回执与终态全部受预期租约保护。 */
@Mapper
public interface FoundationJobMapper {
    /** 绑定可信主体。 */ void bind(@Param("tenantId") String tenantId,@Param("platform") boolean platform);
    /** 调度扫描按宿主类型限制，普通请求无法获得此入口。 */ void bindDispatch(@Param("platform") boolean platform);
    /** 同键受理不覆盖旧请求。 */ int insert(FoundationJobDO job);
    /** 当前作用域锁定幂等受理结果。 */ FoundationJobDO byKey(@Param("key") String key);
    /** 当前作用域锁定任务。 */ FoundationJobDO lock(@Param("id") String id);
    /** 只读结果授权不获取任务写锁，避免与引用收尾的锁顺序冲突。 */ FoundationJobDO find(@Param("id") String id);
    /** 管理列表。 */ List<FoundationJobDO> list(@Param("offset") long offset,@Param("size") int size);
    /** 作用域计数。 */ long count();
    /** 原子领取并返回租约，重启仅允许过期租约重新领取。 */ FoundationJobDO claim(@Param("owner") String owner);
    /** 每行锁定并验证数据库时钟下的租约，只在剩余不足一分钟时续租；false 禁止副作用。 */ boolean guardLease(@Param("id") String id,@Param("owner") String owner);
    /** 已提交行不再重复应用。 */ boolean hasReceipt(@Param("id") String id,@Param("row") int row);
    /** 单行恢复身份压入256行位图，与领域写入同事务提交；成功不新增逐行正文。 */ int receipt(@Param("id") String id,@Param("row") int row);
    /** 仅错误行保存可纠正错误，和位图回执同事务提交。 */ int rowError(@Param("id") String id,@Param("row") int row,@Param("errorsJson") String errorsJson);
    /** 有界块汇总恢复进度，仅有变化才更新；包括取消边界。 */ int progress(@Param("id") String id,@Param("owner") String owner);
    /** 租约所有者唯一提交终态。 */ int finish(@Param("id") String id,@Param("owner") String owner,@Param("state") String state,@Param("message") String message,@Param("resultFileId") String resultFileId,@Param("total") int total);
    /** 取消 queued 或请求 running 在下一行边界停止。 */ int cancel(@Param("id") String id);
    /** 所属任务行错误。 */ List<String> errors(@Param("id") String id,@Param("offset") long offset,@Param("size") int size);
    /** 过期终态扫描，不包含运行与结果未知任务。 */ List<FoundationJobDO> expired(@Param("size") int size);
    /** 过期任务墓碑保留幂等摘要，但释放材料引用和错误正文。 */ int expire(@Param("id") String id);
    /** 删除过期行明细。 */ int deleteReceipts(@Param("id") String id);
    /** 删除同一终态的紧凑恢复身份。 */ int deleteReceiptBlocks(@Param("id") String id);
}
