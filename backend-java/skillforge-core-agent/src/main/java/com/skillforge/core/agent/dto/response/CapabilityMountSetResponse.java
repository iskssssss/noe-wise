package com.skillforge.core.agent.dto.response;

import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import java.util.List;

/** Agent 当前 CapabilityMount 装配代与完整 mount 列表。 */
public record CapabilityMountSetResponse(
        long generation,
        List<CapabilityMountResponse> mounts) {

    public CapabilityMountSetResponse {
        mounts = mounts == null ? List.of() : List.copyOf(mounts);
    }

    /** 将 persistence 结果投影为机构 API 响应。 */
    public static CapabilityMountSetResponse from(CapabilityMountSetResult result) {
        return new CapabilityMountSetResponse(result.generation(),
                result.mounts().stream().map(CapabilityMountResponse::from).toList());
    }
}
