package com.skillforge.core.foundation.jobs;

import com.skillforge.core.foundation.assets.FoundationFileService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 每宿主有限单工作者调度；跨进程并发由数据库租约保护，清理失败保留材料供重试。 */
public class FoundationJobScheduler {
    private static final Logger log=LoggerFactory.getLogger(FoundationJobScheduler.class);
    private final FoundationJobService jobs;
    private final FoundationFileService files;
    private final boolean platform;
    private final AtomicBoolean executing=new AtomicBoolean();
    /** 宿主身份只由应用部署名决定，不能来自 HTTP 参数。 */
    public FoundationJobScheduler(FoundationJobService jobs,FoundationFileService files,boolean platform){this.jobs=jobs;this.files=files;this.platform=platform;}
    /** 一次最多领取四项，避免无限占用宿主调度线程；新请求不代表立即完成。 */
    @Scheduled(fixedDelayString="${skillforge.foundation.jobs.poll-ms:3000}")
    public void poll(){if(!executing.compareAndSet(false,true))return;try{for(int i=0;i<4;i++)if(!jobs.runNext(platform))break;}catch(RuntimeException e){log.error("管理任务调度失败，等待下一次领取",e);}finally{executing.set(false);}}
    /** 七日终态释放后由文件引用保护清理；活跃、等待和结果未知任务不在此范围。 */
    @Scheduled(fixedDelayString="${skillforge.foundation.jobs.cleanup-ms:3600000}")
    public void cleanup(){try{jobs.cleanup(platform);files.cleanup();}catch(RuntimeException e){log.error("管理任务/公共文件清理失败，保留待重试",e);}}
}
