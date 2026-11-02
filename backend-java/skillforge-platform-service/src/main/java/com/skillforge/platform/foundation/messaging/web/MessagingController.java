package com.skillforge.platform.foundation.messaging.web;

import com.skillforge.core.foundation.messaging.application.NotificationService;
import com.skillforge.core.foundation.messaging.contract.NotificationCommands;
import com.skillforge.core.foundation.messaging.contract.NotificationResults;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 平台消息固定路径API，所有动作在应用服务实时检查权限与同租户对象归属。 */
@RestController
@RequestMapping("/api/v1/admin/foundation/messaging")
public class MessagingController {
    private final NotificationService service;
    /** 注入生产消息服务，不允许HTTP直接写Mapper。 */
    public MessagingController(NotificationService service) { this.service=service; }
    /** 读取本人消息，无他人成员ID输入。 */
    @GetMapping("/inbox") public ApiResponse<NotificationResults.Page<NotificationResults.Inbox>> inbox(
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="30") int limit,@RequestParam(defaultValue="false") boolean unread) {
        host(); return ApiResponses.wrap(service.inbox(cursor,limit,unread));
    }
    /** 本人当前未读数量。 */
    @GetMapping("/unread") public ApiResponse<Long> unread() { host(); return ApiResponses.wrap(service.unread()); }
    /** 幂等设置本人消息已读。 */
    @PostMapping("/read") public ApiResponse<Void> read(@Valid @RequestBody MessagingRequests.Identity request) { host(); service.markRead(request.id()); return ApiResponses.wrap((Void)null); }
    /** 查询公告发布历史。 */
    @GetMapping("/announcements") public ApiResponse<NotificationResults.Page<NotificationResults.Announcement>> announcements(
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="30") int limit) { host(); return ApiResponses.wrap(service.announcements(cursor,limit)); }
    /** 冻结范围成员并原子发布公告和通知。 */
    @PostMapping("/announcements/publish") public ApiResponse<String> publish(@Valid @RequestBody MessagingRequests.Publish request) {
        host(); return ApiResponses.wrap(service.publish(new NotificationCommands.Publish(request.title(),request.body(),request.audience(),request.recipientIds(),request.email(),request.deduplicationKey())));
    }
    /** 撤回公告，已派发邮件历史保留真实状态。 */
    @PostMapping("/announcements/withdraw") public ApiResponse<Void> withdraw(@Valid @RequestBody MessagingRequests.Identity request) { host(); service.withdraw(request.id()); return ApiResponses.wrap((Void)null); }
    /** 当前模板定义，不返回任何受理变量。 */
    @GetMapping("/templates") public ApiResponse<List<NotificationResults.Template>> templates() { host(); return ApiResponses.wrap(service.templates()); }
    /** 完整发布新模板版本。 */
    @PostMapping("/templates/publish") public ApiResponse<String> template(@Valid @RequestBody MessagingRequests.Template request) {
        host(); return ApiResponses.wrap(service.publishTemplate(new NotificationCommands.Template(request.key(),request.subject(),request.body(),request.variableNames())));
    }
    /** 邮箱查询仅返回是否已配置凭据。 */
    @GetMapping("/mailbox") public ApiResponse<NotificationResults.Mailbox> mailbox() { host(); return ApiResponses.wrap(service.mailbox()); }
    /** 发布邮箱配置，密码只写不读。 */
    @PutMapping("/mailbox") public ApiResponse<Void> mailbox(@Valid @RequestBody MessagingRequests.Mailbox request) {
        host(); service.configureMailbox(new NotificationCommands.Mailbox(request.host(),request.port(),request.username(),request.password(),request.fromAddress(),request.tlsMode(),request.enabled())); return ApiResponses.wrap((Void)null);
    }
    /** 可靠投递元数据；accepted只表示SMTP接受。 */
    @GetMapping("/deliveries") public ApiResponse<NotificationResults.Page<NotificationResults.Delivery>> deliveries(
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="30") int limit) { host(); return ApiResponses.wrap(service.deliveries(cursor,limit)); }
    /** 确定未接受记录人工恢复，不允许unknown盲重试。 */
    @PostMapping("/deliveries/retry") public ApiResponse<Void> retry(@Valid @RequestBody MessagingRequests.Identity request) { host(); service.retry(request.id()); return ApiResponses.wrap((Void)null); }
    /** 结果未知必须先依据真实服务器记录核验。 */
    @PostMapping("/deliveries/reconcile") public ApiResponse<Void> reconcile(@Valid @RequestBody MessagingRequests.Reconcile request) { host(); service.reconcile(request.id(),request.status(),request.evidence()); return ApiResponses.wrap((Void)null); }
    /** 查询显式终态材料保留期，NULL表示关闭。 */
    @GetMapping("/retention") public ApiResponse<NotificationResults.Retention> retention(){host();return ApiResponses.wrap(service.historyRetention());}
    /** 当前范围管理员发布业务选择的保留期。 */
    @PutMapping("/retention") public ApiResponse<NotificationResults.Retention> retention(@Valid @RequestBody MessagingRequests.Retention request){host();return ApiResponses.wrap(service.setHistoryRetention(request.days()));}
    /** 调查保护阻止正文与尝试事实退休。 */
    @PostMapping("/protection") public ApiResponse<Void> protection(@Valid @RequestBody MessagingRequests.Protection request){host();service.protectMessage(request.id(),request.protect());return ApiResponses.wrap((Void)null);}
    /** 对入口账号域做显式校验，防止另一宿主token改变消息作用域。 */
    private void host() { service.requireHost(true); }
}
