package com.skillforge.institution.runtime;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.PrincipalRef;

/** 宿主内部快照显式编码归属分支；Runtime API 保持不依赖 Jackson。 */
public final class SessionSubjectJson {
    private SessionSubjectJson() { }

    /** 配置调用方自己的 mapper，缺少或未知 kind 不降级为个人归属。 */
    public static ObjectMapper configure(ObjectMapper mapper) {
        return mapper.addMixIn(SessionSubject.class, Subject.class)
                .addMixIn(RunRequester.class, Requester.class)
                .addMixIn(PrincipalRef.class, Principal.class);
    }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME,property="kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value=SessionSubject.User.class,name="USER"),
        @JsonSubTypes.Type(value=SessionSubject.Group.class,name="GROUP")})
    private abstract static class Subject { }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME,property="kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value=RunRequester.EndUser.class,name="END_USER"),
        @JsonSubTypes.Type(value=RunRequester.System.class,name="SYSTEM")})
    private abstract static class Requester { }

    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME,property="kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value=PrincipalRef.ServicePrincipal.class,name="SERVICE"),
        @JsonSubTypes.Type(value=PrincipalRef.SystemPrincipal.class,name="SYSTEM"),
        @JsonSubTypes.Type(value=PrincipalRef.EndUserPrincipal.class,name="END_USER")})
    private abstract static class Principal { }
}
