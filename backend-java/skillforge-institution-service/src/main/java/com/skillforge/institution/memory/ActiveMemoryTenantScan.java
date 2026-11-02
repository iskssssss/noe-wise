package com.skillforge.institution.memory;

import java.util.List;

/** 每个 Worker 独立轮转活跃租户；重启从头扫描，任务领取仍由租户内 CAS 负责。 */
final class ActiveMemoryTenantScan {
    private final ActiveMemoryTenantMapper mapper;
    private long cursor;

    ActiveMemoryTenantScan(ActiveMemoryTenantMapper mapper) {
        this.mapper = mapper;
    }

    synchronized List<String> next() {
        List<String> page = after(cursor);
        if (page.isEmpty() && cursor > 0) page = after(0);
        // 先推进发现游标；某个租户失败仍在下一圈重试，不会挡住后续租户。
        cursor = page.isEmpty() ? 0 : Long.parseLong(page.getLast());
        return page;
    }

    private List<String> after(long after) {
        return mapper.after(after);
    }
}
