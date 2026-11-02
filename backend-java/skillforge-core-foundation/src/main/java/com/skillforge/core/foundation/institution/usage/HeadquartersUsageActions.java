package com.skillforge.core.foundation.institution.usage;

import java.util.Set;

/** 总部用量动作分别授权；汇总、明细及可靠事件不互相推导。 */
public final class HeadquartersUsageActions {
    public static final String SUMMARY = "tenant.sub_institutions.usage_summary.read";
    public static final String DETAILS = "tenant.sub_institutions.usage_details.read";
    public static final String EVENTS = "tenant.sub_institutions.usage_events.read";
    public static final Set<String> ALL = Set.of(SUMMARY, DETAILS, EVENTS);
    private HeadquartersUsageActions() { }
}
