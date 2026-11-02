package com.skillforge.core.knowledge.dto.response;

import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import java.util.List;

/** 宿主配置界面对 SDK 供应商目录的只读投影，不复制目录决策。 */
public record LlmProviderCatalogResponse(List<Provider> providers) {

    public LlmProviderCatalogResponse {
        providers = List.copyOf(providers);
    }

    /** 从 SDK 权威目录创建展示投影。 */
    public static LlmProviderCatalogResponse from(ModelProviderCatalog catalog) {
        return new LlmProviderCatalogResponse(catalog.presets().stream()
                .map(Provider::from).toList());
    }

    public record Provider(String providerId, String displayName, String category,
            List<Service> services) {
        public Provider {
            services = List.copyOf(services);
        }

        private static Provider from(ModelProviderCatalog.ProviderPreset preset) {
            return new Provider(preset.providerId(), preset.displayName(),
                    preset.category().name(), preset.services().stream()
                            .map(Service::from).toList());
        }
    }

    public record Service(String serviceId, String displayName, String profileVersion,
            String availability, String endpoint, boolean customEndpoint,
            ModelDiscovery modelDiscovery, List<Protocol> protocols) {
        public Service {
            protocols = List.copyOf(protocols);
        }

        private static Service from(ModelProviderCatalog.ServiceProfile profile) {
            return new Service(profile.serviceId(), profile.displayName(),
                    profile.profileVersion(), profile.availability().name(),
                    profile.endpoint(), profile.customEndpoint(),
                    ModelDiscovery.from(profile.modelDiscovery()),
                    profile.protocols().stream().map(Protocol::from).toList());
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
            String verificationStatus) {
        public Protocol {
            authenticationSchemes = List.copyOf(authenticationSchemes);
        }

        private static Protocol from(ModelProviderCatalog.ProtocolProfile profile) {
            var support = profile.capabilities();
            return new Protocol(profile.protocol().id(), profile.dialectVersion(),
                    profile.authenticationSchemes().stream().map(scheme -> scheme.id())
                            .sorted().toList(),
                    new Capability(support.jsonObject().name(),
                            support.strictJsonSchema().name(), support.toolCalling().name(),
                            support.streaming().name(), support.reasoning().name()),
                    profile.verificationStatus().name());
        }
    }

    public record Capability(String jsonObject, String strictJsonSchema,
            String toolCalling, String streaming, String reasoning) {
    }
}
