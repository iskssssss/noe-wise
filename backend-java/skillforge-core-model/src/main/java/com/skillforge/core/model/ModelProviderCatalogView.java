package com.skillforge.core.model;

import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import java.util.List;

/** 宿主配置界面对 SDK 供应商目录的只读投影，不复制目录决策。 */
public record ModelProviderCatalogView(List<Provider> providers) {

    public ModelProviderCatalogView {
        providers = List.copyOf(providers);
    }

    /** 从 SDK 权威目录创建展示投影。 */
    public static ModelProviderCatalogView from(ModelProviderCatalog catalog) {
        return new ModelProviderCatalogView(catalog.presets().stream()
                .map(value -> Provider.from(value, catalog)).toList());
    }

    public record Provider(String providerId, String displayName, String category,
            List<Service> services) {
        public Provider {
            services = List.copyOf(services);
        }

        private static Provider from(ModelProviderCatalog.ProviderPreset preset, ModelProviderCatalog catalog) {
            return new Provider(preset.providerId(), preset.displayName(),
                    preset.category().name(), preset.services().stream()
                            .map(value -> Service.from(preset.providerId(), value, catalog)).toList());
        }
    }

    public record Service(String serviceId, String displayName, String profileVersion,
            String availability, String endpoint, boolean customEndpoint,
            ModelDiscovery modelDiscovery, List<Protocol> protocols, String region, List<String> modelKinds) {
        public Service {
            protocols = List.copyOf(protocols);
            modelKinds = List.copyOf(modelKinds);
        }

        private static Service from(String providerId, ModelProviderCatalog.ServiceProfile profile, ModelProviderCatalog catalog) {
            return new Service(profile.serviceId(), profile.displayName(),
                    profile.profileVersion(), profile.availability().name(),
                    profile.endpoint(), profile.customEndpoint(),
                    ModelDiscovery.from(profile.modelDiscovery()),
                    profile.protocols().stream().map(value -> Protocol.from(value,
                            catalog.protocolImageSupport(providerId, profile.serviceId(), value.protocol()))).toList(),
                    profile.region().name(), profile.modelKinds().stream().map(Enum::name).sorted().toList());
        }
    }

    public record ModelDiscovery(String path, String dialectVersion,
            List<String> authenticationSchemes, String verificationStatus) {
        public ModelDiscovery {
            authenticationSchemes = List.copyOf(authenticationSchemes);
        }

        private static ModelDiscovery from(
                ModelProviderCatalog.ModelDiscoveryProfile profile) {
            if (profile == null) {
                return null;
            }
            return new ModelDiscovery(profile.path(), profile.dialectVersion(),
                    profile.authenticationSchemes().stream()
                            .map(scheme -> scheme.id()).sorted().toList(),
                    profile.verificationStatus().name());
        }
    }

    public record Protocol(String protocol, String dialectVersion,
            List<String> authenticationSchemes, Capability capabilities,
            String verificationStatus, com.skillforge.runtime.api.media.ModelImagePolicy imagePolicyDefaults) {
        public Protocol {
            authenticationSchemes = List.copyOf(authenticationSchemes);
        }

        private static Protocol from(ModelProviderCatalog.ProtocolProfile profile,
                com.skillforge.runtime.adapter.model.provider.ModelImageSupport images) {
            var support = profile.capabilities();
            return new Protocol(profile.protocol().id(), profile.dialectVersion(),
                    profile.authenticationSchemes().stream().map(scheme -> scheme.id())
                            .sorted().toList(),
                    new Capability(support.jsonObject().name(),
                            support.strictJsonSchema().name(), support.toolCalling().name(),
                            support.streaming().name(), support.reasoning().name(),
                            images.userImages().name(), images.toolImages().name()),
                    profile.verificationStatus().name(), images.defaults());
        }
    }

    public record Capability(String jsonObject, String strictJsonSchema,
            String toolCalling, String streaming, String reasoning, String userImages, String toolImages) {
    }
}
