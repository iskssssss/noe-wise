package com.skillforge.core.capability;

/** Catalog API 的稳定错误载荷；不把数据库/凭证细节暴露给调用方。 */
public record CapabilityCatalogError(String code, String message) {
}
