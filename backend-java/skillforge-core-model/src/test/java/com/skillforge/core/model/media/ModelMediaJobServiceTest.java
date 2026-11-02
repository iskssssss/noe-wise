package com.skillforge.core.model.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.billing.invocation.ExternalInvocationLedger;
import com.skillforge.core.billing.metering.CostRecorder;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.MediaUsage;
import com.skillforge.core.billing.pricing.MediaPricingContext;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.model.PlatformModelMapper;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProviders;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelMediaJobServiceTest {
    private final PlatformModelCatalogService models = mock(PlatformModelCatalogService.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final ModelMediaJobMapper mapper = mock(ModelMediaJobMapper.class);
    private final ModelMediaAssetStore assets = mock(ModelMediaAssetStore.class);
    private final DomesticMediaProvider provider = mock(DomesticMediaProvider.class);
    private final DomesticMediaProviders providers = mock(DomesticMediaProviders.class);
    private final ExternalInvocationLedger invocations = mock(ExternalInvocationLedger.class);
    private final CostRecorder costs = mock(CostRecorder.class);
    private final Map<String, ModelMediaJobMapper.Row> rows = new HashMap<>();
    private final Map<String, ModelMediaJobMapper.Row> operations = new HashMap<>();
    private final ModelMediaJobService jobs = new ModelMediaJobService(models, crypto, mapper, assets,
            providers, mock(PlatformTransactionManager.class), new ObjectMapper().findAndRegisterModules(), invocations, costs);
    private final ModelMediaJob.Owner platform = new ModelMediaJob.Owner(null, "7");
    private final PlatformModelConfig config = mock(PlatformModelConfig.class);

    @BeforeEach void setup() {
        when(providers.require("aliyun-bailian")).thenReturn(provider);
        when(config.enabled()).thenReturn(true);
        when(config.kind()).thenReturn("VIDEO_GENERATION");
        when(config.providerId()).thenReturn("aliyun-bailian");
        when(config.serviceId()).thenReturn("bailian-cn-beijing-video");
        when(config.protocol()).thenReturn("bailian-native");
        when(config.profileVersion()).thenReturn("2026-10-25");
        when(config.endpoint()).thenReturn("https://dashscope.aliyuncs.com");
        when(config.modelId()).thenReturn("wan-fixture");
        when(config.apiKeyEncrypted()).thenReturn("frozen-encrypted");
        when(config.timeoutSeconds()).thenReturn(15);
        when(config.revision()).thenReturn(3L);
        when(models.draftForProbe("101")).thenReturn(config);
        when(crypto.decrypt("frozen-encrypted")).thenReturn("secret-fixture");
        when(mapper.operation(anyString(), anyString())).thenAnswer(call -> operations.get(call.getArgument(0) + ":" + call.getArgument(1)));
        when(mapper.find(anyString(), anyString())).thenAnswer(call -> {
            var row = rows.get(call.getArgument(1));
            return row != null && row.ownerId.equals(call.getArgument(0)) ? row : null;
        });
        when(mapper.insert(any())).thenAnswer(call -> {
            ModelMediaJobMapper.Row row = call.getArgument(0);
            rows.put(row.id, row); operations.put(row.ownerId + ":" + row.operationId, row); return 1;
        });
        when(mapper.update(any(), anyLong())).thenAnswer(call -> {
            ModelMediaJobMapper.Row row = call.getArgument(0);
            row.lockVersion = (long) call.getArgument(1) + 1;
            return 1;
        });
        when(mapper.assets(anyString())).thenReturn(List.of());
        when(invocations.start(anyString(), any(), any())).thenReturn(true);
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void originalOperationIsReturnedBeforeReadingAnEditedDraftAndCannotBeReusedForOtherInput() {
        when(provider.execute(any(), any())).thenReturn(result(DomesticMediaProvider.Status.PENDING, "original-task"));
        var command = command("original");
        var first = jobs.create(platform, command);
        when(models.draftForProbe("101")).thenThrow(new ApiException(org.springframework.http.HttpStatus.CONFLICT, "changed"));
        var repeated = jobs.create(platform, command);
        assertEquals(first.id(), repeated.id());
        assertEquals("original-task", repeated.providerTaskId());
        verify(provider, times(1)).execute(any(), any());
        var conflict = assertThrows(ApiException.class, () -> jobs.create(platform,
                new ModelMediaJob.Create(command.operationId(), "101", null, video("different"))));
        assertEquals("model_media_operation_conflict", conflict.getCode());
    }

    @Test void dispatchFailureStaysUnknownAndNeitherQueryNorSameOperationCreatesAnotherTask() {
        when(provider.execute(any(), any())).thenThrow(new IllegalStateException("private upstream material"));
        var command = command("unknown");
        var first = jobs.create(platform, command);
        assertEquals(DomesticMediaProvider.Status.UNKNOWN, first.status());
        assertNull(first.providerTaskId());
        assertFalse(first.toString().contains("private"));
        assertEquals(first.id(), jobs.create(platform, command).id());
        assertEquals(DomesticMediaProvider.Status.UNKNOWN, jobs.query(platform, first.id()).status());
        verify(provider, times(1)).execute(any(), any());
    }

    @Test void originalTaskQueryUsesFrozenConnectionAndDoesNotReadCurrentModelOrKey() {
        when(provider.execute(any(), any())).thenReturn(result(DomesticMediaProvider.Status.PENDING, "original-task"),
                result(DomesticMediaProvider.Status.SUCCEEDED, "original-task"));
        var first = jobs.create(platform, command("query"));
        clearInvocations(models);
        var complete = jobs.query(platform, first.id());
        assertEquals(DomesticMediaProvider.Status.SUCCEEDED, complete.status());
        var requests = ArgumentCaptor.forClass(DomesticMediaProvider.Request.class);
        var connections = ArgumentCaptor.forClass(DomesticMediaProvider.Connection.class);
        verify(provider, times(2)).execute(connections.capture(), requests.capture());
        assertEquals(new DomesticMediaProvider.TaskQuery(DomesticMediaProvider.TaskKind.VIDEO, "original-task"), requests.getAllValues().getLast());
        assertEquals("secret-fixture", connections.getAllValues().getLast().credential());
        verifyNoInteractions(models);
    }

    @Test void publishedAgentCallUsesExactRevisionAndCompleteSessionOwnership() {
        TenantContext.setTenantId("9");
        var owner = new ModelMediaJob.Owner("9", "7", "first-session");
        when(config.revision()).thenReturn(2L);
        when(models.readRevision("101", 2)).thenReturn(config);
        when(provider.execute(any(), any())).thenReturn(result(DomesticMediaProvider.Status.PENDING, "original-task"));
        var created = jobs.createPublished(owner, UUID.randomUUID().toString(), "101", 2, video("published"),
                Instant.now().plusSeconds(5), () -> false, source("first-session", "creator-run"));
        verify(models).readRevision("101", 2);
        verify(models, never()).draftForProbe(any());
        assertThrows(ApiException.class, () -> jobs.detail(new ModelMediaJob.Owner("9", "7", "other-session"), created.id()));
        assertNotEquals(owner.key(), new ModelMediaJob.Owner("9", "7", "other-session").key());
        TenantContext.setTenantId("10");
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN,
                assertThrows(ApiException.class, () -> jobs.detail(owner, created.id())).getStatus());
    }

    @Test void invalidInputDoesNotRegisterOrDispatchAndStopWaitingDoesNotCancelSupplier() {
        var invalid = new ModelMediaJob.Create(UUID.randomUUID().toString(), "101", null,
                new ModelMediaInput.Video("", List.of(new DomesticMediaProvider.VideoInput(
                        DomesticMediaProvider.VideoInputKind.FIRST_FRAME, "file:///private/path")), null, null, null, null, null, null, null));
        assertEquals("model_media_input_invalid", assertThrows(ApiException.class, () -> jobs.create(platform, invalid)).getCode());
        verify(mapper, never()).insert(any()); verifyNoInteractions(provider);
        when(provider.execute(any(), any())).thenReturn(result(DomesticMediaProvider.Status.PENDING, "original-task"));
        var created = jobs.create(platform, command("stop"));
        when(mapper.stop(platform.key(), created.id())).thenAnswer(call -> { rows.get(created.id()).waitingStopped = true; return 1; });
        var stopped = jobs.stopWaiting(platform, created.id());
        assertTrue(stopped.waitingStopped());
        assertEquals(DomesticMediaProvider.Status.PENDING, stopped.status());
        verify(provider, times(1)).execute(any(), any());
    }

    @Test void qwenTranscriptionUsesExactServiceAndTypedRequestWithFixedPersistedFields() {
        qwenConfiguration();
        String audio = "data:audio/mpeg;base64,SUQzAQID";
        when(crypto.encrypt(audio)).thenReturn("encrypted-managed-audio");
        when(provider.execute(any(), any())).thenReturn(result(DomesticMediaProvider.Status.SUCCEEDED, null));
        var input = new ModelMediaInput.QwenTranscription(audio, "zh", true);
        var created = jobs.create(platform, new ModelMediaJob.Create(UUID.randomUUID().toString(), "101", null, input));
        var request = ArgumentCaptor.forClass(DomesticMediaProvider.Request.class);
        verify(provider).execute(any(), request.capture());
        assertEquals(new DomesticMediaProvider.QwenTranscriptionRequest("qwen3-asr-flash", audio, "zh", true), request.getValue());
        var persisted = rows.get(created.id());
        assertEquals("zh", persisted.language);
        assertEquals(true, persisted.enableItn);
        verify(mapper).insertSource(created.id(), 0, "AUDIO_FILE", "encrypted-managed-audio");
        assertFalse(input.toString().contains(audio));
    }

    @Test void qwenAndParaformerCannotBorrowEachOthersConfiguration() {
        when(config.kind()).thenReturn("SPEECH_RECOGNITION");
        var qwen = new ModelMediaInput.QwenTranscription("https://audio.example.cn/clip.mp3", null, null);
        assertEquals("model_media_configuration_invalid", assertThrows(ApiException.class,
                () -> jobs.create(platform, new ModelMediaJob.Create(UUID.randomUUID().toString(), "101", null, qwen))).getCode());
        qwenConfiguration();
        when(config.protocol()).thenReturn("bailian-native");
        assertEquals("model_media_configuration_invalid", assertThrows(ApiException.class,
                () -> jobs.create(platform, new ModelMediaJob.Create(UUID.randomUUID().toString(), "101", null, qwen))).getCode());
        when(config.protocol()).thenReturn("qwen-asr-native");
        var paraformer = new ModelMediaInput.Transcription(List.of("https://audio.example.cn/clip.mp3"), null, null, null, null);
        assertEquals("model_media_configuration_invalid", assertThrows(ApiException.class,
                () -> jobs.create(platform, new ModelMediaJob.Create(UUID.randomUUID().toString(), "101", null, paraformer))).getCode());
        verify(mapper, never()).insert(any());
        verifyNoInteractions(provider);
    }

    @Test void managedMp3CanFeedQwenWithoutPublishingUrlAndCannotCrossSession() throws Exception {
        TenantContext.setTenantId("9");
        var owner = new ModelMediaJob.Owner("9", "7", "source-session");
        byte[] bytes = {73, 68, 51, 1, 2, 3};
        var source = managedAudio(owner, "audio/mpeg", bytes.length);
        when(assets.open("201")).thenReturn(new ByteArrayInputStream(bytes));
        String audio = jobs.transcriptionAudio(owner, source.id, "201");
        assertEquals("data:audio/mpeg;base64,SUQzAQID", audio);
        qwenConfiguration();
        when(models.readRevision("101", 3)).thenReturn(config);
        when(provider.execute(any(), any())).thenReturn(result(DomesticMediaProvider.Status.SUCCEEDED, null));
        var created = jobs.createPublished(owner, UUID.randomUUID().toString(), "101", 3,
                new ModelMediaInput.QwenTranscription(audio, "zh", true), Instant.now().plusSeconds(5), () -> false,
                source("source-session", "creator-run"));
        assertEquals(DomesticMediaProvider.Status.SUCCEEDED, created.status());
        assertEquals("model_media_job_not_found", assertThrows(ApiException.class,
                () -> jobs.transcriptionAudio(new ModelMediaJob.Owner("9", "7", "other-session"), source.id, "201")).getDetail());
        verify(assets, times(1)).open("201");
        verify(crypto, never()).decrypt("private-supplier-url");
    }

    @Test void managedAudioRejectsOtherMediaAndBoundsEncodedInput() throws Exception {
        var source = managedAudio(platform, "image/png", 6);
        String otherMediaId = source.id;
        var body = spy(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(assets.open("201")).thenReturn(body);
        assertEquals("model_media_audio_type_invalid", assertThrows(ApiException.class,
                () -> jobs.transcriptionAudio(platform, otherMediaId, "201")).getDetail());
        verify(body, never()).readNBytes(anyInt());
        verify(body).close();
        source = managedAudio(platform, "audio/mpeg", DomesticMediaProvider.QwenTranscriptionRequest.MAXIMUM_ENCODED_AUDIO_BYTES);
        String jobId = source.id;
        assertEquals("model_media_audio_size_exceeded", assertThrows(ApiException.class,
                () -> jobs.transcriptionAudio(platform, jobId, "201")).getDetail());
        verify(body, never()).readNBytes(anyInt());
    }

    @Test void managedAudioRejectsTruncatedAndExtendedStoredContentBeforeTranscription() throws Exception {
        var source = managedAudio(platform, "audio/wav", 6);
        for (int actualSize : List.of(5, 7)) {
            var body = spy(new ByteArrayInputStream(new byte[actualSize]));
            when(assets.open("201")).thenReturn(body);
            assertEquals("media_asset_size_invalid", assertThrows(java.io.IOException.class,
                    () -> jobs.transcriptionAudio(platform, source.id, "201")).getMessage());
            verify(body).close();
        }
        verifyNoInteractions(provider);
    }

    @Test void paraformerStillReceivesItsOriginalPublicUrlRequest() {
        when(config.kind()).thenReturn("SPEECH_RECOGNITION");
        when(config.modelId()).thenReturn("paraformer-v2");
        when(provider.execute(any(), any())).thenReturn(result(DomesticMediaProvider.Status.PENDING, "paraformer-task"));
        String audio = "https://audio.example.cn/clip.mp3";
        var input = new ModelMediaInput.Transcription(List.of(audio), List.of(0), List.of("zh"), true, 2);
        var created = jobs.create(platform, new ModelMediaJob.Create(UUID.randomUUID().toString(), "101", null, input));
        var request = ArgumentCaptor.forClass(DomesticMediaProvider.Request.class);
        verify(provider).execute(any(), request.capture());
        assertEquals(new DomesticMediaProvider.TranscriptionRequest("paraformer-v2", List.of(audio), List.of(0),
                List.of("zh"), true, 2), request.getValue());
        assertEquals("paraformer-task", created.providerTaskId());
    }

    @Test void unsavedQwenInputUsesRealCatalogWireProtocolAndDispatchesItsTypedRequest() {
        var modelMapper = mock(PlatformModelMapper.class);
        var json = new ObjectMapper().findAndRegisterModules();
        var catalog = new PlatformModelCatalogService(modelMapper, crypto, json, ModelProviderCatalog.standard(), mock(com.skillforge.core.model.TimePricingRules.class));
        var media = new ModelMediaJobService(catalog, crypto, mapper, assets, providers,
                mock(PlatformTransactionManager.class), json, invocations, costs);
        var payload = new ModelWrite(null, "临时Qwen语音识别", "SPEECH_RECOGNITION", true,
                "aliyun-bailian", "bailian-cn-beijing-qwen-asr", "2026-10-25-cn-v1", "qwen-asr-native",
                "bearer-api-key", "https://dashscope.aliyuncs.com", "qwen3-asr-flash", "synthetic-key",
                null, null, null, null, null, 60, null, null, null, null, null, "CNY");
        when(crypto.encrypt("synthetic-key")).thenReturn("frozen-encrypted");
        var preview = catalog.previewForProbe(null, payload);
        assertEquals("qwen-asr-native", preview.protocol());
        assertEquals(0, preview.revision());
        when(provider.execute(any(), any())).thenReturn(result(DomesticMediaProvider.Status.SUCCEEDED, null));
        String audio = "data:audio/mpeg;base64,SUQzAQID";
        var created = media.create(platform, new ModelMediaJob.Create(UUID.randomUUID().toString(), null, payload,
                new ModelMediaInput.QwenTranscription(audio, "zh", true)));
        var request = ArgumentCaptor.forClass(DomesticMediaProvider.Request.class);
        verify(provider).execute(any(), request.capture());
        assertEquals(new DomesticMediaProvider.QwenTranscriptionRequest("qwen3-asr-flash", audio, "zh", true), request.getValue());
        assertNull(created.modelConfigId());
        assertEquals(0, created.modelRevision());
        verifyNoInteractions(modelMapper);
    }

    @Test void platformProbeDoesNotCreateInstitutionBillingFacts() {
        when(provider.execute(any(), any())).thenAnswer(call -> {
            DomesticMediaProvider.Connection connection = call.getArgument(0);
            connection.beforeDispatch().beforeDispatch();
            return result(DomesticMediaProvider.Status.SUCCEEDED, null);
        });

        jobs.create(platform, command("platform-probe"));

        verifyNoInteractions(invocations, costs);
    }

    @Test void supplierStartPrecedesSendAndOriginalQueryOnlyObservesTheSameEconomicIdentity() {
        TenantContext.setTenantId("9");
        var owner = new ModelMediaJob.Owner("9", "7", "media-session");
        when(models.readRevision("101", 3)).thenReturn(config);
        var sends = new AtomicInteger();
        when(provider.execute(any(), any())).thenAnswer(call -> {
            DomesticMediaProvider.Connection connection = call.getArgument(0);
            connection.beforeDispatch().beforeDispatch();
            if (sends.get() == 0) {
                verify(costs).recordMediaStarted(eq("9"), any(), any(), any(), any());
                sends.incrementAndGet();
                return new DomesticMediaProvider.Result(DomesticMediaProvider.Status.PENDING, "create-receipt", "task",
                        List.of(), new DomesticMediaProvider.Usage(null, null, null, null, 18L, new java.math.BigDecimal("2.08")), null, Instant.now());
            }
            sends.incrementAndGet();
            return new DomesticMediaProvider.Result(DomesticMediaProvider.Status.SUCCEEDED, "query-receipt", "task",
                    List.of(), new DomesticMediaProvider.Usage(55L, null, null, null, null, null), null, Instant.now());
        });
        String operation = UUID.randomUUID().toString();
        var creator = source("media-session", "original-creator-run");
        var created = jobs.createPublished(owner, operation, "101", 3, video("one-task"), Instant.now().plusSeconds(10), () -> false, creator);
        assertEquals(created.id(), jobs.createPublished(owner, operation, "101", 3, video("one-task"), Instant.now().plusSeconds(10), () -> false, creator).id());
        var queried = jobs.query(owner, created.id());
        jobs.query(owner, created.id());

        assertEquals(2, sends.get(), "one creation and one original task query; completed readback does not send");
        var original = ArgumentCaptor.forClass(CostContext.class);
        var invocation = ArgumentCaptor.forClass(com.skillforge.core.billing.invocation.ExternalInvocationRequest.class);
        verify(costs).recordMediaStarted(eq("9"), original.capture(), invocation.capture(), any(), any());
        assertEquals("original-creator-run", original.getValue().runId());
        assertEquals("media-job:" + created.id(), invocation.getValue().invocationId());
        assertEquals("media-job:" + created.id() + ":create:1", invocation.getValue().attemptId());
        var observations = ArgumentCaptor.forClass(MediaUsage.class);
        verify(costs).recordMediaObservation(eq("9"), eq(invocation.getValue().invocationId()), eq(1L), any(), eq(false), eq(new MediaPricingContext("720P")));
        verify(costs).recordMediaObservation(eq("9"), eq(invocation.getValue().invocationId()), eq(2L), observations.capture(), eq(true), eq(new MediaPricingContext("720P")));
        assertEquals(18L, observations.getValue().characters());
        assertEquals(new java.math.BigDecimal("2.08"), observations.getValue().durationSeconds());
        assertEquals(55L, queried.usage().inputTokens());
        assertEquals(18L, queried.usage().characters());
        assertNull(queried.usage().outputTokens(), "missing supplier usage stays unknown");
    }

    @Test void meteringStartFailureStopsBeforeNetworkAndPreservesTheTechnicalCause() {
        TenantContext.setTenantId("9");
        var owner = new ModelMediaJob.Owner("9", "7", "media-session");
        when(models.readRevision("101", 3)).thenReturn(config);
        var failure = new IllegalStateException("usage_storage_unavailable");
        doThrow(failure).when(costs).recordMediaStarted(anyString(), any(), any(), any(), any());
        var sends = new AtomicInteger();
        when(provider.execute(any(), any())).thenAnswer(call -> {
            ((DomesticMediaProvider.Connection) call.getArgument(0)).beforeDispatch().beforeDispatch();
            sends.incrementAndGet();
            return result(DomesticMediaProvider.Status.SUCCEEDED, null);
        });

        assertSame(failure, assertThrows(IllegalStateException.class, () -> jobs.createPublished(owner,
                UUID.randomUUID().toString(), "101", 3, video("not-sent"), Instant.now().plusSeconds(10), () -> false,
                source("media-session", "creator"))));
        assertEquals(0, sends.get());
        var rejected = rows.values().iterator().next();
        assertEquals("NOT_SUBMITTED", rejected.status);
        assertEquals("model_media_metering_not_submitted", rejected.reasonCode);
        verify(costs, never()).recordMediaObservation(anyString(), anyString(), anyLong(), any(), anyBoolean(), any());
    }

    @Test void supplierUnknownRetainsOneOriginalInvocationAndNeverSettlesOrResends() {
        TenantContext.setTenantId("9");
        var owner = new ModelMediaJob.Owner("9", "7", "media-session");
        when(models.readRevision("101", 3)).thenReturn(config);
        var sends = new AtomicInteger();
        when(provider.execute(any(), any())).thenAnswer(call -> {
            ((DomesticMediaProvider.Connection) call.getArgument(0)).beforeDispatch().beforeDispatch();
            sends.incrementAndGet();
            throw new IllegalStateException("transport outcome unknown");
        });
        String operation = UUID.randomUUID().toString();
        var source = source("media-session", "creator");
        var created = jobs.createPublished(owner, operation, "101", 3, video("unknown"), Instant.now().plusSeconds(10), () -> false, source);

        assertEquals(DomesticMediaProvider.Status.UNKNOWN, created.status());
        assertEquals(created.id(), jobs.createPublished(owner, operation, "101", 3, video("unknown"), Instant.now().plusSeconds(10), () -> false, source).id());
        assertEquals(DomesticMediaProvider.Status.UNKNOWN, jobs.query(owner, created.id()).status());
        assertEquals(1, sends.get());
        verify(costs).recordMediaStarted(eq("9"), any(), any(), any(), any());
        verify(costs).recordMediaObservation(eq("9"), eq("media-job:" + created.id()), eq(1L), eq(MediaUsage.empty()), eq(false), eq(new MediaPricingContext("720P")));
        verify(invocations).finish(eq("9"), eq("media-job:" + created.id() + ":create:1"),
                eq(com.skillforge.core.billing.invocation.ExternalInvocationStatus.UNRESOLVED), isNull(), any(), any());
    }

    @Test void cancellationAfterStartedButBeforeSendClosesOnlyTheUnsentOriginalCreation() {
        TenantContext.setTenantId("9");
        var owner = new ModelMediaJob.Owner("9", "7", "media-session");
        when(models.readRevision("101", 3)).thenReturn(config);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> { cancelled.set(true); return null; }).when(costs).recordMediaStarted(anyString(), any(), any(), any(), any());
        var sends = new AtomicInteger();
        when(provider.execute(any(), any())).thenAnswer(call -> {
            DomesticMediaProvider.Connection connection = call.getArgument(0);
            connection.beforeDispatch().beforeDispatch();
            if (connection.cancelled().getAsBoolean())
                return new DomesticMediaProvider.Result(DomesticMediaProvider.Status.NOT_SUBMITTED,
                        null, null, List.of(), null, "media_cancelled_before_send", Instant.now());
            sends.incrementAndGet();
            return result(DomesticMediaProvider.Status.SUCCEEDED, null);
        });

        var created = jobs.createPublished(owner, UUID.randomUUID().toString(), "101", 3, video("cancelled"),
                Instant.now().plusSeconds(10), cancelled::get, source("media-session", "creator"));

        assertEquals(0, sends.get());
        assertEquals(DomesticMediaProvider.Status.NOT_SUBMITTED, created.status());
        verify(costs).recordMediaNotSubmitted("9", "media-job:" + created.id(), 1);
        verify(costs, never()).recordMediaObservation(anyString(), anyString(), anyLong(), any(), anyBoolean(), any());
        verify(invocations).finish(eq("9"), eq("media-job:" + created.id() + ":create:1"),
                eq(com.skillforge.core.billing.invocation.ExternalInvocationStatus.FAILED), isNull(), eq("media_cancelled_before_send"), any());
    }

    @Test void queryNotSubmittedPreservesOriginalPendingStatusAndReportedUsage() {
        TenantContext.setTenantId("9");
        var owner = new ModelMediaJob.Owner("9", "7", "media-session");
        when(models.readRevision("101", 3)).thenReturn(config);
        when(provider.execute(any(), any())).thenAnswer(call -> {
            DomesticMediaProvider.Connection connection = call.getArgument(0);
            connection.beforeDispatch().beforeDispatch();
            if (call.getArgument(1) instanceof DomesticMediaProvider.TaskQuery)
                return new DomesticMediaProvider.Result(DomesticMediaProvider.Status.NOT_SUBMITTED,
                        null, null, List.of(), null, "media_cancelled_before_send", Instant.now());
            return new DomesticMediaProvider.Result(DomesticMediaProvider.Status.PENDING, "create-receipt", "original-task",
                    List.of(), new DomesticMediaProvider.Usage(null, null, null, 1L, null, null), null, Instant.now());
        });
        var created = jobs.createPublished(owner, UUID.randomUUID().toString(), "101", 3, video("pending"),
                Instant.now().plusSeconds(10), () -> false, source("media-session", "creator"));
        var queried = jobs.query(owner, created.id());

        assertEquals(DomesticMediaProvider.Status.PENDING, queried.status());
        assertEquals("original-task", queried.providerTaskId()); assertEquals(1L, queried.usage().videoCount());
        verify(costs, never()).recordMediaNotSubmitted(anyString(), anyString(), anyLong());
        verify(costs, times(1)).recordMediaObservation(anyString(), anyString(), anyLong(), any(), anyBoolean(), any());
        verify(invocations, times(1)).finish(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test void institutionCreateRequiresExplicitTrustedMeteringSourceBeforeRegisteringOrSending() {
        TenantContext.setTenantId("9");
        var owner = new ModelMediaJob.Owner("9", "7", "media-session");
        assertThrows(IllegalArgumentException.class, () -> jobs.createPublished(owner, UUID.randomUUID().toString(),
                "101", 3, video("missing-source"), Instant.now().plusSeconds(10), () -> false, null));
        assertThrows(IllegalArgumentException.class, () -> jobs.create(owner, command("probe-bypass")));
        verifyNoInteractions(models, provider, costs, invocations);
        verify(mapper, never()).insert(any());
    }

    private void qwenConfiguration() {
        when(config.kind()).thenReturn("SPEECH_RECOGNITION");
        when(config.serviceId()).thenReturn("bailian-cn-beijing-qwen-asr");
        when(config.protocol()).thenReturn("qwen-asr-native");
        when(config.modelId()).thenReturn("qwen3-asr-flash");
    }

    private ModelMediaJobMapper.Row managedAudio(ModelMediaJob.Owner owner, String type, long size) {
        var row = new ModelMediaJobMapper.Row();
        row.id = "301"; row.ownerId = owner.key(); row.tenantId = owner.tenantId(); row.status = "SUCCEEDED";
        rows.put(row.id, row);
        var asset = new ModelMediaJobMapper.AssetRow();
        asset.id = "201"; asset.jobId = row.id; asset.kind = "AUDIO"; asset.mediaType = type;
        asset.name = "generated.mp3"; asset.sizeBytes = size; asset.available = true;
        when(mapper.assets(row.id)).thenReturn(List.of(asset));
        return row;
    }

    private static ModelMediaJob.Create command(String prompt) {
        return new ModelMediaJob.Create(UUID.randomUUID().toString(), "101", null, video(prompt));
    }
    private static ModelMediaMeteringSource source(String session, String run) {
        var trace = new TraceContext("input", "root-input", "trace", "span", "tool-event", null);
        return new ModelMediaMeteringSource(CostContext.builder().requestId("input").rootInputId("root-input")
                .runId(run).attributionRunId(run).sessionId(session).endUserId(7L).agentId(8L)
                .callPoint("agent-runtime.media").traceContext(trace).build(), "tool-invocation", "tool-attempt");
    }
    private static ModelMediaInput.Video video(String prompt) {
        return new ModelMediaInput.Video(prompt, List.of(), "720P", "16:9", 5, false, null, null, false);
    }
    private static DomesticMediaProvider.Result result(DomesticMediaProvider.Status status, String task) {
        return new DomesticMediaProvider.Result(status, "request-fixture", task, List.of(), null, null, Instant.now());
    }
}
