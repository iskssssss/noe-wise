package com.skillforge.core.foundation.assets;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.core.shared.exception.ApiException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 上传材料边界：路径、可执行内容、伪造 MIME 与文本编码必须拒绝。 */
class FoundationFilePolicyTest {
    @Test void rejectsPathsAndControlCharacters(){for(String name:new String[]{"../x.csv","C:\\x.csv","/x.csv","x\n.csv",".",""})assertThrows(ApiException.class,()->FoundationFilePolicy.validateName(name));}
    @Test void rejectsExecutableMarkupAndSpoofedExtension(){assertThrows(ApiException.class,()->FoundationFilePolicy.detect("x.txt","<html><script>1</script>".getBytes(StandardCharsets.UTF_8)));assertThrows(ApiException.class,()->FoundationFilePolicy.detect("x.png","csv,text".getBytes(StandardCharsets.UTF_8)));assertThrows(ApiException.class,()->FoundationFilePolicy.detect("x.svg","<svg/>".getBytes(StandardCharsets.UTF_8)));}
    @Test void preservesChineseUtf8AndDetectsCsv(){assertEquals("text/csv",FoundationFilePolicy.detect("资料.csv","编码,名称\r\nexample,测试".getBytes(StandardCharsets.UTF_8)));}
    @Test void malformedUtf8AndEmptyFileFail(){assertThrows(ApiException.class,()->FoundationFilePolicy.detect("x.csv",new byte[]{(byte)0xc3,0x28}));assertThrows(ApiException.class,()->FoundationFilePolicy.detect("x.csv",new byte[0]));}
    @Test void exactUnicodeLengthBoundaryIsChecked(){assertDoesNotThrow(()->FoundationFilePolicy.validateName("测".repeat(251)+".csv"));assertThrows(ApiException.class,()->FoundationFilePolicy.validateName("测".repeat(252)+".csv"));}
}
