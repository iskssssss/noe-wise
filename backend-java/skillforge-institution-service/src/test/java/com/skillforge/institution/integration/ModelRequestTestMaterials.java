package com.skillforge.institution.integration;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** 集成断言按本次请求manifest的精确引用批读原材料，不从当前配置拼装历史。 */
final class ModelRequestTestMaterials {
    private ModelRequestTestMaterials() { }
    static AgentExecutionCodec.ModelCallRequest decode(AgentExecutionCodec codec, TypedPayload request,
            JdbcTemplate jdbc, String tenant) {
        return codec.decodeModelRequest(request, references -> {
            var parameters = new ArrayList<Object>(); parameters.add(tenant);
            parameters.addAll(references.stream().map(ResultRef::value).toList());
            var rows = AbstractIntegrationTest.queryRows(jdbc, tenant,
                    "SELECT reference,schema_id,canonical_json FROM agent_runtime_execution_materials WHERE tenant_id=? AND reference IN ("
                            + String.join(",", java.util.Collections.nCopies(references.size(), "?")) + ")", parameters.toArray());
            var actual = new HashMap<ResultRef, ExecutionStore.Material>();
            for (var row : rows) {
                var reference = new ResultRef(row.get("reference").toString());
                actual.put(reference, new ExecutionStore.Material(reference,
                        new TypedPayload(row.get("schema_id").toString(), row.get("canonical_json").toString())));
            }
            return references.stream().map(reference -> Optional.ofNullable(actual.get(reference))).toList();
        });
    }
    static List<Optional<ExecutionStore.Material>> load(java.sql.Connection connection, String tenant, List<ResultRef> references) {
        var actual = new HashMap<ResultRef, ExecutionStore.Material>();
        String sql = "SELECT reference,schema_id,canonical_json FROM agent_runtime_execution_materials WHERE tenant_id=? AND reference IN ("
                + String.join(",", java.util.Collections.nCopies(references.size(), "?")) + ")";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            for (int index = 0; index < references.size(); index++) statement.setString(index + 2, references.get(index).value());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var reference = new ResultRef(rows.getString("reference"));
                    actual.put(reference, new ExecutionStore.Material(reference, new TypedPayload(rows.getString("schema_id"), rows.getString("canonical_json"))));
                }
            }
        } catch (java.sql.SQLException failure) { throw new IllegalStateException("model_request_material_read_failed", failure); }
        return references.stream().map(reference -> Optional.ofNullable(actual.get(reference))).toList();
    }
}
