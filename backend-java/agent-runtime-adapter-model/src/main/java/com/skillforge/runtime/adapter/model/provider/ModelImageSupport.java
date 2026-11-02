package com.skillforge.runtime.adapter.model.provider;

import com.skillforge.runtime.api.media.ModelImagePolicy;
import java.util.Objects;

/** 图片能力状态与可选计量策略；协议查询描述编码上限，型号查询描述已核实能力，缺省策略不代表已实测。 */
public record ModelImageSupport(ModelProviderCatalog.SupportLevel userImages,
        ModelProviderCatalog.SupportLevel toolImages, ModelImagePolicy defaults) {
    public ModelImageSupport {
        Objects.requireNonNull(userImages, "userImages");
        Objects.requireNonNull(toolImages, "toolImages");
    }
    public static ModelImageSupport unsupported() {
        return new ModelImageSupport(ModelProviderCatalog.SupportLevel.UNSUPPORTED,
                ModelProviderCatalog.SupportLevel.UNSUPPORTED, null);
    }
}
