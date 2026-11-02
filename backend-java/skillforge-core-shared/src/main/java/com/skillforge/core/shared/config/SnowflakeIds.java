package com.skillforge.core.shared.config;

import com.github.yitter.contract.IdGeneratorOptions;
import com.github.yitter.idgen.YitIdHelper;

/**
 * 雪花漂移 ID 生成器（yitter/IdGenerator）静态门面。
 *
 * <p>所有业务主键与业务标识统一经 {@link #nextId()} 生成，不带任何前缀。
 * WorkerId 通过环境变量 {@code SNOWFLAKE_WORKER_ID} 配置（默认 0），
 * 多实例部署时需为每个实例分配唯一值；默认参数下生成的 ID 落在
 * JS Number.MAX_SAFE_INTEGER 范围内，前端可直接使用数字。
 */
public final class SnowflakeIds {

    /** 默认 WorkerId。 */
    private static final short DEFAULT_WORKER_ID = 0;

    private SnowflakeIds() {
    }

    static {
        IdGeneratorOptions options = new IdGeneratorOptions(workerId());
        YitIdHelper.setIdGenerator(options);
    }

    /**
     * 生成下一个全局唯一 ID。
     *
     * @return 雪花 ID
     */
    public static long nextId() {
        return YitIdHelper.nextId();
    }

    /**
     * 读取环境变量配置的 WorkerId。
     *
     * @return WorkerId，非法或缺失时回退默认值
     */
    private static short workerId() {
        String raw = System.getenv("SNOWFLAKE_WORKER_ID");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_WORKER_ID;
        }
        try {
            return Short.parseShort(raw);
        } catch (NumberFormatException e) {
            return DEFAULT_WORKER_ID;
        }
    }
}
