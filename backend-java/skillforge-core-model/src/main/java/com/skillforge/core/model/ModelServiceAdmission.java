package com.skillforge.core.model;

import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;

/** 新调用使用冻结配置时仍核对实际服务范围；不改变旧版本或历史调用事实。 */
public final class ModelServiceAdmission {
    private ModelServiceAdmission() { }

    public static ModelProviderCatalog.ResolvedProfile resolve(ModelProviderCatalog catalog, PlatformModelConfig config) {
        var service = catalog.serviceProfile(config.providerId(), config.serviceId());
        if (service.region() == ModelProviderCatalog.ServiceRegion.UNVERIFIED
                || !service.modelKinds().contains(ModelProviderCatalog.ModelKind.valueOf(config.kind())))
            throw new IllegalArgumentException("model_domestic_service_required");
        return catalog.resolve(new ModelProviderCatalog.Selection(config.providerId(), config.serviceId(),
                config.profileVersion(), config.modelId(), ModelProviderCatalog.ModelProtocol.fromId(config.protocol()),
                ModelProviderCatalog.AuthenticationScheme.fromId(config.authenticationScheme()), config.endpoint()));
    }
}
