package com.skillforge.core.foundation.catalog;

import com.skillforge.core.shared.exception.ApiException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/** catalog 输入的码点长度、稳定编码与明确错误边界。 */
public final class CatalogValidation {
    private CatalogValidation() { }
    /** 稳定编码只允许小写字母、数字、下划线、点和连字符。 */
    public static String code(String value) { if(value==null || !value.matches("[a-z][a-z0-9_.-]{0,63}")) throw invalid("code 格式错误"); return value; }
    /** 显示文本 trim 后按 Unicode 码点检查，越界不截断。 */
    public static String text(String value,int max) { if(value==null || value.isBlank()) throw invalid("名称不能为空"); String v=value.trim(); if(v.codePointCount(0,v.length())>max) throw invalid("文本超出长度限制"); return v; }
    /** 可空外部 HTTPS 链接仅用于展示，不在服务端下载。 */
    public static String url(String value) { if(value==null || value.isBlank()) return null; if(value.length()>2048) throw invalid("URL 超出限制"); try { URI u=URI.create(value); if(!"https".equals(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null) throw invalid("URL 必须是无凭据的 HTTPS 链接"); } catch(IllegalArgumentException e) { throw invalid("URL 格式错误"); } return value; }
    /** CAS 失败要求客户端重新读取，永不覆盖其他修改。 */
    public static ApiException conflict() { return new ApiException(HttpStatus.CONFLICT,"catalog_revision_conflict","配置已被修改，请刷新后重试",false); }
    /** 缺失资源与跨范围查询一致返回不存在。 */
    public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"catalog_not_found","目录资源不存在",false); }
    /** 所有输入失败使用稳定领域原因。 */
    public static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"catalog_invalid",message,false); }
}
