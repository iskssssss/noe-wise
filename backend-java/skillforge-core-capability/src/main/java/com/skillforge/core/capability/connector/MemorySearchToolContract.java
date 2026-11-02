package com.skillforge.core.capability.connector;

/** search_memory 平台协议字段；模型目录与执行边界共用，归属身份不进入可改参数。 */
public final class MemorySearchToolContract {
    public static final String NAME = "search_memory";
    public static final String QUERY = "query";
    public static final String LIMIT = "limit";
    public static final String SUBJECT_END_USER_ID = "subject_end_user_id";
    public static final String SUCCESS = "success";
    public static final String ERROR = "error";
    public static final String QUERY_ID = "query_id";
    public static final String RETRIEVAL_EVENT_ID = "retrieval_event_id";
    public static final String AS_OF = "as_of";
    public static final String RETRIEVAL_MODE = "retrieval_mode";
    public static final String RETRIEVAL_STATUS = "retrieval_status";
    public static final String DEGRADATION_REASON = "degradation_reason";
    public static final String NOTICE = "notice";
    public static final String FACTS = "facts";
    public static final String FACT_ID = "fact_id";
    public static final String REVISION = "revision";
    public static final String SCOPE = "scope";
    public static final String TYPE = "type";
    public static final String SUBJECT = "subject";
    public static final String PREDICATE = "predicate";
    public static final String OBJECT_SCHEMA = "object_schema";
    public static final String OBJECT = "object";
    public static final String VALID_FROM = "valid_from";
    public static final String VALID_TO = "valid_to";
    public static final String EXPIRES_AT = "expires_at";

    private MemorySearchToolContract() { }
}
