package com.skillforge.core.foundation.messaging.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 消息领域XML持久化入口；所有对象查询同时约束作用域，租约更新必须CAS。 */
@Mapper
public interface MessagingMapper {
    /** 同作用域写入串行锁；只允许在应用事务中调用。 */
    String lockScope(@Param("scope") String scope);
    /** 查找同作用域通知幂等键。 */
    MessageRow messageByKey(@Param("scope") String scope, @Param("key") String key);
    /** 只读取同作用域通知。 */
    MessageRow message(@Param("scope") String scope, @Param("id") String id);
    /** 与业务事务共同追加冻结通知。 */
    int insertMessage(MessageRow row);
    /** 列出成员自身可见且未撤回公告的站内信。 */
    List<MessageRow> inbox(@Param("scope") String scope, @Param("member") String member,
            @Param("cursor") String cursor, @Param("limit") int limit, @Param("unread") boolean unread);
    /** 统计自身未读，已撤回公告不计入。 */
    long unread(@Param("scope") String scope, @Param("member") String member);
    /** 仅自身消息幂等标记已读。 */
    int markRead(@Param("scope") String scope, @Param("member") String member, @Param("id") String id);
    /** 查找发布幂等键。 */
    AnnouncementRow announcementByKey(@Param("scope") String scope, @Param("key") String key);
    /** 发送前读取本领域公告发布人，用于实时撤权检查。 */
    AnnouncementRow announcement(@Param("scope") String scope, @Param("id") String id);
    /** 冻结公告正文和收件人总数。 */
    int insertAnnouncement(AnnouncementRow row);
    /** 同作用域公告页。 */
    List<AnnouncementRow> announcements(@Param("scope") String scope, @Param("cursor") String cursor, @Param("limit") int limit);
    /** 仅published转withdrawn。 */
    int withdraw(@Param("scope") String scope, @Param("id") String id);
    /** 撤回仅取消未派发的outbox。 */
    int cancelAnnouncement(@Param("scope") String scope, @Param("id") String id);
    /** 当前同作用域模板。 */
    TemplateRow template(@Param("scope") String scope, @Param("key") String key);
    /** 当前模板清单。 */
    List<TemplateRow> templates(@Param("scope") String scope);
    /** 推进模板指针前取消旧指针。 */
    int retireTemplate(@Param("scope") String scope, @Param("key") String key);
    /** 追加不可变模板版本。 */
    int insertTemplate(TemplateRow row);
    /** 当前邮箱配置；只在配置和发送边界使用。 */
    MailboxRow currentMailbox(@Param("scope") String scope);
    /** 按冻结版本读取邮箱。 */
    MailboxRow mailbox(@Param("scope") String scope, @Param("id") String id);
    /** 推进邮箱指针前取消旧指针。 */
    int retireMailbox(@Param("scope") String scope);
    /** 追加不可变邮箱/凭据版本。 */
    int insertMailbox(MailboxRow row);
    /** 与通知同事务追加可靠待发送记录。 */
    int insertDelivery(DeliveryRow row);
    /** 受控后台只枚举待处理作用域。 */
    List<String> dueScopes();
    /** 唤醒无正文工作目录，新投递和显式恢复同事务调用。 */
    int readyScope(@Param("scope") String scope);
    /** 失效dispatching转unknown，leased回到pending；保留安全失败码。 */
    int recoverExpired(@Param("scope") String scope);
    /** SKIP LOCKED原子领取单条记录；新token提供唯一写权。 */
    DeliveryRow claim(@Param("scope") String scope, @Param("token") String token, @Param("leaseSeconds") int leaseSeconds);
    /** 外部派发前提交dispatching，旧租约不能派发。 */
    int dispatch(@Param("scope") String scope, @Param("id") String id, @Param("token") String token);
    /** 租约持有者记录确定或不确定结局。 */
    int finish(@Param("scope") String scope, @Param("id") String id, @Param("token") String token,
            @Param("status") String status, @Param("code") String code);
    /** 追加尝试事实，不含秘密。 */
    int attempt(@Param("id") String id, @Param("scope") String scope, @Param("tenant") String tenant,
            @Param("delivery") String delivery, @Param("token") String token, @Param("status") String status,
            @Param("code") String code, @Param("evidence") String evidence);
    /** 管理页仅返回投递元数据。 */
    List<DeliveryRow> deliveries(@Param("scope") String scope, @Param("cursor") String cursor, @Param("limit") int limit);
    /** 锁定同作用域投递以执行人工处置。 */
    DeliveryRow lockDelivery(@Param("scope") String scope, @Param("id") String id);
    /** 仅blocked/rejected重新受理；unknown绝不进入该语句。 */
    int retry(@Param("scope") String scope, @Param("id") String id, @Param("mailbox") String mailbox);
    /** unknown只允许带核验证据设置accepted或rejected。 */
    int reconcile(@Param("scope") String scope, @Param("id") String id, @Param("status") String status);
    /** 首次领域受理登记已批准30日策略；同scope仅INSERT一次，不覆盖显式配置。 */ int ensureHistoryRetention(@Param("scope") String scope);
    /** 已批准默认30日，NULL只来自管理员显式关闭。 */ Integer historyRetention(@Param("scope") String scope);
    /** 发布显式期限，幂等策略不产生无变化UPDATE。 */ int setHistoryRetention(@Param("scope") String scope,@Param("days") Integer days);
    /** 调查保护锁定所属通知，返回1为变化、2为原值、0为不存在或已退休。 */ int protectMessage(@Param("scope") String scope,@Param("id") String id,@Param("protect") boolean protect);
    /** 公平枚举已启用回收作用域，不跨租户返回正文。 */ List<String> historyCleanupScopes();
    /** 终态且无调查保护的尝试最多一千条，可靠状态和UNKNOWN不清理。 */ int pruneAttempts(@Param("scope") String scope);
    /** 只退休已无尝试、邮件已接受/取消或无邮件的已读/撤回通知，未读及幂等收件关系仍受保护。 */ int retireMessages(@Param("scope") String scope);
    /** 所有收件关系均退休后才能释放共享公告正文，幂等摘要和格式身份保留。 */ int retireAnnouncements(@Param("scope") String scope);
    /** 终态墓碑不再持有历史SMTP凭据，释放至多100条精确版本引用。 */ int releaseRetiredMailboxReferences(@Param("scope") String scope);
    /** 无活动引用且非当前的模板/邮箱版本各至多100条，UNKNOWN仍通过原引用保护。 */ int pruneRetiredTemplates(@Param("scope") String scope);
    /** 回收非当前、无活动引用的SMTP版本及其密文凭据。 */ int pruneRetiredMailboxes(@Param("scope") String scope);
}
