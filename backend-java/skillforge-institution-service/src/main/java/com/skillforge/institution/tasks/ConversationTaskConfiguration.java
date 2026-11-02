package com.skillforge.institution.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.jdbc.session.JdbcConversationTaskPort;
import com.skillforge.runtime.api.port.ConversationTask;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ConversationTaskPort;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 宿主提供 JSON 编码；JDBC adapter 不依赖 Spring 或 Jackson。 */
@Configuration
public class ConversationTaskConfiguration {
    /** 内部任务协议固定驼峰字段，不能继承 HTTP DTO 的蛇形命名策略。 */
    static ObjectMapper taskJson(ObjectMapper json) {
        return com.skillforge.institution.runtime.SessionSubjectJson.configure(json.copy()).setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.LOWER_CAMEL_CASE);
    }

    @Bean
    ConversationTaskPort conversationTaskPort(DataSource dataSource, ObjectMapper json) {
        var taskJson = taskJson(json);
        return new JdbcConversationTaskPort(dataSource, new JdbcConversationTaskPort.Codec() {
            public TypedPayload encode(ConversationTask task) {
                try { return new TypedPayload(ConversationTaskCoordinator.SNAPSHOT_SCHEMA, taskJson.writeValueAsString(task)); }
                catch (Exception failure) { throw new IllegalArgumentException("task_encode_failed", failure); }
            }
            public ConversationTask decode(TypedPayload payload) {
                if (!ConversationTaskCoordinator.SNAPSHOT_SCHEMA.equals(payload.schemaId()))
                    throw new IllegalArgumentException("task_snapshot_schema_invalid");
                try { return taskJson.readValue(payload.canonicalJson(), ConversationTask.class); }
                catch (Exception failure) { throw new IllegalArgumentException("task_decode_failed", failure); }
            }
        });
    }
}
