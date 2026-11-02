package com.skillforge.runtime.adapter.jdbc.fixture;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.RunRequester;

/** 仅供 JDBC 测试自有 JSON 快照 codec 使用的正式 SessionSubject 分支声明。 */
public final class SessionSubjectJsonFixture {
    private SessionSubjectJsonFixture() { }

    /** 配置夹具自己的 mapper；缺失或未知 kind 必须拒绝，不默认成个人归属。 */
    public static ObjectMapper configure(ObjectMapper mapper) {
        return mapper.addMixIn(SessionSubject.class, Subject.class).addMixIn(RunRequester.class, Requester.class);
    }

    /** 与宿主 JSON 边界一致的封闭鉴别字段，API 本身不依赖 Jackson。 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = SessionSubject.User.class, name = "USER"),
        @JsonSubTypes.Type(value = SessionSubject.Group.class, name = "GROUP")
    })
    private abstract static class Subject { }
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = RunRequester.EndUser.class, name = "END_USER"),
        @JsonSubTypes.Type(value = RunRequester.System.class, name = "SYSTEM")
    })
    private abstract static class Requester { }

}
