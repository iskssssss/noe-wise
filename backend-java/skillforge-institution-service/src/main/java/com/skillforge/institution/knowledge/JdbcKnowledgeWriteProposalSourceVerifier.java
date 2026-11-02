package com.skillforge.institution.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposal;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposalSourceVerifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import com.skillforge.institution.knowledge.JdbcKnowledgeWriteProposalSourceMapper.Observation;
import org.springframework.stereotype.Component;

/** 用不可变 Runtime 结果和 SessionLog 证明提案来源，而不是信任请求自述。 */
@Component
public final class JdbcKnowledgeWriteProposalSourceVerifier
        implements KnowledgeWriteProposalSourceVerifier {
    private static final Logger log = LoggerFactory.getLogger(JdbcKnowledgeWriteProposalSourceVerifier.class);
    private static final Set<String> OBSERVATIONS = Set.of(
            "inbound.message", "agent.tool-result", "agent.workflow-result");

    private final JdbcKnowledgeWriteProposalSourceMapper mapper;
    private final ObjectMapper json;

    public JdbcKnowledgeWriteProposalSourceVerifier(JdbcKnowledgeWriteProposalSourceMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public void verify(String tenantId, KnowledgeWriteProposal proposal) {
        log.debug("知识写回来源校验开始 tenant={}", tenantId);
        Map<?, ?> source = (Map<?, ?>) proposal.payload().get("source");
        String kind = text(source, "kind");
        if ("evidence_ref".equals(kind)) {
            verifyEvidence(tenantId, proposal, source);
        } else if ("observation".equals(kind)) {
            verifyObservation(tenantId, proposal, source);
        } else {
            throw invalid("不支持的写回来源类型");
        }
        log.debug("知识写回来源校验完成 tenant={} kind={}", tenantId, kind);
    }

    private void verifyEvidence(String tenantId, KnowledgeWriteProposal proposal, Map<?, ?> source) {
        long version = positiveLong(source, "version");
        List<String> itemIds = texts(source.get("item_ids"), "item_ids");
        List<String> results = mapper.bundles(tenantId, proposal.bundleId());
        if (results.size() != 1) throw invalid("引用的 EvidenceBundle 不存在或不唯一");
        JsonNode bundle = parse(results.getFirst());
        if (bundle.path("version").asLong(-1) != version) {
            throw invalid("EvidenceRef version 与已提交 bundle 不一致");
        }
        Set<String> committedItems = values(bundle.path("items"), "itemId");
        if (!committedItems.containsAll(itemIds)) {
            throw invalid("EvidenceRef 包含未提交的 itemId");
        }
        List<String> events = mapper.eventPayloads(tenantId, proposal.sourceEventId());
        if (events.size() != 1 || !containsEvidenceRef(parse(events.getFirst()),
                proposal.bundleId(), version, itemIds)) {
            throw invalid("sourceEventId 未提交对应 EvidenceRef");
        }
    }

    private void verifyObservation(String tenantId, KnowledgeWriteProposal proposal, Map<?, ?> source) {
        String declaredType = text(source, "observation_type");
        if (!OBSERVATIONS.contains(declaredType)) throw invalid("Observation 类型不在来源白名单");
        List<Observation> events = mapper.observations(tenantId, proposal.sourceEventId());
        if (events.size() != 1 || !declaredType.equals(events.getFirst().type())) {
            throw invalid("sourceEventId 不是已提交的白名单 Observation");
        }
        if (!"inbound.message".equals(declaredType)) {
            String executionId = text(source, "execution_id");
            String exportPath = text(source, "export_path");
            if (!executionId.equals(events.getFirst().executionId())) {
                throw invalid("Observation executionId 与 SessionLog 不一致");
            }
            if (!containsExportedPath(parse(events.getFirst().payload()), declaredType, exportPath)) {
                throw invalid("exportPath 未指向已提交 Observation 的安全输出");
            }
        }
    }

    private boolean containsEvidenceRef(JsonNode node, String bundleId, long version,
            List<String> itemIds) {
        if (node.isObject()) {
            if (bundleId.equals(node.path("bundleId").asText())
                    && version == node.path("version").asLong(-1)) {
                Set<String> values = values(node.path("itemIds"), null);
                if (values.containsAll(itemIds)) return true;
            }
            var fields = node.elements();
            while (fields.hasNext()) if (containsEvidenceRef(fields.next(), bundleId, version, itemIds)) return true;
        } else if (node.isArray()) {
            for (JsonNode child : node) if (containsEvidenceRef(child, bundleId, version, itemIds)) return true;
        }
        return false;
    }

    private boolean containsExportedPath(JsonNode event, String eventType, String exportPath) {
        String expectedStatus = "agent.tool-result".equals(eventType) ? "SUCCEEDED" : "COMPLETED";
        if (!expectedStatus.equals(event.path("status").asText())) return false;
        JsonNode canonicalJson = event.path("output").path("canonicalJson");
        if (!canonicalJson.isTextual()) return false;
        try {
            return resolvePath(json.readTree(canonicalJson.textValue()), exportPath) != null;
        } catch (Exception invalidOutput) {
            return false;
        }
    }

    /** 只接受确定性的根路径、点路径与数组下标；不支持通配、递归或表达式。 */
    private JsonNode resolvePath(JsonNode root, String path) {
        String value = path.trim();
        if (value.startsWith("$") && value.length() > 1
                && value.charAt(1) != '.' && value.charAt(1) != '[') return null;
        int index = value.startsWith("$") ? 1 : 0;
        JsonNode current = root;
        while (index < value.length()) {
            if (value.charAt(index) == '.') index++;
            if (index >= value.length()) return null;
            if (value.charAt(index) == '[') {
                int close = value.indexOf(']', index + 1);
                if (close < 0) return null;
                String token = value.substring(index + 1, close);
                if (!token.matches("0|[1-9][0-9]*") || !current.isArray()) return null;
                try {
                    current = current.path(Integer.parseInt(token));
                } catch (NumberFormatException tooLarge) {
                    return null;
                }
                index = close + 1;
            } else {
                int end = index;
                while (end < value.length() && value.charAt(end) != '.'
                        && value.charAt(end) != '[') end++;
                String token = value.substring(index, end);
                if (token.isBlank() || !current.isObject()) return null;
                current = current.path(token);
                index = end;
            }
            if (current.isMissingNode()) return null;
        }
        return current;
    }

    private Set<String> values(JsonNode array, String field) {
        if (!array.isArray()) return Set.of();
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (JsonNode item : array) {
            JsonNode value = field == null ? item : item.path(field);
            if (value.isTextual() && !value.textValue().isBlank()) result.add(value.textValue());
        }
        return Set.copyOf(result);
    }

    private JsonNode parse(String value) {
        try {
            return json.readTree(value);
        } catch (Exception exception) {
            throw invalid("已提交来源 JSON 无法验证");
        }
    }

    private List<String> texts(Object value, String field) {
        if (!(value instanceof List<?> list) || list.isEmpty()) throw invalid(field + " 不能为空");
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) throw invalid(field + " 包含无效值");
            result.add(text);
        }
        return List.copyOf(result);
    }

    private String text(Map<?, ?> value, String field) {
        Object item = value.get(field);
        if (!(item instanceof String text) || text.isBlank()) throw invalid(field + " 不能为空");
        return text.trim();
    }

    private long positiveLong(Map<?, ?> value, String field) {
        try {
            long parsed = Long.parseLong(String.valueOf(value.get(field)));
            if (parsed > 0) return parsed;
        } catch (RuntimeException ignored) {
            // 转为稳定的 422。
        }
        throw invalid(field + " 必须为正整数");
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

}
