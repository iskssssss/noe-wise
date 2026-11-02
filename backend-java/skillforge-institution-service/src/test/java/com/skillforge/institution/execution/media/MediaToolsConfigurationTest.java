package com.skillforge.institution.execution.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.media.ModelMediaJobService;
import com.skillforge.institution.artifact.ArtifactStorage;
import com.skillforge.institution.artifact.DeliverableService;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MediaToolsConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MediaToolsConfiguration.class)
            .withBean(ModelMediaJobService.class, () -> mock(ModelMediaJobService.class))
            .withBean(PlatformModelCatalogService.class, () -> mock(PlatformModelCatalogService.class))
            .withBean(ArtifactStorage.class, () -> mock(ArtifactStorage.class))
            .withBean(DeliverableService.class, () -> mock(DeliverableService.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test void registersAllHandlersWithoutInventingAModelBinding() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var bindings = context.getBean(MediaToolModelBindings.class);
            assertNull(bindings.image());
            assertNull(bindings.video());
            assertNull(bindings.transcription());
            assertNull(bindings.speech());
            assertEquals(Set.of("generate_image", "generate_video", "transcribe_audio", "synthesize_speech", "query_model_media_job"),
                    context.getBeansOfType(ModelMediaToolHandler.class).values().stream()
                            .map(ModelMediaToolHandler::toolName).collect(Collectors.toSet()));
        });
    }

    @Test void bindsTheExactPublishedModelRevisionFromDeploymentConfiguration() {
        runner.withPropertyValues("skillforge.models.media-tools.image.model-config-id=7001",
                "skillforge.models.media-tools.image.published-revision=3").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(new MediaToolModelBindings.Binding("7001", 3),
                    context.getBean(MediaToolModelBindings.class).image());
        });
    }
}
