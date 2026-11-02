package com.skillforge.core.foundation.assets;

/** 受保护生成文件的引用所有者授权契约；所有者必须在每次下载重新检查当前领域权限。 */
public interface FoundationFileReferenceAuthorizer {
    /** 固定所有者领域代码。 */ String ownerKind();
    /** 校验真实关联、当前主体、权限和保留状态，失败须抛明确拒绝异常。 */ void authorizeFile(String ownerId,String fileId);
}
