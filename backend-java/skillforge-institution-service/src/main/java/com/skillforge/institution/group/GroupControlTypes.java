package com.skillforge.institution.group;

import java.util.Arrays;

/** 群控制内部状态与固定协议标识的唯一Java定义，String只保留在HTTP/SQL边界。 */
final class GroupControlTypes {
    static final String GROUP_TARGET="*", INITIAL_COMMAND="initial", RUNTIME="runtime", OUTBOX="outbox", MISSING_SENDER="unregistered_sender", ORIGIN_PREFIX="group-trigger:";
    private GroupControlTypes() { }
    /** 独立的自动执行、业务写、外发与提取门禁。 */
    enum Scope {
        EXECUTION("execution"),BUSINESS_WRITE("business_write"),SEND("send"),MEMORY_EXTRACTION("memory_extraction");
        final String wire; Scope(String wire){this.wire=wire;}
        static Scope parse(String wire){return Arrays.stream(values()).filter(value->value.wire.equals(wire)).findFirst().orElseThrow(()->GroupControlService.bad("控制范围非法"));}
    }
    /** 期望状态不等于传播已生效。 */
    enum Desired { PAUSED("paused"),RUNNING("running"); final String wire; Desired(String wire){this.wire=wire;}
        static Desired parse(String wire){return Arrays.stream(values()).filter(value->value.wire.equals(wire)).findFirst().orElseThrow(()->GroupControlService.bad("期望状态非法"));} }
    /** 单个命令的不可变受理事实。 */
    enum Result { ACCEPTED("accepted"),REJECTED("rejected"),TERMINAL_NOT_ACCEPTED("terminal_not_accepted"); final String wire; Result(String wire){this.wire=wire;} }
    /** 查询的当前传播状态，不能覆盖POST原回执。 */
    enum Status { ACCEPTED,PARTIAL,APPLIED,SUPERSEDED,REJECTED,TERMINAL_NOT_ACCEPTED }
    /** REGISTERING和RETIRING均禁止新发送资格。 */
    enum EndpointStatus { REGISTERING,ACTIVE,RETIRING,RETIRED }
    /** 持久ACK不构成持续连通证明；未装配实时观察时只能报告UNKNOWN。 */
    enum ConnectionState { UNKNOWN }
    /** 实际资格查询的稳定拒绝原因。 */
    enum DeliveryReason { ELIGIBLE,CONTROL_PAUSED,ROOT_BOUNDARY_BLOCKED,ENDPOINT_NOT_READY,CONTROL_NOT_APPLIED }
    /** 独立幂等管理动作，不与权威命令身份混用。 */
    enum Operation { TERMINALIZE,RELEASE,REGISTER,RETIRE,ACK }
}
