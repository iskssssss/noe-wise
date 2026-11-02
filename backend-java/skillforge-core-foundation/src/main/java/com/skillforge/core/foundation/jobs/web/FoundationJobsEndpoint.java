package com.skillforge.core.foundation.jobs.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.foundation.jobs.FoundationJobContracts;
import com.skillforge.core.foundation.jobs.FoundationJobService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** 双宿主管理任务协议，受理响应与最终执行状态保持区分。 */
public abstract class FoundationJobsEndpoint {
    private final FoundationJobService jobs;
    /** 服务所有者承载授权、幂等、租约与失败规则。 */ protected FoundationJobsEndpoint(FoundationJobService jobs){this.jobs=jobs;}
    /** 当前主体可用的真实注册消费者。 */
    @GetMapping("/handlers") public ApiResponse<List<FoundationJobContracts.HandlerView>> handlers(){return ApiResponses.wrap(jobs.handlers());}
    /** 当前作用域的管理任务分页。 */
    @GetMapping public ApiResponse<FoundationJobContracts.Page> list(@RequestParam(defaultValue="1") int page,@RequestParam(name="page_size",defaultValue="20") int size){return ApiResponses.wrap(jobs.list(page,size));}
    /** 已受理任务详情。 */
    @GetMapping("/detail") public ApiResponse<FoundationJobContracts.JobView> detail(@RequestParam(name="job_id") String id){return ApiResponses.wrap(jobs.detail(id));}
    /** 有限材料的逐行错误分页。 */
    @GetMapping("/errors") public ApiResponse<List<FoundationJobContracts.RowError>> errors(@RequestParam(name="job_id") String id,@RequestParam(defaultValue="1") int page,@RequestParam(name="page_size",defaultValue="20") int size){return ApiResponses.wrap(jobs.errors(id,page,size));}
    /** 预检完整 CSV，不执行领域写入。 */
    @PostMapping("/preflight") public ApiResponse<FoundationJobContracts.PreflightResult> preflight(@RequestBody FoundationJobContracts.Preflight command){return ApiResponses.wrap(jobs.preflight(command));}
    /** 受理后由可靠工作者执行，响应不能当作导入完成。 */
    @PostMapping("/submit") public ApiResponse<FoundationJobContracts.JobView> submit(@RequestBody FoundationJobContracts.Submit command){return ApiResponses.wrap(jobs.submit(command));}
    /** 在行边界取消，不回滚已经提交的业务行。 */
    @PostMapping("/cancel") public ApiResponse<FoundationJobContracts.JobView> cancel(@RequestBody JobId request){return ApiResponses.wrap(jobs.cancel(request.jobId()));}
    /** @param jobId UUID 管理任务身份 */ public record JobId(@JsonProperty("job_id") String jobId){ }
}
