package com.skillforge.core.foundation.jobs;

import com.skillforge.core.shared.exception.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;

/** 严格 UTF-8/RFC4180 CSV，容量和行数有限制；导出防止电子表格公式执行。 */
public final class FoundationCsv {
    /** 固定后台批次最大一万数据行。 */ public static final int MAX_ROWS=10000;
    private FoundationCsv() { }
    /** 表头必须精确匹配，完整解析后再启动领域写入。 */
    public static List<List<String>> parse(byte[] bytes,List<String> columns) {
        String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(java.nio.charset.CharacterCodingException e){throw invalid("CSV 必须使用 UTF-8");}
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();
        boolean quoted=false,closed=false,started=false;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);started=true;
            if(quoted){if(c=='"'){if(i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;}}else cell.append(c);}
            else if(c=='"'){if(cell.length()!=0||closed)throw invalid("CSV 引号位置无效");quoted=true;}
            else if(c==','||c=='\r'||c=='\n'){
                row.add(cell.toString());cell.setLength(0);closed=false;
                if(c!=','){if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;rows.add(List.copyOf(row));row.clear();started=false;if(rows.size()>MAX_ROWS+1)throw invalid("CSV 超过一万数据行");}
            }else{if(closed)throw invalid("CSV 闭合引号后仅允许分隔符");if(c==0)throw invalid("CSV 含非法字符");cell.append(c);}
            if(cell.length()>4096)throw invalid("CSV 单元格超过 4096 字符");
        }
        if(quoted)throw invalid("CSV 引号未闭合");
        if(started||!row.isEmpty()||cell.length()>0){row.add(cell.toString());rows.add(List.copyOf(row));}
        if(rows.isEmpty()||!rows.getFirst().equals(columns))throw invalid("CSV 表头必须为："+String.join(",",columns));
        if(rows.size()>MAX_ROWS+1)throw invalid("CSV 超过一万数据行");
        var data=List.copyOf(rows.subList(1,rows.size()));
        if(data.isEmpty())throw invalid("CSV 没有数据行");
        for(var values:data)if(values.size()!=columns.size())throw invalid("CSV 列数与表头不一致");
        return data;
    }
    /** 防公式前缀，保留文本语义；输出 CRLF 和标准引号。 */
    public static byte[] encode(List<String> columns,List<List<String>> rows){
        if(rows.size()>MAX_ROWS)throw invalid("导出超过一万行，请收窄范围");StringBuilder out=new StringBuilder("\uFEFF");append(out,columns);
        for(var row:rows){if(row.size()!=columns.size())throw new IllegalStateException("处理器导出列数错误");append(out,row);if(out.length()>20*1024*1024)throw invalid("导出超过文件容量");}
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    /** 单元格转义包含公式安全保护，不能写原始业务文本到可执行位置。 */
    private static void append(StringBuilder out,List<String> row){for(int i=0;i<row.size();i++){if(i>0)out.append(',');String value=row.get(i)==null?"":row.get(i);String trimmed=value.stripLeading();if(!trimmed.isEmpty()&&"=+-@".indexOf(trimmed.charAt(0))>=0)value="'"+value;out.append('"').append(value.replace("\"","\"\"")).append('"');}out.append("\r\n");}
    /** 无部分成功的解析错误。 */ private static ApiException invalid(String message){return new ApiException(HttpStatus.BAD_REQUEST,"csv_invalid",message,false);}
}
