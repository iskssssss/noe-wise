package com.skillforge.institution.execution.media;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.model.PlatformModelMapper;
import com.skillforge.core.model.TimePricingRules;
import com.skillforge.core.model.media.ModelMediaInput;
import com.skillforge.core.model.media.ModelMediaJob;
import com.skillforge.core.model.media.ModelMediaJobService;
import com.skillforge.core.model.media.ModelMediaMeteringSource;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.artifact.DeliverableService;
import com.skillforge.institution.artifact.ArtifactClosingBudget;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.Status;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider.AssetKind;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ModelProtocol;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.CancellationSignal;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelMediaToolHandlerTest {
    private final ModelMediaJobService jobs = mock(ModelMediaJobService.class);
    private final PlatformModelCatalogService models = mock(PlatformModelCatalogService.class);
    private final DeliverableService outputs = mock(DeliverableService.class);
    private final ModelMediaArtifactPublisher publisher = mock(ModelMediaArtifactPublisher.class);
    private final MediaToolModelBindings.Binding binding = new MediaToolModelBindings.Binding("7001", 3);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test void rejectsUnboundToolsAndModelSuppliedIdentityBeforeCreatingAnyTask() throws Exception {
        var unbound = new ModelMediaToolHandler("generate_image", null, jobs, models, outputs, publisher, json, run -> run);
        assertEquals("model_media_tool_binding_required", unbound.execute(Map.of("prompt", "一棵树"), context("a", "s", "attempt")).get("reason_code"));
        var handler = handler("generate_image");
        assertEquals("model_media_arguments_invalid", handler.execute(Map.of("prompt", "一棵树", "model_config_id", "999"), context("a", "s", "attempt")).get("reason_code"));
        verifyNoInteractions(jobs, models, publisher, outputs);
    }

    @Test void usesExactPublishedBindingAndStableLogicalOperationWhileKeepingUnknownVisible() throws Exception {
        when(jobs.createPublished(any(), anyString(), anyString(), anyLong(), any(), any(), any(), any())).thenReturn(job(Status.UNKNOWN));
        var handler = handler("generate_image");
        var first = handler.execute(Map.of("prompt", "一棵树"), context("same-call", "session", "attempt-1"));
        handler.execute(Map.of("prompt", "一棵树"), context("same-call", "session", "attempt-2"));
        var owner = ArgumentCaptor.forClass(ModelMediaJob.Owner.class);
        var operation = ArgumentCaptor.forClass(String.class);
        verify(jobs, times(2)).createPublished(owner.capture(), operation.capture(), eq("7001"), eq(3L), any(ModelMediaInput.Image.class), any(), any(), any());
        assertEquals(operation.getAllValues().getFirst(), operation.getAllValues().getLast());
        assertDoesNotThrow(() -> UUID.fromString(operation.getValue()));
        assertEquals("session", owner.getValue().sessionScope());
        assertEquals("9", owner.getValue().actorId());
        assertEquals("UNKNOWN", first.get("status"));
        assertEquals("101", first.get("job_id"));
        assertEquals(false, first.get("success"));
        assertFalse(json.writeValueAsString(first).contains("api_key"));
    }

    @Test void sourceIsExplicitWithoutThreadMeterScopeAndRetainsTheVerifiedResourceOrigin() throws Exception {
        assertNull(MeterScope.current());
        var origin = mock(RunIdentity.class);
        when(origin.runRef()).thenReturn(new RunRef(new TenantId("1"), "original-resource-run"));
        when(jobs.createPublished(any(), anyString(), anyString(), anyLong(), any(), any(), any(), any()))
                .thenReturn(job(Status.PENDING));
        var handler = new ModelMediaToolHandler("generate_image", binding, jobs, models, outputs, publisher, json, run -> origin);

        handler.execute(Map.of("prompt", "蓝色背景"), context("logical-tool", "media-session", "tool-attempt"));

        var source = ArgumentCaptor.forClass(ModelMediaMeteringSource.class);
        verify(jobs).createPublished(any(), anyString(), eq("7001"), eq(3L), any(), any(), any(), source.capture());
        assertEquals("run", source.getValue().context().runId());
        assertEquals("original-resource-run", source.getValue().context().attributionRunId());
        assertEquals("media-session", source.getValue().context().sessionId());
        assertEquals(9L, source.getValue().context().endUserId());
        assertEquals("input", source.getValue().context().rootInputId());
        assertEquals("logical-tool", source.getValue().toolInvocationId());
        assertEquals("tool-attempt", source.getValue().toolAttemptId());
        assertNull(MeterScope.current(), "media source does not install an ambient metering scope");
    }

    @Test void queryUsesTheCurrentSessionOwnerAndNeverStartsAnotherCreation() throws Exception {
        when(jobs.query(any(), eq("101"), any(), any())).thenReturn(job(Status.PENDING));
        var result = handler("query_model_media_job").execute(Map.of("job_id", "101"), context("query", "second-session", "attempt"));
        assertEquals("PENDING", result.get("status"));
        var owner = ArgumentCaptor.forClass(ModelMediaJob.Owner.class);
        verify(jobs).query(owner.capture(), eq("101"), any(), any());
        assertEquals("second-session", owner.getValue().sessionScope());
        verify(jobs, never()).createPublished(any(), anyString(), anyString(), anyLong(), any(), any(), any(), any());
    }

    @Test void imageEditsResolveOwnedAssetBytesInOriginalReferenceOrder() throws Exception {
        byte[] image = "fixture-image".getBytes(StandardCharsets.UTF_8);
        when(jobs.download(any(), eq("101"), eq("102"))).thenReturn(new ModelMediaJobService.Download(
                "image/png", "image.png", image.length, new ByteArrayInputStream(image)));
        when(jobs.createPublished(any(), anyString(), anyString(), anyLong(), any(), any(), any(), any())).thenReturn(job(Status.PENDING));
        handler("generate_image").execute(Map.of("prompt", "编辑图片", "reference_images", List.of(
                Map.of("job_id", "101", "asset_id", "102"), Map.of("url", "https://assets.example.cn/reference.png"))), context("image-edit", "session", "attempt"));
        var input = ArgumentCaptor.forClass(ModelMediaInput.class);
        verify(jobs).createPublished(any(), anyString(), eq("7001"), eq(3L), input.capture(), any(), any(), any());
        assertEquals(List.of("data:image/png;base64," + Base64.getEncoder().encodeToString(image),
                "https://assets.example.cn/reference.png"), ((ModelMediaInput.Image) input.getValue()).referenceImages());
    }

    @Test void collectionFailureAfterCreationCannotBeReportedAsNotSubmitted() throws Exception {
        when(jobs.createPublished(any(), anyString(), anyString(), anyLong(), any(), any(), any(), any())).thenReturn(job(Status.SUCCEEDED));
        var failure = new ApiException(HttpStatus.CONFLICT, "fixture-collection-rejected");
        when(publisher.publish(any(), any(), any())).thenThrow(failure);

        assertSame(failure, assertThrows(ApiException.class, () -> handler("generate_image")
                .execute(Map.of("prompt", "一棵树"), context("created", "session", "attempt"))));
    }

    @Test void closingBudgetRefusalKeepsItsReasonAndDoesNotDispatchMedia() throws Exception {
        var shortfall = new ExecutionBudgetPort.ClosingShortfall(ExecutionBudgetPort.BalanceScope.RUN,
                "ACTIVE_MILLIS", 240_000, 180_000, 60_000, 0, 0, 180_000);
        var refusal = new ExecutionBudgetPort.ClosingInsufficient(ExecutionBudgetPort.Phase.BUSINESS, List.of(shortfall));
        doThrow(new IllegalArgumentException(ArtifactClosingBudget.REJECTION_CODE, refusal))
                .when(outputs).admitCollection(any());

        var result = handler("generate_image").execute(Map.of("prompt", "蓝色背景"), context("image", "session", "attempt"));

        assertEquals("NOT_SUBMITTED", result.get("status"));
        assertEquals(ArtifactClosingBudget.REJECTION_CODE, result.get("reason_code"));
        assertNull(result.get("job_id"));
        assertEquals(List.of(), result.get("assets"));
        assertEquals(List.of(), result.get("artifacts"));
        assertTrue(json.valueToTree(result).path("public_presentation").path("summary").asText().contains("剩余额度不足"));
        verifyNoInteractions(jobs, models, publisher);
    }

    @Test void collectionStateRefusalIsNotReclassifiedFromMessageText() throws Exception {
        doThrow(new IllegalArgumentException(ArtifactClosingBudget.REJECTION_CODE)).when(outputs).admitCollection(any());

        var result = handler("generate_image").execute(Map.of("prompt", "蓝色背景"), context("image", "session", "attempt"));

        assertEquals("NOT_SUBMITTED", result.get("status"));
        assertEquals("model_media_collection_unavailable", result.get("reason_code"));
        verifyNoInteractions(jobs, models, publisher);
    }

    @Test void collectionTechnicalFailureKeepsItsCauseAndDoesNotDispatchMedia() {
        var failure = new IllegalStateException("budget_storage_failed");
        doThrow(failure).when(outputs).admitCollection(any());

        assertSame(failure, assertThrows(IllegalStateException.class, () -> handler("generate_image")
                .execute(Map.of("prompt", "蓝色背景"), context("image", "session", "attempt"))));
        verifyNoInteractions(jobs, models, publisher);
    }

    @Test void qwenAsrConsumesTheOwnedAudioAssetReturnedBySpeechSynthesis() throws Exception {
        transcriptionModel("bailian-cn-beijing-qwen-asr", ModelProtocol.QWEN_ASR_NATIVE);
        byte[] mp3 = {0x49, 0x44, 0x33, 0x04};
        String audio = "data:audio/mpeg;base64," + Base64.getEncoder().encodeToString(mp3);
        var speechJob = new ModelMediaJob("101", "tts-operation", "7002", "speech-02-hd", 1, "SPEECH_SYNTHESIS", Status.SUCCEEDED,
                false, null, null, Instant.now(), Instant.now(), null,
                List.of(new ModelMediaJob.Asset("102", AssetKind.AUDIO, "audio/mpeg", (long) mp3.length, "speech.mp3", true)));
        when(jobs.createPublished(any(), anyString(), eq("7002"), eq(1L), any(ModelMediaInput.Speech.class), any(), any(), any()))
                .thenReturn(speechJob);
        when(jobs.createPublished(any(), anyString(), eq("7001"), eq(3L), any(ModelMediaInput.QwenTranscription.class), any(), any(), any()))
                .thenReturn(new ModelMediaJob("103", "asr-operation", "7001", "qwen3-asr-flash", 3, "SPEECH_RECOGNITION", Status.SUCCEEDED,
                        false, null, null, Instant.now(), Instant.now(), null, List.of()));
        when(jobs.transcriptionAudio(any(), eq("101"), eq("102"))).thenReturn(audio);
        var speechHandler = new ModelMediaToolHandler("synthesize_speech", new MediaToolModelBindings.Binding("7002", 1),
                jobs, models, outputs, publisher, json, run -> run);
        var speech = json.valueToTree(speechHandler.execute(Map.of("text", "您好", "voice", "fixture-voice"),
                context("tts", "same-session", "attempt")));

        handler("transcribe_audio").execute(Map.of("audio_source", Map.of("job_id", speech.path("job_id").asText(),
                        "asset_id", speech.path("assets").get(0).path("asset_id").asText()), "language", "zh", "enable_itn", false),
                context("asr", "same-session", "attempt"));

        var owner = ArgumentCaptor.forClass(ModelMediaJob.Owner.class);
        var input = ArgumentCaptor.forClass(ModelMediaInput.class);
        verify(jobs).transcriptionAudio(owner.capture(), eq("101"), eq("102"));
        assertEquals("same-session", owner.getValue().sessionScope());
        verify(jobs).createPublished(any(), anyString(), eq("7001"), eq(3L), input.capture(), any(), any(), any());
        assertEquals(new ModelMediaInput.QwenTranscription(audio, "zh", false), input.getAllValues().getLast());
        verify(models).readRevision("7001", 3);
        verify(jobs, never()).referenceSource(any(), anyString(), anyString());
    }

    @Test void paraformerKeepsItsUrlInputEvenWhenTheModelNameLooksLikeQwen() throws Exception {
        var config = transcriptionModel("bailian-cn-beijing-asr", ModelProtocol.BAILIAN_NATIVE);
        when(config.modelId()).thenReturn("qwen3-asr-flash");
        when(jobs.createPublished(any(), anyString(), anyString(), anyLong(), any(), any(), any(), any())).thenReturn(job(Status.PENDING));

        handler("transcribe_audio").execute(Map.of("audio_source", Map.of("url", "https://assets.example.cn/speech.mp3"),
                "language_hints", List.of("zh")), context("url-asr", "session", "attempt"));

        var input = ArgumentCaptor.forClass(ModelMediaInput.class);
        verify(jobs).createPublished(any(), anyString(), eq("7001"), eq(3L), input.capture(), any(), any(), any());
        var transcription = assertInstanceOf(ModelMediaInput.Transcription.class, input.getValue());
        assertEquals(List.of("https://assets.example.cn/speech.mp3"), transcription.fileUrls());
        assertEquals(List.of("zh"), transcription.languageHints());
        verify(jobs, never()).transcriptionAudio(any(), anyString(), anyString());
    }

    @Test void qwenAcceptsAnExplicitHttpsSourceWithoutOpeningAnyStoredAsset() throws Exception {
        transcriptionModel("bailian-cn-beijing-qwen-asr", ModelProtocol.QWEN_ASR_NATIVE);
        when(jobs.createPublished(any(), anyString(), anyString(), anyLong(), any(), any(), any(), any())).thenReturn(job(Status.SUCCEEDED));

        handler("transcribe_audio").execute(Map.of("audio_source", Map.of("url", "https://assets.example.cn/speech.mp3")),
                context("qwen-url", "session", "attempt"));

        var input = ArgumentCaptor.forClass(ModelMediaInput.class);
        verify(jobs).createPublished(any(), anyString(), eq("7001"), eq(3L), input.capture(), any(), any(), any());
        assertEquals(new ModelMediaInput.QwenTranscription("https://assets.example.cn/speech.mp3", null, null), input.getValue());
        verify(jobs, never()).transcriptionAudio(any(), anyString(), anyString());
        verify(jobs, never()).referenceSource(any(), anyString(), anyString());
    }

    @Test void paraformerDoesNotTurnAnInlineOnlyAudioAssetIntoAPublicUrl() throws Exception {
        transcriptionModel("bailian-cn-beijing-asr", ModelProtocol.BAILIAN_NATIVE);
        when(jobs.referenceSource(any(), eq("101"), eq("102")))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "model_media_source_not_remote"));

        var result = handler("transcribe_audio").execute(Map.of("audio_source", Map.of("job_id", "101", "asset_id", "102")),
                context("paraformer-inline", "session", "attempt"));

        assertEquals("NOT_SUBMITTED", result.get("status"));
        verify(jobs, never()).transcriptionAudio(any(), anyString(), anyString());
        verify(jobs, never()).createPublished(any(), anyString(), anyString(), anyLong(), any(), any(), any(), any());
    }

    @Test void transcriptionRejectsAnInexactServiceAndProtocolPairBeforeReadingOrSubmitting() throws Exception {
        transcriptionModel("bailian-cn-beijing-qwen-asr", ModelProtocol.BAILIAN_NATIVE);

        var result = handler("transcribe_audio").execute(Map.of("audio_source", Map.of("job_id", "101", "asset_id", "102")),
                context("invalid-binding", "session", "attempt"));

        assertEquals("model_media_transcription_binding_invalid", result.get("reason_code"));
        verifyNoInteractions(jobs);
    }

    @Test void transcriptionConsumesTheOfficialWireProtocolsReturnedByTheActualPublishedCatalog() throws Exception {
        assertPublishedCatalogConsumption("bailian-cn-beijing-qwen-asr", ModelProtocol.QWEN_ASR_NATIVE,
                "qwen3-asr-flash", ModelMediaInput.QwenTranscription.class);
        assertPublishedCatalogConsumption("bailian-cn-beijing-asr", ModelProtocol.BAILIAN_NATIVE,
                "paraformer-v2", ModelMediaInput.Transcription.class);
    }

    @Test void transcriptionDoesNotAcceptAnEnumNameInPlaceOfTheOfficialWireProtocol() throws Exception {
        var config = transcriptionModel("bailian-cn-beijing-qwen-asr", ModelProtocol.QWEN_ASR_NATIVE);
        when(config.protocol()).thenReturn(ModelProtocol.QWEN_ASR_NATIVE.name());

        var result = handler("transcribe_audio").execute(Map.of("audio_source", Map.of("url", "https://assets.example.cn/speech.mp3")),
                context("enum-name-asr", "session", "attempt"));

        assertEquals("model_media_transcription_binding_invalid", result.get("reason_code"));
        verifyNoInteractions(jobs);
    }

    @Test void qwenDoesNotSilentlyDiscardParaformerOptions() throws Exception {
        transcriptionModel("bailian-cn-beijing-qwen-asr", ModelProtocol.QWEN_ASR_NATIVE);

        var result = handler("transcribe_audio").execute(Map.of("audio_source", Map.of("job_id", "101", "asset_id", "102"),
                "channels", List.of(0)), context("invalid-options", "session", "attempt"));

        assertEquals("model_media_arguments_invalid", result.get("reason_code"));
        verifyNoInteractions(jobs);
    }

    @Test void cancellationDuringControlledAudioReadPreventsPaidSubmission() throws Exception {
        transcriptionModel("bailian-cn-beijing-qwen-asr", ModelProtocol.QWEN_ASR_NATIVE);
        var cancelled = new AtomicBoolean();
        when(jobs.transcriptionAudio(any(), anyString(), anyString())).thenAnswer(call -> {
            cancelled.set(true);
            return "data:audio/mpeg;base64,SUQzBA==";
        });

        var result = handler("transcribe_audio").execute(Map.of("audio_source", Map.of("job_id", "101", "asset_id", "102")),
                context("cancelled-asr", "session", "attempt", cancelled::get));

        assertEquals("model_media_call_cancelled", result.get("reason_code"));
        assertEquals("NOT_SUBMITTED", result.get("status"));
        verify(jobs, never()).createPublished(any(), anyString(), anyString(), anyLong(), any(), any(), any(), any());
    }

    private PlatformModelConfig transcriptionModel(String service, ModelProtocol protocol) {
        var config = mock(PlatformModelConfig.class);
        when(config.serviceId()).thenReturn(service);
        when(config.protocol()).thenReturn(protocol.id());
        when(models.readRevision("7001", 3)).thenReturn(config);
        return config;
    }

    private void assertPublishedCatalogConsumption(String service, ModelProtocol protocol, String model,
            Class<? extends ModelMediaInput> inputType) throws Exception {
        var mapper = mock(PlatformModelMapper.class);
        var crypto = mock(CryptoService.class);
        when(crypto.encrypt("fixture-key")).thenReturn("fixture-encrypted-key");
        var catalogJson = json.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var draft = new AtomicReference<String>();
        var published = new AtomicReference<String>();
        when(mapper.createDraft(anyString(), anyString(), isNull())).thenAnswer(call -> {
            draft.set(call.getArgument(1));
            return 1;
        });
        when(mapper.draft(anyString())).thenAnswer(call -> catalogJson.writeValueAsString(new DraftSnapshot(
                catalogJson.readValue(draft.get(), PlatformModelConfig.class), published.get() == null ? null : 1L,
                published.get() == null)));
        when(mapper.publishDraft(anyString(), eq(1L), eq(1L))).thenReturn(1);
        when(mapper.append(anyString(), eq(1L), anyString(), eq("7"), isNull())).thenAnswer(call -> {
            published.set(call.getArgument(2));
            return 1;
        });
        when(mapper.revision(anyString(), eq(1L))).thenAnswer(call -> published.get());
        var timeRules = mock(TimePricingRules.class);
        when(timeRules.save(isNull())).thenReturn(null);
        when(mapper.draftRuleId(anyString())).thenReturn(null);
        when(mapper.revisionRuleId(anyString(), anyLong())).thenReturn(null);
        var catalog = new PlatformModelCatalogService(mapper, crypto, catalogJson, ModelProviderCatalog.standard(), timeRules);
        var created = catalog.create(new ModelWrite(null, "国内转写测试", "SPEECH_RECOGNITION", true,
                "aliyun-bailian", service, "2026-10-25-cn-v1", protocol.id(), "bearer-api-key",
                "https://dashscope.aliyuncs.com", model, "fixture-key", null, null, null, null, null,
                60, null, null, null, null, null, "CNY"), "7");
        catalog.publish(created.id(), 1, "7");
        assertEquals(protocol.id(), catalog.readRevision(created.id(), 1).protocol());
        var mediaBinding = new MediaToolModelBindings.Binding(created.id(), 1);
        when(jobs.createPublished(any(), anyString(), eq(created.id()), eq(1L), any(), any(), any(), any()))
                .thenReturn(new ModelMediaJob("201", "published-operation", created.id(), model, 1, "SPEECH_RECOGNITION",
                        Status.PENDING, false, null, null, Instant.now(), Instant.now(), null, List.of()));

        var result = new ModelMediaToolHandler("transcribe_audio", mediaBinding, jobs, catalog, outputs, publisher, json, run -> run)
                .execute(Map.of("audio_source", Map.of("url", "https://assets.example.cn/speech.mp3")),
                        context("published-wire-" + service, "session", "attempt"));

        assertEquals("PENDING", result.get("status"));
        var input = ArgumentCaptor.forClass(ModelMediaInput.class);
        verify(jobs).createPublished(any(), anyString(), eq(created.id()), eq(1L), input.capture(), any(), any(), any());
        assertInstanceOf(inputType, input.getValue());
        verify(mapper, times(2)).revision(created.id(), 1);
    }

    private record DraftSnapshot(PlatformModelConfig config,
            @JsonProperty("published_revision") Long publishedRevision,
            @JsonProperty("has_unpublished_changes") boolean hasUnpublishedChanges) { }

    private ModelMediaToolHandler handler(String name) {
        return new ModelMediaToolHandler(name, binding, jobs, models, outputs, publisher, json, run -> run);
    }
    private static ModelMediaJob job(Status status) {
        return new ModelMediaJob("101", "operation", "7001", "qwen-image", 3, "IMAGE_GENERATION", status, false,
                null, null, Instant.now(), Instant.now(), null, List.of());
    }
    private static PlatformToolExecutionContext context(String invocation, String session, String attempt) {
        return context(invocation, session, attempt, () -> false);
    }
    private static PlatformToolExecutionContext context(String invocation, String session, String attempt, CancellationSignal cancelled) {
        var tenant = new TenantId("1"); var input = new InputId("input");
        var owner = SessionOwner.user(new SessionRef(tenant, session), new AgentId("7"), new EndUserId(9));
        var run = new RunIdentity(owner, new AgentId("7"), input, new RunRef(tenant, "run"),
                new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("result"),
                new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(tenant, "input:input"));
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                Set.of(), 1, new ResultRef("verification"));
        return new PlatformToolExecutionContext(new PortCallContext(run, identity,
                new InvocationControl(Instant.now().plusSeconds(120), cancelled)), invocation, attempt);
    }
}
