package com.skillforge.core.foundation.catalog.dictionary;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.CatalogScope;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** CSV 错误定位沿用字典领域规则；预检不写入，公开保存与导入拒绝同一无效值。 */
class DictionaryCsvTransferHandlerTest {
    private final DictionaryMapper mapper=mock(DictionaryMapper.class);
    private final DictionaryService dictionaries=new DictionaryService(mapper,mock(FoundationAccess.class),mock(CatalogScope.class),mock(FeatureGate.class));
    private final DictionaryCsvTransferHandler handler=new DictionaryCsvTransferHandler(dictionaries);
    private final Map<String,String> options=Map.of("dictionary_code","audit_status");

    static Stream<Arguments> invalidRows() {
        return Stream.of(
                Arguments.of(List.of("ok","","1","true"),"label","显示文本不能为空"),
                Arguments.of(List.of("ok","  ","1","true"),"label","显示文本不能为空"),
                Arguments.of(List.of("ok","字".repeat(129),"1","true"),"label","显示文本不能超过 128 个字符"),
                Arguments.of(List.of("INVALID","有效","1","true"),"code","稳定编码须以小写字母开头"),
                Arguments.of(List.of("ok","有效","-1","true"),"sort_order","排序必须为非负整数"),
                Arguments.of(List.of("ok","有效","1.5","true"),"sort_order","排序必须为非负整数"),
                Arguments.of(List.of("ok","有效","2147483648","true"),"sort_order","排序必须为非负整数"),
                Arguments.of(List.of("ok","有效","1","yes"),"enabled","启用状态（enabled）只接受 true 或 false"),
                Arguments.of(List.of("ok","有效","1"),"row","CSV必须含四列")
        );
    }

    @ParameterizedTest @MethodSource("invalidRows")
    void reportsActualHeaderAndSafeMessageWithoutWrites(List<String> cells,String field,String message) {
        var errors=handler.validateRow(options,7,cells);
        assertEquals(1,errors.size());
        assertEquals(7,errors.getFirst().rowNumber());
        assertEquals(field,errors.getFirst().field());
        assertTrue(errors.getFirst().message().startsWith(message));
        verifyNoInteractions(mapper);
    }

    @Test void validRowUsesDomainNormalizationWithoutWriting() {
        assertTrue(handler.validateRow(options,2,List.of("ok","  巡检值  ","0","false")).isEmpty());
        var result=dictionaries.inspectImport(List.of(new DictionaryContracts.ImportEntry("audit_status","ok","  巡检值  ",0,false)));
        assertEquals("巡检值",result.entries().getFirst().label());
        verifyNoInteractions(mapper);
    }

    @Test void publicSaveRejectsTheSameMissingDisplayTextBeforeWriting() {
        var error=assertThrows(ApiException.class,()->dictionaries.saveValue(new DictionaryContracts.SaveValue("audit_status","ok","",0,true,0)));
        assertEquals("dictionary_value_label_invalid",error.getCode());
        assertEquals("显示文本不能为空",error.getMessage());
        verifyNoInteractions(mapper);
    }
}
