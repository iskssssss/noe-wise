package com.skillforge.institution.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionDelegations;
import com.skillforge.runtime.api.InboundEnvelope;
import com.skillforge.runtime.api.RunTarget;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.session.spi.SessionDelegations.Entry;
import com.skillforge.runtime.session.spi.SessionDelegations.Wait;
import com.skillforge.runtime.session.SessionDelegationEngine;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** DAG 适配器复用 SessionLog 和 Run 存储；宿主仅提供当前格式的 JSON 编码。 */
@Configuration
public class TeamRuntimeConfiguration {
    @Bean
    SessionDelegationEngine sessionDelegationEngine(
            com.skillforge.runtime.session.spi.SessionDelegations.Store store,
            com.skillforge.runtime.session.spi.SessionDelegations.Host host,
            Clock clock, org.springframework.beans.factory.ObjectProvider<com.skillforge.runtime.api.port.PlanPort> plans) {
        return new SessionDelegationEngine(store, host, clock);
    }

    @Bean
    JdbcDagWorkPort dagWorkPort(DataSource dataSource, ObjectMapper mapper) {
        var json = com.skillforge.institution.runtime.SessionSubjectJson.configure(mapper.copy()).setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        return new JdbcDagWorkPort(dataSource, new JdbcDagWorkPort.Codec() {
            public String encode(DagWork work) { return write(work); }
            public DagWork decode(String payload) {
                try { return json.readValue(payload, DagWork.class); }
                catch (java.io.IOException failure) { throw new IllegalArgumentException("invalid DAG snapshot", failure); }
            }
            public String encodeBinding(DagWork.Binding binding) { return write(binding); }
            private String write(Object value) {
                try { return json.writeValueAsString(value); }
                catch (java.io.IOException failure) { throw new IllegalArgumentException("invalid DAG snapshot", failure); }
            }
        });
    }

    @Bean
    JdbcSessionDelegations sessionDelegations(
            DataSource dataSource, ObjectMapper mapper, org.springframework.beans.factory.ObjectProvider<com.skillforge.runtime.api.port.PlanPort> plans) {
        var json = com.skillforge.institution.runtime.SessionSubjectJson.configure(mapper.copy())
                .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .addMixIn(RunTarget.class, RunTargetJson.class);
        return new JdbcSessionDelegations(dataSource,
                new JdbcSessionDelegations.Codec() {
                    @Override
                    public String encodeEntry(Entry entry) {
                        return write(entry);
                    }

                    @Override
                    public Entry decodeEntry(String snapshot) {
                        return read(snapshot, Entry.class);
                    }

                    @Override
                    public String encodeWait(Wait wait) {
                        return write(wait);
                    }

                    @Override
                    public Wait decodeWait(String snapshot) {
                        return read(snapshot, Wait.class);
                    }







                    private String write(Object value) {
                        try {
                            return json.writeValueAsString(value);
                        } catch (java.io.IOException failure) {
                            throw new IllegalArgumentException(
                                    "invalid delegation snapshot", failure);
                        }
                    }

                    private <T> T read(String value, Class<T> type) {
                        try {
                            return json.readValue(value, type);
                        } catch (java.io.IOException failure) {
                            throw new IllegalArgumentException(
                                    "invalid delegation snapshot", failure);
                        }
                    }
                });
    }

    @com.fasterxml.jackson.annotation.JsonTypeInfo(
            use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.DEDUCTION)
    @com.fasterxml.jackson.annotation.JsonSubTypes({
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(
                RunTarget.NewRun.class),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(
                RunTarget.ExistingRun.class)
    })
    private interface RunTargetJson {}
}
