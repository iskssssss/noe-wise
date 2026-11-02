package com.skillforge.institution.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 固定成果控制 schema 的字节稳定性与原始完整契约结构。 */
class OutputControlToolsTest {

    /** 对象键递归排序而不改变数组和值，避免 Map.of 的进程随机顺序进入冻结摘要。 */
    @Test
    void schemaBytesAreRecursivelySortedAndPreserveEntirePublishedContract() throws Exception {
        var json=new ObjectMapper();
        var entries=OutputControlTools.append(new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()), List.of());
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().discovery().name()).isEqualTo("finalize_task_outputs");
        for(var entry:entries) {
            var published=new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream()
                    .filter(candidate->candidate.name().equals(entry.discovery().name())).findFirst().orElseThrow();
            JsonNode input=json.readTree(entry.target().inputSchema().canonicalJson());
            JsonNode output=json.readTree(entry.target().outputSchema().canonicalJson());
            assertThat(input).isEqualTo(json.valueToTree(published.parametersSchema()));
            assertThat(output).isEqualTo(json.valueToTree(published.outputSchema()));
            assertSortedObjectKeys(input);
            assertSortedObjectKeys(output);
            assertThat(entry.target().inputSchema().schemaId()).isEqualTo("json-schema");
            assertThat(entry.target().outputSchema().schemaId()).isEqualTo("json-schema");
            assertThat(entry.target().capability().versionId()).isEqualTo(published.publishedVersionKey());
        }
        assertThat(OutputControlTools.append(new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()), List.of())).isEqualTo(entries);
    }

    /** 重复装配始终替换为相同固定控制，不累计重复条目或改变冻结字节。 */
    @Test
    void repeatedAppendKeepsStableControlsAndDoesNotDuplicateThem() {
        var controls=OutputControlTools.append(new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()), List.of());
        assertThat(OutputControlTools.append(new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()), controls)).containsExactlyElementsOf(controls);
    }

    /** 递归检查所有对象层级，数组仅遍历，禁止借排序改变其原始顺序。 */
    private static void assertSortedObjectKeys(JsonNode node) {
        if(node.isObject()) {
            var keys=new ArrayList<String>();
            node.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).isSorted();
        }
        node.forEach(OutputControlToolsTest::assertSortedObjectKeys);
    }
}
