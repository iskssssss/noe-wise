package com.skillforge.institution.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.channel.ChannelAgentBindingService;
import com.skillforge.core.channel.ChannelService;
import com.skillforge.core.channel.dto.request.*;
import com.skillforge.core.channel.mapper.ChannelMapper;
import com.skillforge.core.channel.model.ChannelDO;
import com.skillforge.core.channel.constant.ChannelConstants;
import com.skillforge.core.channel.enums.ChannelStatus;
import com.skillforge.core.channel.enums.ChannelType;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.core.foundation.application.CryptoService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** 真实渠道应用服务经 HTTP 控制面委派，验证租户、业务配置及独立凭据生命周期。 */
class ChannelControllerCoverageTest {
    private ChannelMapper mapper;
    private ChannelAgentBindingService bindings;
    private CryptoService crypto;
    private com.skillforge.core.foundation.access.FoundationAccess access;
    private ChannelDO channel;
    private ChannelController controller;

    @BeforeAll static void metadata() {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                configuration, ""), ChannelDO.class);
    }
    @BeforeEach void setUp() {
        TenantContext.setTenantId("7");
        mapper = mock(ChannelMapper.class);
        bindings = mock(ChannelAgentBindingService.class);
        crypto = mock(CryptoService.class);
        access = mock(com.skillforge.core.foundation.access.FoundationAccess.class);
        channel = new ChannelDO();
        channel.setId(31L); channel.setTenantId(7L); channel.setName("widget");
        channel.setChannelType(ChannelType.WEB_WIDGET.code()); channel.setStatus(ChannelStatus.ACTIVE.code());
        channel.setConfig(Map.of("visible", "yes")); channel.setUiConfig(Map.of());
        channel.setChannelTokenPrefix("ch_12345");
        when(mapper.selectOne(any())).thenReturn(channel);
        when(mapper.selectList(any())).thenReturn(List.of(channel));
        when(mapper.lockOwnedChannel(7L, 31L)).thenReturn(31L);
        when(mapper.bindingSummaries(anyLong(), any())).thenReturn(List.of());
        when(crypto.encrypt(anyString())).thenAnswer(call -> "encrypted:" + call.getArgument(0));
        doAnswer(call -> {
            channel.setApiKeyHash(call.getArgument(2));
            channel.setApiSecretEncrypted(call.getArgument(3));
            return 1;
        }).when(mapper).updateApiCredentials(eq(7L), eq(31L), nullable(String.class), nullable(String.class));
        controller = new ChannelController(new ChannelService(mapper, bindings, crypto,
                access,
                org.mockito.Mockito.mock(com.skillforge.core.foundation.catalog.entitlements.FeatureGate.class),
                org.mockito.Mockito.mock(com.skillforge.core.foundation.catalog.entitlements.ResourceQuota.class)));
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void createWithBindingsRequiresUpdatePermissionAndUsesNewChannel() {
        var requests = List.of(new ChannelAgentBindingRequest(81L, 0, true, null));
        var request = new CreateChannelRequest("接入", ChannelType.WEB_WIDGET, null, null, requests);
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "无更新权限"))
                .when(access).require("foundation.channels.update");
        assertThatThrownBy(() -> controller.create(request)).isInstanceOf(ApiException.class);
        verify(mapper, never()).insert(any(ChannelDO.class));
        verifyNoInteractions(bindings);
        reset(access);
        var created = controller.create(request).data();
        verify(access).require("foundation.channels.create");
        verify(access).require("foundation.channels.update");
        verify(bindings).replaceBindings(7L, created.id(), requests);
    }

    @Test void listCreateGetAndUpdateKeepCredentialsOutsideBusinessConfig() {
        assertThat(controller.list().data().total()).isEqualTo(1);
        var created = controller.create(new CreateChannelRequest(null, null, null, null, null)).data();
        assertThat(created.name()).isEqualTo(ChannelConstants.DEFAULT_NAME);
        assertThat(created.type()).isEqualTo(ChannelType.WEB_WIDGET);
        assertThat(created.channelToken()).startsWith(ChannelConstants.TOKEN_PREFIX);
        var inserted = ArgumentCaptor.forClass(ChannelDO.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getTenantId()).isEqualTo(7L);
        assertThat(inserted.getValue().getChannelTokenHash()).isEqualTo(TokenHash.sha256Hex(created.channelToken()));
        assertThat(inserted.getValue().getConfig()).isEmpty();
        channel.setApiKeyHash("stored-hash"); channel.setApiSecretEncrypted("stored-encrypted-secret");
        var response = controller.update(new ChannelController.PatchCommand(31L,
                new PatchChannelRequest("renamed", Map.of("visible", "updated"), Map.of("theme", "dark"), ChannelStatus.DISABLED))).data();
        assertThat(response.config()).containsEntry("visible", "updated");
        assertThat(response.apiKeyIssued()).isTrue();
        assertThat(response.name()).isEqualTo("renamed");
        assertThat(response.status()).isEqualTo(ChannelStatus.DISABLED);
        assertThat(channel.getApiKeyHash()).isEqualTo("stored-hash");
        assertThat(channel.getApiSecretEncrypted()).isEqualTo("stored-encrypted-secret");
        assertThat(new ObjectMapper().valueToTree(controller.get(31L).data()).toString())
                .doesNotContain("stored-hash", "stored-encrypted-secret", "apiKeyHash", "apiSecretEncrypted");
        verify(mapper).lockOwnedChannel(7L, 31L);
        verify(mapper).updateById(channel);
    }

    @Test void configCredentialInjectionIsRejectedBeforeAnyWrite() {
        for (String key : List.of("api_key_hash", "api_secret_encrypted")) {
            assertThatThrownBy(() -> controller.create(new CreateChannelRequest("bad", ChannelType.OPENAPI,
                    Map.of(key, "injected"), null, null))).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
            assertThatThrownBy(() -> controller.update(new ChannelController.PatchCommand(31L,
                    new PatchChannelRequest(null, Map.of(key, "injected"), null, null))))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verifyNoInteractions(mapper, crypto);
    }

    @Test void issueRotateAndRevokePersistOnlyHashAndEncryptedSecret() throws Exception {
        var issueRoutes = ChannelController.class.getMethod("issueApiKey", ChannelController.ChannelCommand.class)
                .getAnnotation(org.springframework.web.bind.annotation.PostMapping.class).value();
        assertThat(issueRoutes).containsExactlyInAnyOrder("/api-keys", "/api-keys/rotate");
        var command = new ChannelController.ChannelCommand(31L);
        assertThatThrownBy(() -> controller.issueApiKey(command)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        channel.setChannelType(ChannelType.OPENAPI.code());
        var issued = controller.issueApiKey(command).data();
        assertThat(issued.apiKey()).startsWith(ChannelConstants.API_KEY_PREFIX);
        assertThat(issued.apiSecret()).isNotBlank();
        assertThat(channel.getApiKeyHash()).isEqualTo(TokenHash.sha256Hex(issued.apiKey()));
        assertThat(channel.getApiSecretEncrypted()).isEqualTo("encrypted:" + issued.apiSecret());
        assertThat(controller.get(31L).data().apiKeyIssued()).isTrue();
        var rotated = controller.issueApiKey(command).data();
        assertThat(rotated.apiKey()).isNotEqualTo(issued.apiKey());
        assertThat(rotated.apiSecret()).isNotEqualTo(issued.apiSecret());
        assertThat(channel.getApiKeyHash()).isEqualTo(TokenHash.sha256Hex(rotated.apiKey()));
        assertThat(channel.getConfig()).isEqualTo(Map.of("visible", "yes"));
        assertThat(controller.revokeApiKey(command).data().status()).isEqualTo(ChannelConstants.STATUS_REVOKED);
        assertThat(channel.getApiKeyHash()).isNull(); assertThat(channel.getApiSecretEncrypted()).isNull();
        assertThat(controller.get(31L).data().apiKeyIssued()).isFalse();
        verify(mapper).updateApiCredentials(7L, 31L, null, null);
    }

    @Test void disableRotateTokenAndBindingLifecycleUseAuthenticatedTenant() {
        var command = new ChannelController.ChannelCommand(31L);
        assertThat(controller.delete(command).data().status()).isEqualTo(ChannelStatus.DISABLED);
        var rotated = controller.rotateToken(command).data();
        assertThat(channel.getChannelTokenHash()).isEqualTo(TokenHash.sha256Hex(rotated.channelToken()));
        var requests = List.of(new ChannelAgentBindingRequest(91L, 0, true, 100),
                new ChannelAgentBindingRequest(92L, 2, false, 0));
        when(bindings.replaceBindings(7L, 31L, requests)).thenReturn(2);
        assertThat(controller.bindAgents(new ChannelController.BindAgentsCommand(31L, requests)).data().count()).isEqualTo(2);
        assertThat(controller.unbindAgent(new ChannelController.UnbindAgentCommand(31L, 91L)).data().status())
                .isEqualTo(ChannelConstants.STATUS_UNBOUND);
        verify(bindings).replaceBindings(7L, 31L, requests); verify(bindings).unbind(7L, 31L, 91L);
    }

    @Test void anotherTenantCannotReadUpdateOrIssueCredentials() {
        TenantContext.setTenantId("8");
        when(mapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> controller.get(31L)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller.issueApiKey(new ChannelController.ChannelCommand(31L)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller.update(new ChannelController.PatchCommand(31L,
                new PatchChannelRequest("escape", null, null, null))))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        var read = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(mapper, times(3)).selectOne(read.capture());
        assertThat(read.getAllValues()).hasSize(3);
        for (var query : read.getAllValues()) {
            assertThat(query.getSqlSegment()).contains("tenant_id", "id");
            assertThat(query.getParamNameValuePairs().values()).contains(8L, 31L);
        }
        verify(mapper, times(2)).lockOwnedChannel(8L, 31L);
        verify(mapper, never()).updateApiCredentials(anyLong(), anyLong(), any(), any());
        verify(mapper, never()).updateById(any(ChannelDO.class));
    }
}
