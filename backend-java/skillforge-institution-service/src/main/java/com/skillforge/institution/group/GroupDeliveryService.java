package com.skillforge.institution.group;

import static com.skillforge.institution.group.GroupControlTypes.*;
import static com.skillforge.institution.group.GroupControlService.*;
import com.skillforge.institution.group.GroupControlContracts.Release;
import com.skillforge.institution.group.GroupControlContracts.ReleaseReceipt;
import com.skillforge.institution.group.GroupDeliveryContracts.Qualify;
import com.skillforge.institution.group.GroupDeliveryContracts.Qualification;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 正式消息的中央发送资格与精确放行；不创建发送执行器，不改本地DISPATCHING/UNKNOWN账本。 */
@Service
public class GroupDeliveryService {
    private final GroupControlService controls;
    private final GroupSenderService senders;
    private final GroupControlMapper mapper;
    private final ObjectProvider<GroupDeliveryFinalMessages> finals;
    public GroupDeliveryService(GroupControlService controls,GroupSenderService senders,GroupControlMapper mapper,ObjectProvider<GroupDeliveryFinalMessages> finals) {
        this.controls=controls;this.senders=senders;this.mapper=mapper;this.finals=finals;
    }

    /** 每次重新核中央当前资格；调用方随后仍须在共享本地ledger事务中核门禁并取得实际DISPATCHING。 */
    @Transactional(rollbackFor=Exception.class)
    public Qualification qualify(Qualify command) {
        Objects.requireNonNull(command);var access=controls.access("groups.controls.ack",command.groupBindingId());var group=controls.lock(access);controls.requireOpen(access);
        positive(command.generation());positive(command.messageVersion());
        var endpoint=senders.bound(access,command.endpointId(),command.generation(),null);var endpoints=controls.endpoints(access);
        if(!EndpointStatus.ACTIVE.name().equals(endpoint.status())||endpoints.stream().anyMatch(value->value.endpointId().equals(endpoint.endpointId())&&value.generation()>endpoint.generation()))throw conflict("发送端点当前世代尚未取得资格或已经退役");
        var message=message(access,command.runId(),command.messageId(),command.messageVersion());var root=controls.root(mapper,message.identity());
        if(root==null||!root.integrationId().equals(access.integration())||root.groupBindingId()!=access.group())throw denied("正式消息缺少同群原根触发资格");
        var delivery=bind(access,command.deliveryId(),message,root);
        var boundaries=uncovered(access,root,command.runId(),delivery.deliveryId());var states=controls.states(access);
        boolean paused=states.stream().anyMatch(state->state.paused()&&Scope.SEND.wire.equals(state.scope())&&matches(state.targetRef(),command.runId(),root.runId()));
        long requiredRevision=states.stream().filter(state->Scope.SEND.wire.equals(state.scope())&&matches(state.targetRef(),command.runId(),root.runId())).mapToLong(GroupControlMapper.State::revision).max().orElse(0);
        DeliveryReason reason=paused?DeliveryReason.CONTROL_PAUSED:!boundaries.isEmpty()?DeliveryReason.ROOT_BOUNDARY_BLOCKED:endpoint.appliedRevision()<requiredRevision?DeliveryReason.CONTROL_NOT_APPLIED:DeliveryReason.ELIGIBLE;
        return new Qualification(reason==DeliveryReason.ELIGIBLE,reason.name(),delivery.deliveryId(),command.groupBindingId(),command.runId(),root.triggerId(),root.triggerSequence(),message.messageId(),message.messageVersion(),message.content(),delivery.contentDigest(),group.revision(),boundaries);
    }

    /** 只放行已存在的准确formal消息和明确边界，保留原delivery身份，更晚暂停仍会阻止它。 */
    @Transactional(rollbackFor=Exception.class)
    public ReleaseReceipt release(Release command,String key) {
        Objects.requireNonNull(command);var access=controls.access("groups.controls.release",command.groupBindingId());var group=controls.lock(access);controls.requireOpen(access);requireRevision(command.expectedRevision());
        String fingerprint=controls.fingerprint(command);var prior=controls.prior(access,Operation.RELEASE,key,fingerprint,ReleaseReceipt.class);if(prior!=null)return prior;
        if(group.revision()!=command.expectedRevision())throw conflict("消息放行控制CAS已过时");
        if(command.messages()==null||command.messages().isEmpty()||command.messages().size()>100||command.blockedBoundaryRefs()==null||command.blockedBoundaryRefs().isEmpty()||command.blockedBoundaryRefs().size()>100)throw bad("放行须指定具体消息集合及阻止边界");
        var requested=new TreeSet<>(command.blockedBoundaryRefs());if(requested.size()!=command.blockedBoundaryRefs().size())throw bad("阻止边界不能重复");
        var boundaries=controls.boundaries(access);var seen=new HashSet<String>();String releaseId="release:"+hash(access.principal()+"\0"+text(key,256));
        for(var item:command.messages()) {
            positive(item.messageVersion());text(item.deliveryId(),128);if(!seen.add(item.deliveryId()))throw bad("放行消息delivery重复");
            var message=message(access,command.runId(),item.messageId(),item.messageVersion());var root=controls.root(mapper,message.identity());
            if(root==null||root.groupBindingId()!=access.group()||!root.integrationId().equals(access.integration()))throw denied("放行消息缺少原根触发资格");
            if(controls.states(access).stream().anyMatch(state->state.paused()&&Scope.SEND.wire.equals(state.scope())&&matches(state.targetRef(),command.runId(),root.runId())))throw conflict("仍处于发送暂停，须先独立恢复再放行具体消息");
            for(String reference:requested) {
                var boundary=boundaries.stream().filter(value->value.boundaryRef().equals(reference)).findFirst().orElseThrow(()->conflict("指定阻止边界不存在"));
                if(boundary.throughSequence()==null||!matches(boundary.targetRef(),command.runId(),root.runId())||root.triggerSequence()>boundary.throughSequence())throw conflict("放行边界不覆盖该原消息或尚未恢复");
            }
            var delivery=bind(access,item.deliveryId(),message,root);
            mapper.release(new GroupControlMapper.ReleaseRow(access.tenant(),access.integration(),access.group(),releaseId,delivery.deliveryId(),group.revision(),access.principal()));
            for(String boundary:requested)mapper.releaseBoundary(new GroupControlMapper.ReleaseBoundary(access.tenant(),access.integration(),access.group(),releaseId,delivery.deliveryId(),boundary));
        }
        var result=new ReleaseReceipt(releaseId,command.groupBindingId(),group.revision(),List.copyOf(command.messages()),List.copyOf(requested));
        controls.saveRequest(access,Operation.RELEASE,key,fingerprint,result);controls.record(access,"release",releaseId,group.revision());return result;
    }

    /** 所有正式结果语义归唯一公开投影；构造期不解析该provider，避免Runtime依赖循环。 */
    private GroupDeliveryFinalMessages.FinalMessage message(GroupControlService.Access access,String run,String message,long version) {
        text(run,128);text(message,256);var result=finals.getObject().requireFinal(Long.toString(access.tenant()),access.integration(),Long.toString(access.group()),run,message,version);
        if(!result.identity().runRef().runId().equals(run)||!result.messageId().equals(message)||result.messageVersion()!=version)throw new IllegalStateException("group_formal_final_identity_mismatch");return result;
    }
    /** 中央仅保存稳定消息身份；不是实际外发/UNKNOWN账本，也不借换代产生第二个delivery。 */
    private GroupControlMapper.Delivery bind(GroupControlService.Access access,String id,GroupDeliveryFinalMessages.FinalMessage message,GroupControlMapper.Eligibility root) {
        var expected=new GroupControlMapper.Delivery(access.tenant(),access.integration(),access.group(),text(id,128),message.identity().runRef().runId(),message.messageId(),message.messageVersion(),root.triggerId(),hash(message.content()));
        var prior=mapper.delivery(access.tenant(),access.integration(),access.group(),id);
        if(prior!=null){if(!prior.equals(expected))throw conflict("原delivery不能换绑消息、版本或正文");return prior;}
        var sameMessage=mapper.messageDelivery(access.tenant(),access.integration(),access.group(),expected.runId(),expected.messageId(),expected.messageVersion());
        if(sameMessage!=null)throw conflict("原正式消息已有稳定delivery，不能换身份绕过去重");
        if(mapper.insertDelivery(expected)!=1)throw new IllegalStateException("group_delivery_identity_commit_failed");return expected;
    }
    /** 保留所有旧来源边界，只有覆盖准确delivery的release能解除对应项。 */
    private List<String> uncovered(GroupControlService.Access access,GroupControlMapper.Eligibility root,String run,String delivery) {
        var released=new HashSet<>(mapper.releases(access.tenant(),access.integration(),access.group(),delivery));
        return controls.boundaries(access).stream().filter(boundary->matches(boundary.targetRef(),run,root.runId())&&(boundary.throughSequence()==null||root.triggerSequence()<=boundary.throughSequence())&&!released.contains(boundary.boundaryRef())).map(GroupControlMapper.Boundary::boundaryRef).sorted().toList();
    }
    private static void positive(Long value){if(value==null||value<1)throw bad("消息版本或端点世代必须显式提供正整数");}
}
