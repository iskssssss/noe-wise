package com.skillforge.runtime.adapter.sqlite;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

/** 桌面会话导航元数据；工作目录首次创建后不可由重复请求静默替换。 */
public final class SqliteDesktopSessions implements AutoCloseable {
    public record Session(String id, String title, String workspace, Instant createdAt) { }
    interface Mapper {
        int create(Session session);
        Session find(@Param("id") String id);
        List<Session> list();
    }
    private final SqliteDatabase database;
    private final SqlSessionFactory sessions;
    private final SqlSessionFactory reads;
    private final Clock clock;
    public SqliteDesktopSessions(Path databaseFile, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        database = new SqliteDatabase(databaseFile, clock);
        var configuration = SqliteDatabase.configuration(database.source());
        SqliteDatabase.load(configuration, "mapper/SqliteDesktopSessionsMapper.xml");
        sessions = new SqlSessionFactoryBuilder().build(configuration);
        var readConfiguration = SqliteDatabase.configuration(database.readSource());
        SqliteDatabase.load(readConfiguration, "mapper/SqliteDesktopSessionsMapper.xml");
        reads = new SqlSessionFactoryBuilder().build(readConfiguration);
    }
    /** 幂等创建；同一 ID 的标题或目录不同则拒绝，并保留原创建时间。 */
    public Session create(String id, String title, String workspace) {
        if (id == null || id.isBlank() || title == null || title.isBlank() || workspace == null || workspace.isBlank())
            throw new IllegalArgumentException("desktop_session_fields_required");
        try (var transaction = sessions.openSession(false)) {
            var mapper = transaction.getMapper(Mapper.class);
            mapper.create(new Session(id, title, workspace, clock.instant()));
            var saved = mapper.find(id);
            if (!saved.title().equals(title) || !saved.workspace().equals(workspace)) throw new IllegalStateException("desktop_session_conflict");
            transaction.commit(); return saved;
        }
    }
    public Optional<Session> find(String id) { try (var session = reads.openSession()) { return Optional.ofNullable(session.getMapper(Mapper.class).find(id)); } }
    public List<Session> list() { try (var session = reads.openSession()) { return List.copyOf(session.getMapper(Mapper.class).list()); } }
    @Override public void close() { database.close(); }
}
