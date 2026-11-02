package com.skillforge.runtime.codec.spi;

import com.skillforge.runtime.api.KnowledgeDocumentResult;
import com.skillforge.runtime.api.TypedPayload;

/**
 * 文档访问的强类型规范编码边界，供检索适配器与 Session 共用同一实现。
 *
 * <p>资源限额由 SDK 装配时固定；编码后的实际 JSON 同时用于持久结果、模型输入与字符预算。
 * 编解码不证明来源授权、证据采用或结果提交，调用者仍须核验当前读取范围。
 */
public interface KnowledgeDocumentCodec {
    String SCHEMA = "skillforge.runtime.knowledge.document-access.v1";
    String TEXT_SCHEMA = "skillforge.runtime.knowledge.document-slice.v1";

    /** 编码目录或原文切片的元数据，不编码原文内容。 */
    TypedPayload encode(KnowledgeDocumentResult result);

    /** 编码原文切片；字符预算必须度量本方法产生的实际 JSON。 */
    TypedPayload encodeText(String text);

    /** 严格校验 schema、结构、资源限额及重新编码后的字节一致性。 */
    KnowledgeDocumentResult decode(TypedPayload payload);
}
