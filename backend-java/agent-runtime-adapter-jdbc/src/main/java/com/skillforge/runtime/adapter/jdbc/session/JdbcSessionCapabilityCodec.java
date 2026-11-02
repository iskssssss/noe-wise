package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.session.spi.SessionCapabilities;

/** 宿主自定义能力来源时，仍使用 JDBC 标准快照格式供准备与恢复组件读取。 */
public final class JdbcSessionCapabilityCodec {
    private JdbcSessionCapabilityCodec() {}
    public static String encode(SessionCapabilities.Snapshot snapshot) {
        return SessionPersistenceCodec.encode(snapshot);
    }
    public static SessionCapabilities.Snapshot decode(String data) {
        return SessionPersistenceCodec.decode(data, SessionCapabilities.Snapshot.class);
    }
}
