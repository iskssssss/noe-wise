package com.skillforge.runtime.json;

/** JSON 边界显式提供的资源预算；根值深度为一，对象键和值均计入节点。 */
public record CodecLimits(int maxCodeUnits, int maxNodes, int maxDepth) {
    public CodecLimits {
        if (maxCodeUnits <= 0 || maxNodes <= 0 || maxDepth <= 0 || maxDepth > 128) {
            throw new IllegalArgumentException("codec limits must be positive and maxDepth must not exceed 128");
        }
    }
}
