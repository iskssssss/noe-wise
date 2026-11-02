package com.skillforge.core.model.media.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.model.media.ModelMediaAssetStore;
import com.skillforge.core.model.media.ModelMediaJob.Owner;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.adapter.model.media.BailianRealtimeTranscription;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProvider;
import com.skillforge.runtime.adapter.model.media.DomesticMediaProviders;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 应用层验证原操作复读、音频单次发送与终态保存；SQL/RLS另以真实数据库验证。 */
class ModelRealtimeServiceTest {
    private final PlatformModelCatalogService models = mock(PlatformModelCatalogService.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final ModelRealtimeMapper mapper = mock(ModelRealtimeMapper.class);
    private final ModelMediaAssetStore assets = mock(ModelMediaAssetStore.class);
    private final BailianRealtimeTranscription sdk = mock(BailianRealtimeTranscription.class);
    private final DomesticMediaProviders providers = mock(DomesticMediaProviders.class);
    private final BailianRealtimeTranscription.Session socket = mock(BailianRealtimeTranscription.Session.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);
    private final Owner owner = new Owner(null, "7");
    private final AtomicReference<ModelRealtimeMapper.Row> stored = new AtomicReference<>();
    private final AtomicReference<Consumer<BailianRealtimeTranscription.TranscriptEvent>> events = new AtomicReference<>();

    ModelRealtimeServiceTest() {
        when(crypto.decrypt("encrypted")).thenReturn("private-credential");
        when(mapper.insert(any())).thenAnswer(call -> { stored.set(call.getArgument(0)); return 1; });
        when(mapper.find(eq(owner.key()), anyString())).thenAnswer(call -> stored.get());
        when(mapper.operation(eq(owner.key()), anyString())).thenAnswer(call -> stored.get());
        when(mapper.started(anyString(), any())).thenAnswer(call -> { stored.get().status = "RUNNING"; return 1; });
        when(mapper.claimAudio(anyString(), anyString(), anyLong(), any())).thenAnswer(call -> {
            if (stored.get().nextAudioSequence != (long) call.getArgument(2)) return 0;
            stored.get().nextAudioSequence++; return 1;
        });
        when(mapper.finish(anyString(), anyString(), any())).thenAnswer(call -> {
            if (stored.get().finishing) return 0; stored.get().finishing = true; return 1;
        });
        when(mapper.complete(any())).thenAnswer(call -> { stored.set(call.getArgument(0)); return 1; });
        when(mapper.asset(any())).thenAnswer(call -> { var row = (ModelRealtimeMapper.Row) call.getArgument(0); row.assetAvailable = true; stored.set(row); return 1; });
        when(socket.sendAudio(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(socket.finish()).thenReturn(new CompletableFuture<>());
        when(sdk.open(any(), any(), any())).thenAnswer(call -> {
            events.set(call.getArgument(2));
            events.get().accept(new BailianRealtimeTranscription.TranscriptEvent.Started("task"));
            return socket;
        });
    }

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void reusesOriginalOperationAndRegistersEachAudioChunkBeforeSendingOnce() throws Exception {
        var configuration = config("SPEECH_RECOGNITION");
        when(models.draftForProbe("71")).thenReturn(configuration);
        var service = service();
        var command = new ModelRealtimeSession.OpenAsr(UUID.randomUUID().toString(), "71", null, 16000, "zh");
        var opened = service.openAsr(owner, command);
        assertEquals(DomesticMediaProvider.Status.RUNNING, opened.status());
        assertTrue(opened.connected());
        assertEquals(opened.id(), service.openAsr(owner, command).id());
        verify(sdk, times(1)).open(any(), any(), any());
        assertEquals(1, service.audio(owner, opened.id(), 0, new byte[] {0, 0}).nextAudioSequence());
        assertEquals("model_realtime_audio_sequence_conflict",
                assertThrows(ApiException.class, () -> service.audio(owner, opened.id(), 0, new byte[] {0, 0})).getMessage());
        verify(socket, times(1)).sendAudio(any());
        service.finish(owner, opened.id()); service.finish(owner, opened.id());
        verify(socket, times(1)).finish();

        var result = new DomesticMediaProvider.Result(DomesticMediaProvider.Status.SUCCEEDED, null,
                opened.providerTaskId(), List.of(), new DomesticMediaProvider.Usage(0L, null, null, null, 3L,
                new BigDecimal("1.234567891")), null, clock.instant());
        events.get().accept(new BailianRealtimeTranscription.TranscriptEvent.Completed(
                new BailianRealtimeTranscription.Outcome(result, "识别完成", List.of())));
        var completed = service.detail(owner, opened.id());
        assertEquals("识别完成", completed.finalText());
        assertEquals(new BigDecimal("1.234567891"), completed.usage().durationSeconds());
        assertNull(completed.usage().outputTokens());
        assertFalse(completed.connected());
        assertFalse(new ObjectMapper().findAndRegisterModules().writeValueAsString(completed).contains("private-credential"));
    }

    @Test void reconstructedServiceReadsOriginalFactWithoutOpeningAnotherConnection() {
        var configuration = config("SPEECH_RECOGNITION");
        when(models.draftForProbe("71")).thenReturn(configuration);
        var command = new ModelRealtimeSession.OpenAsr(UUID.randomUUID().toString(), "71", null, 16000, null);
        var original = service().openAsr(owner, command);
        var rebuilt = service();
        var read = rebuilt.openAsr(owner, command);
        assertEquals(original.id(), read.id()); assertFalse(read.connected());
        assertEquals("model_realtime_connection_unavailable",
                assertThrows(ApiException.class, () -> rebuilt.audio(owner, read.id(), 0, new byte[] {0, 0})).getMessage());
        verify(sdk, times(1)).open(any(), any(), any());
    }

    @Test void mismatchedTenantAndDifferentCommandDoNotDispatch() {
        TenantContext.setTenantId("9");
        assertThrows(ApiException.class, () -> service().openAsr(owner,
                new ModelRealtimeSession.OpenAsr(UUID.randomUUID().toString(), "71", null, 16000, null)));
        verifyNoInteractions(models, sdk);
        TenantContext.clear();
        var configuration = config("SPEECH_RECOGNITION");
        when(models.draftForProbe("71")).thenReturn(configuration);
        String operation = UUID.randomUUID().toString();
        service().openAsr(owner, new ModelRealtimeSession.OpenAsr(operation, "71", null, 16000, "zh"));
        assertEquals("model_realtime_operation_conflict", assertThrows(ApiException.class, () -> service().openAsr(owner,
                new ModelRealtimeSession.OpenAsr(operation, "71", null, 8000, "en"))).getMessage());
        verify(sdk, times(1)).open(any(), any(), any());
    }

    @Test void speechStreamsRealEncodingAndStoresCompleteAudioWithoutExposingProviderSource() throws Exception {
        var configuration = config("SPEECH_SYNTHESIS");
        when(models.draftForProbe("71")).thenReturn(configuration);
        var speech = mock(DomesticMediaProvider.class);
        when(providers.require("aliyun-bailian")).thenReturn(speech);
        byte[] bytes = {1, 2, 3, 4};
        when(assets.storeBytes(anyString(), any(), any(), anyString(), any(), any())).thenReturn(
                new ModelMediaAssetStore.Stored("audio/wav", 4, "1".repeat(64), "speech.wav"));
        var complete = new DomesticMediaProvider.Result(DomesticMediaProvider.Status.SUCCEEDED, null, null,
                List.of(new DomesticMediaProvider.Asset(DomesticMediaProvider.AssetKind.AUDIO,
                        new DomesticMediaProvider.BytesSource(bytes), "audio/wav")), null, null, clock.instant());
        when(speech.streamSpeech(any(), any())).thenReturn(subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            int step;
            @Override public void request(long requested) {
                if (step++ == 0) subscriber.onNext(new DomesticMediaProvider.SpeechEvent.AudioChunk(bytes, "pcm_s16le", 24000, 1));
                else if (step == 2) { subscriber.onNext(new DomesticMediaProvider.SpeechEvent.Completed(complete)); subscriber.onComplete(); }
            }
            @Override public void cancel() { }
        }));
        var finished = new CountDownLatch(1);
        var audio = new AtomicReference<ModelRealtimeSession.Audio>();
        var terminal = new AtomicReference<ModelRealtimeSession>();
        var service = service();
        var command = new ModelRealtimeSession.OpenSpeech(UUID.randomUUID().toString(), "71", null, "测试文本", "voice", "Chinese");
        var opened = service.streamSpeech(owner, command, new ModelRealtimeService.Listener() {
            @Override public void snapshot(ModelRealtimeSession value) { terminal.set(value); }
            @Override public void audio(ModelRealtimeSession.Audio value) { audio.set(value); }
            @Override public void complete() { finished.countDown(); }
        });
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals("pcm_s16le", audio.get().encoding());
        assertEquals(24000, audio.get().sampleRateHz());
        assertEquals(DomesticMediaProvider.Status.SUCCEEDED, terminal.get().status());
        assertEquals("speech.wav", terminal.get().asset().name());
        assertEquals(opened.id(), service.detail(owner, opened.id()).id());
        verify(assets).storeBytes(anyString(), eq(DomesticMediaProvider.AssetKind.AUDIO), eq(bytes), eq("audio/wav"), any(), any());
        verify(speech, times(1)).streamSpeech(any(), any());
    }

    @Test void shutdownClosesTenantConnectionWithoutRequestContextAndRejectsNewWork() {
        var tenantOwner = new Owner("9", "7", "shutdown-session");
        when(mapper.find(eq(tenantOwner.key()), anyString())).thenAnswer(call -> stored.get());
        when(mapper.operation(eq(tenantOwner.key()), anyString())).thenAnswer(call -> stored.get());
        var configuration = config("SPEECH_RECOGNITION");
        when(models.draftForProbe("71")).thenReturn(configuration);
        var service = service();
        TenantContext.setTenantId("9");
        var command = new ModelRealtimeSession.OpenAsr(UUID.randomUUID().toString(), "71", null, 16000, null);
        var original = service.openAsr(tenantOwner, command);
        TenantContext.clear();
        service.close(); service.close();
        verify(socket, times(1)).cancel();
        TenantContext.setTenantId("9");
        assertEquals(DomesticMediaProvider.Status.UNKNOWN, service.detail(tenantOwner, original.id()).status());
        assertFalse(service.detail(tenantOwner, original.id()).connected());
        assertEquals("model_realtime_service_closed",
                assertThrows(ApiException.class, () -> service.openAsr(tenantOwner, command)).getMessage());
        verify(sdk, times(1)).open(any(), any(), any());
    }

    private ModelRealtimeService service() {
        var transactions = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
        return new ModelRealtimeService(models, crypto, mapper, assets, sdk, providers, transactions,
                new ObjectMapper().findAndRegisterModules(), clock);
    }
    private static PlatformModelConfig config(String kind) {
        var config = mock(PlatformModelConfig.class);
        when(config.enabled()).thenReturn(true); when(config.kind()).thenReturn(kind); when(config.revision()).thenReturn(1L);
        when(config.providerId()).thenReturn("aliyun-bailian");
        when(config.serviceId()).thenReturn(kind.equals("SPEECH_RECOGNITION") ? "bailian-cn-beijing-asr" : "bailian-cn-beijing-tts");
        when(config.modelId()).thenReturn("model-fixture"); when(config.endpoint()).thenReturn("https://dashscope.aliyuncs.com");
        when(config.apiKeyEncrypted()).thenReturn("encrypted"); when(config.timeoutSeconds()).thenReturn(60);
        return config;
    }
}
