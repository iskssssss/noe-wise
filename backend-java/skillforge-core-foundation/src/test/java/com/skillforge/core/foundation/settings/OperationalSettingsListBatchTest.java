package com.skillforge.core.foundation.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.skillforge.core.foundation.application.CryptoService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationalSettingsListBatchTest {
    private final OperationalSettingsMapper mapper=mock(OperationalSettingsMapper.class);
    private final CryptoService crypto=mock(CryptoService.class);
    private final OperationalSettingsRegistry registry=registry();
    private final OperationalSettingsService service=new OperationalSettingsService(mapper,crypto,registry);

    static OperationalSettingsRegistry registry() {
        return new OperationalSettingsRegistry(List.of(()->List.of(
                OperationalSettingsRegistry.group("alpha","甲",OperationalSettingsRegistry.string("value","值","A",false,false,100)),
                OperationalSettingsRegistry.group("beta","乙",OperationalSettingsRegistry.string("token","密钥","secret",false,true,100)),
                OperationalSettingsRegistry.group("gamma","丙",OperationalSettingsRegistry.string("value","值","C",false,false,100)))));
    }
    static OperationalSettingHeadRow head(String key,long revision) {
        var row=new OperationalSettingHeadRow();row.setGroupKey(key);row.setCurrentRevision(revision);return row;
    }
    static OperationalSettingVersionRow version(String key,long revision,Map<String,Object> values) {
        var row=new OperationalSettingVersionRow();row.setGroupKey(key);row.setRevision(revision);row.setValues(values);return row;
    }
    @Test void completeViewsStayOrderedAndRedactedUsingCapturedPairsOnly() {
        var heads=List.of(head("gamma",3),head("alpha",2),head("beta",4));
        var rows=List.of(version("gamma",3,Map.of("value","C")),version("beta",4,Map.of("token",Map.of("$encrypted","cipher"))),version("alpha",2,Map.of("value","A")));
        when(crypto.decrypt("cipher")).thenReturn("secret");
        for(var head:heads){when(mapper.selectCurrent(head.getGroupKey())).thenReturn(head);
            when(mapper.selectVersion(head.getGroupKey(),head.getCurrentRevision())).thenReturn(rows.stream().filter(row->row.getGroupKey().equals(head.getGroupKey())).findFirst().orElseThrow());}
        var baseline=registry.groups().stream().map(group->service.get(group.key())).toList();
        clearInvocations(mapper,crypto);
        when(mapper.selectCurrents(List.of("alpha","beta","gamma"))).thenReturn(heads);
        when(mapper.selectVersions(heads)).thenReturn(rows);
        var actual=service.list();assertEquals(baseline,actual);
        assertEquals(List.of("alpha","beta","gamma"),actual.stream().map(OperationalSettingsService.GroupView::key).toList());
        assertTrue(actual.get(1).values().isEmpty());
        verify(mapper).selectCurrents(List.of("alpha","beta","gamma"));verify(mapper).selectVersions(heads);
        verify(mapper,never()).selectCurrent(anyString());verify(mapper,never()).selectVersion(anyString(),anyLong());
    }
    @Test void earlierMissingVersionWinsOverLaterMissingHead() {
        when(mapper.selectCurrents(anyList())).thenReturn(List.of(head("alpha",1)));
        when(mapper.selectVersions(anyList())).thenReturn(List.of());
        assertEquals("运营配置当前版本快照不存在: alpha",assertThrows(IllegalStateException.class,service::list).getMessage());
    }
    @Test void domainErrorsAreStillCheckedInRegistryOrder() {
        when(mapper.selectCurrents(anyList())).thenReturn(List.of(head("alpha",1),head("beta",1)));
        when(mapper.selectVersions(anyList())).thenReturn(List.of(version("alpha",1,Map.of("value",42)),
                version("beta",1,Map.of("token",Map.of("$encrypted","broken")))));
        assertEquals("运营配置持久化快照不符合注册定义: alpha",assertThrows(IllegalStateException.class,service::list).getMessage());
        verifyNoInteractions(crypto);
        when(mapper.selectVersions(anyList())).thenReturn(List.of(version("alpha",1,Map.of("value","A")),version("beta",1,Map.of("token","unencrypted"))));
        assertEquals("敏感运营配置未加密: token",assertThrows(IllegalStateException.class,service::list).getMessage());
        when(mapper.selectVersions(anyList())).thenReturn(List.of(version("alpha",1,Map.of("value","A")),
                version("beta",1,Map.of("token",Map.of("$encrypted","broken")))));
        when(crypto.decrypt("broken")).thenThrow(new IllegalStateException("解密失败"));
        assertEquals("解密失败",assertThrows(IllegalStateException.class,service::list).getMessage());
        verify(crypto).decrypt("broken");
    }
    @Test void emptyRegistryOrNoHeadsNeverSendEmptyInQueries() {
        var empty=new OperationalSettingsService(mapper,crypto,new OperationalSettingsRegistry(List.of()));
        assertTrue(empty.list().isEmpty());verifyNoInteractions(mapper,crypto);
        when(mapper.selectCurrents(anyList())).thenReturn(List.of());
        assertEquals("运营配置当前版本不存在: alpha",assertThrows(IllegalStateException.class,service::list).getMessage());
        verify(mapper,never()).selectVersions(anyList());
    }
}
