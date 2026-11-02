package com.skillforge.core.chat.trajectory;

/** 轨迹读取的显式资源身份；租户来源由宿主授权器核实。 */
public final class TrajectoryQuery {
    private TrajectoryQuery() { }

    public record Resource(long tenantId, String sessionId, String rootInputId) { }

    /** 快照与游标绑定完整资源及搜索条件；nodeId只用于定位，不授予读取权。 */
    public record Nodes(long tenantId, String sessionId, String rootInputId,
                        String snapshot, String cursor, int pageSize, String nodeId, String query) {
        public Resource resource() { return new Resource(tenantId, sessionId, rootInputId); }
    }

    public record Content(long tenantId, String sessionId, String rootInputId, String contentRef) {
        public Resource resource() { return new Resource(tenantId, sessionId, rootInputId); }
    }
}
