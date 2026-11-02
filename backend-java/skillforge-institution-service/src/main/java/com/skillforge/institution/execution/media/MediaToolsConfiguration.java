package com.skillforge.institution.execution.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.MediaToolDefinitions;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.media.ModelMediaJobService;
import com.skillforge.institution.artifact.ArtifactStorage;
import com.skillforge.institution.artifact.DeliverableService;
import com.skillforge.institution.runtime.AgentRuntimeProgramTools;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Handler只装配已实现的固定能力，挂载、授权及调用登记仍由既有ToolPortal负责。 */
@Configuration
@EnableConfigurationProperties(MediaToolModelBindings.class)
public class MediaToolsConfiguration {
    @Bean
    ModelMediaArtifactPublisher modelMediaArtifactPublisher(ModelMediaJobService jobs, ArtifactStorage artifacts,
            @Value("${skillforge.models.media-tool-staging-root:${java.io.tmpdir}/skillforge-media-tools}") String staging) {
        return new ModelMediaArtifactPublisher(jobs, artifacts, staging);
    }

    @Bean
    ModelMediaToolHandler generateImageToolHandler(ModelMediaJobService jobs, PlatformModelCatalogService models, DeliverableService outputs,
            ModelMediaArtifactPublisher publisher, MediaToolModelBindings bindings, ObjectMapper json, ObjectProvider<AgentRuntimeProgramTools> programs) {
        return new ModelMediaToolHandler(MediaToolDefinitions.GENERATE_IMAGE, bindings.image(), jobs, models, outputs, publisher, json,
                run -> programs.getObject().resourceOrigin(run));
    }
    @Bean
    ModelMediaToolHandler generateVideoToolHandler(ModelMediaJobService jobs, PlatformModelCatalogService models, DeliverableService outputs,
            ModelMediaArtifactPublisher publisher, MediaToolModelBindings bindings, ObjectMapper json, ObjectProvider<AgentRuntimeProgramTools> programs) {
        return new ModelMediaToolHandler(MediaToolDefinitions.GENERATE_VIDEO, bindings.video(), jobs, models, outputs, publisher, json,
                run -> programs.getObject().resourceOrigin(run));
    }
    @Bean
    ModelMediaToolHandler transcribeAudioToolHandler(ModelMediaJobService jobs, PlatformModelCatalogService models, DeliverableService outputs,
            ModelMediaArtifactPublisher publisher, MediaToolModelBindings bindings, ObjectMapper json, ObjectProvider<AgentRuntimeProgramTools> programs) {
        return new ModelMediaToolHandler(MediaToolDefinitions.TRANSCRIBE_AUDIO, bindings.transcription(), jobs, models, outputs, publisher, json,
                run -> programs.getObject().resourceOrigin(run));
    }
    @Bean
    ModelMediaToolHandler synthesizeSpeechToolHandler(ModelMediaJobService jobs, PlatformModelCatalogService models, DeliverableService outputs,
            ModelMediaArtifactPublisher publisher, MediaToolModelBindings bindings, ObjectMapper json, ObjectProvider<AgentRuntimeProgramTools> programs) {
        return new ModelMediaToolHandler(MediaToolDefinitions.SYNTHESIZE_SPEECH, bindings.speech(), jobs, models, outputs, publisher, json,
                run -> programs.getObject().resourceOrigin(run));
    }
    @Bean
    ModelMediaToolHandler queryModelMediaJobToolHandler(ModelMediaJobService jobs, PlatformModelCatalogService models, DeliverableService outputs,
            ModelMediaArtifactPublisher publisher, ObjectMapper json, ObjectProvider<AgentRuntimeProgramTools> programs) {
        return new ModelMediaToolHandler(MediaToolDefinitions.QUERY_JOB, null, jobs, models, outputs, publisher, json,
                run -> programs.getObject().resourceOrigin(run));
    }
}
