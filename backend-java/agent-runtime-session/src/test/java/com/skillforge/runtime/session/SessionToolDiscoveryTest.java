package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class SessionToolDiscoveryTest {
    @Test void exactQualifiedNameWinsOverDescriptionAndMultiTermMatchesWithoutChangingHandle() {
        var exact = searchable("exact", "mcp:catalog/find documents", "");
        var broad = searchable("broad", "mcp:catalog/find documents extra", "mcp:catalog/find documents");
        var description = searchable("description", "mcp:other/search", "mcp:catalog/find documents");

        var result = SessionToolDiscovery.search(List.of(broad, description, exact), "  MCP:CATALOG/FIND DOCUMENTS  ", 3);

        assertEquals(List.of(exact), result);
        assertSame(exact, result.getFirst());
        assertEquals(exact.target().handle(), result.getFirst().target().handle());
        assertEquals(List.of(description), SessionToolDiscovery.search(List.of(description), "mcp:catalog/find documents", 3));
    }

    @Test void exactNameCannotDiscoverAnEntryOutsideTheCurrentAuthorizedDirectory() {
        var hidden = searchable("private", "mcp:private/delete", "Delete private documents");
        var visible = searchable("visible", "mcp:public/search", "Search public documents");
        assertEquals(List.of(hidden), SessionToolDiscovery.search(List.of(hidden, visible), "mcp:private/delete", 3));
        assertEquals(List.of(), SessionToolDiscovery.search(List.of(visible), "mcp:private/delete", 3));
    }

    @Test void duplicateExactNamesUseStableHandleOrderAndLimitWhileTokenSearchRemainsAvailable() {
        var first = searchable("a", "mcp:catalog/find", "");
        var second = searchable("b", "mcp:catalog/find", "");
        assertEquals(List.of(first), SessionToolDiscovery.search(List.of(second, first), "mcp:catalog/find", 1));
        assertEquals(List.of(first, second), SessionToolDiscovery.search(List.of(second, first), "mcp:catalog/find", 3));

        var name = searchable("name", "mcp:catalog/documents", "");
        var description = searchable("description", "mcp:other/search", "documents archived");
        assertEquals(List.of(name, description),
                SessionToolDiscovery.search(List.of(description, name), "documents, archived", 3));
    }

    @Test void searchRejectsEmptyOrUnboundedRequests() {
        assertThrows(IllegalArgumentException.class,()->SessionToolDiscovery.search(List.of(),"",1));
        assertThrows(IllegalArgumentException.class,()->SessionToolDiscovery.search(List.of(),"documents",13));
    }

    @Test void pageReportsOnlyRealAdditionalMatchesIncludingAtMaximumLimit() {
        var first = searchable("first", "mcp:catalog/first", "Find documents");
        var second = searchable("second", "mcp:catalog/second", "Find documents");
        var exactLimit = SessionToolDiscovery.searchPage(List.of(first, second), "documents", 2);
        assertEquals(List.of(first, second), exactLimit.matches());
        assertFalse(exactLimit.hasMore());
        var limited = SessionToolDiscovery.searchPage(List.of(first, second), "documents", 1);
        assertEquals(List.of(first), limited.matches());
        assertTrue(limited.hasMore());
        var none = SessionToolDiscovery.searchPage(List.of(first), "unavailable", 1);
        assertEquals(List.of(), none.matches());
        assertFalse(none.hasMore());

        var many = java.util.stream.IntStream.range(0, 13)
                .mapToObj(index -> searchable("id" + index, "mcp:catalog/tool" + index, "Find documents")).toList();
        var maximum = SessionToolDiscovery.searchPage(many, "documents", 12);
        assertEquals(12, maximum.matches().size());
        assertTrue(maximum.hasMore());
        assertFalse(SessionToolDiscovery.searchPage(many.subList(0, 12), "documents", 12).hasMore());
        assertThrows(UnsupportedOperationException.class, () -> maximum.matches().clear());
    }

    @Test void exactNamePageDoesNotClaimDescriptionFallbacksAsAdditionalExactMatches() {
        var exact = searchable("exact", "mcp:catalog/find", "Find documents");
        var fallback = searchable("fallback", "mcp:other/search", "mcp:catalog/find");
        var result = SessionToolDiscovery.searchPage(List.of(fallback, exact), "mcp:catalog/find", 1);
        assertEquals(List.of(exact), result.matches());
        assertFalse(result.hasMore());
    }

    /** v1的名称4分、每个描述词1分及稳定同分顺序由独立目录样例固定，不授予目录外资格。 */
    @Test void versionOneNameAndDescriptionWeightsPreserveStableTiesAndFrozenEntries() {
        var name = searchable("name", "mcp:z/inventory", "Unrelated");
        var threeDescriptions = searchable("three", "mcp:b/lookup", "recent notes reports");
        var fourDescriptions = searchable("four", "mcp:a/lookup", "recent notes reports status");
        var fiveDescriptions = searchable("five", "mcp:c/lookup", "recent notes reports status archived");

        var result = SessionToolDiscovery.search(List.of(name, threeDescriptions, fiveDescriptions, fourDescriptions),
                "INVENTORY recent notes reports status archived inventory", 4);

        assertEquals("authorized-tool-name-description-substring", SessionToolDiscovery.POLICY_ID);
        assertEquals(1, SessionToolDiscovery.POLICY_VERSION);
        assertEquals(List.of(fiveDescriptions, fourDescriptions, name, threeDescriptions), result);
        assertSame(name, result.get(2));
    }
    private static SessionToolDirectory.Entry searchable(String id,String qualifiedName,String description) {
        int separator=qualifiedName.indexOf('/');String server=qualifiedName.substring(0,separator),name=qualifiedName.substring(separator+1);
        var target=new ToolPortal.FrozenToolTarget(new ToolPortal.ToolHandle("tool:"+id),new CapabilityRef(CapabilityType.TOOL,id,"1"),"deployment",1,
                new TypedPayload("json-schema","{}"),new TypedPayload("json-schema","{}"),ToolEffect.READ_ONLY,true,Duration.ofSeconds(30),true,
                ToolPortal.ToolConfirmationPolicy.NEVER,Set.of(),Set.of(),true,ToolPortal.ToolCallIdentityPolicy.ARGUMENTS);
        var binding=new ToolPortal.ToolBinding(ToolPortal.ToolBinding.ProviderType.MCP,server.substring("mcp:".length()),name);
        return new SessionToolDirectory.Entry(target,binding,new SessionToolDirectory.Discovery(server,qualifiedName,name,Optional.empty(),description.isEmpty()?"Unrelated tool":description),true);
    }
}
