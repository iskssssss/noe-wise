package com.skillforge.core.foundation.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalDO;
import com.skillforge.core.shared.security.TokenHash;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.beans.BeanUtils;

/** 服务身份必须隔离租户、只存凭证派生值，并以版本 CAS 轮换。 */
class ServicePrincipalServiceTest {

    private final ServicePrincipalMapper mapper = mock(ServicePrincipalMapper.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final ServicePrincipalService service = new ServicePrincipalService(mapper, crypto);

    @Test
    void issueStoresOnlyKeyHashAndEncryptedSecret() {
        when(crypto.encrypt(any())).thenAnswer(invocation -> "enc:" + invocation.getArgument(0));
        var persisted = new AtomicReference<ServicePrincipalDO>();
        Instant storedTime = Instant.parse("2026-10-13T00:00:00.234567Z");
        when(mapper.insert(any(ServicePrincipalDO.class))).thenAnswer(invocation -> {
            var stored = new ServicePrincipalDO();
            BeanUtils.copyProperties(invocation.getArgument(0), stored);
            stored.setCreatedAt(storedTime); stored.setUpdatedAt(storedTime);
            persisted.set(stored);
            return 1;
        });
        when(mapper.selectOne(any())).thenAnswer(invocation -> persisted.get());
        when(mapper.selectList(any())).thenAnswer(invocation -> List.of(persisted.get()));

        ServicePrincipalService.IssuedPrincipal issued = service.issue(
                "7", "crm", Set.of("sessions.events.read"));

        ArgumentCaptor<ServicePrincipalDO> capture = ArgumentCaptor.forClass(ServicePrincipalDO.class);
        verify(mapper).insert(capture.capture());
        ServicePrincipalDO stored = capture.getValue();
        assertEquals(7L, stored.getTenantId());
        assertEquals("crm", stored.getIntegrationId());
        assertEquals(ServicePrincipalService.ACTIVE, stored.getStatus());
        assertEquals(TokenHash.sha256Hex(issued.apiKey()), stored.getApiKeyHash());
        assertFalse(stored.getApiKeyHash().contains(issued.apiKey()));
        assertTrue(stored.getSigningSecretEncrypted().startsWith("enc:"));
        assertEquals(Set.of("sessions.events.read"), Set.copyOf(stored.getScopes()));
        assertEquals(storedTime, issued.principal().getCreatedAt());
        assertEquals(service.list("7").getFirst(), issued.principal());
        verify(crypto).encrypt(issued.signingSecret());
        var lookup = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectOne(lookup.capture());
        assertTrue(lookup.getValue().getSqlSegment().contains("tenant_id"));
        assertEquals(Set.of(7L, stored.getId()), Set.copyOf(lookup.getValue().getParamNameValuePairs().values()));
    }

    @Test
    void rotateUsesVersionCasAndReplacesBothCredentials() {
        ServicePrincipalDO existing = existing();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.update(any(), any())).thenReturn(1);
        when(crypto.encrypt(any())).thenAnswer(invocation -> "enc:" + invocation.getArgument(0));

        ServicePrincipalService.IssuedPrincipal rotated = service.rotate("7", 12L);

        assertEquals(2L, rotated.principal().getCredentialVersion());
        assertNotEquals("old-hash", rotated.principal().getApiKeyHash());
        assertTrue(rotated.apiKey().startsWith("sfsp_"));
        verify(mapper).update(any(), any());
    }

    @Test
    void listAndStatusTransitionsStayTenantScopedAndInvalidInputsFailClosed() {
        when(mapper.selectList(any())).thenReturn(List.of(existing()));
        when(mapper.update(any(), any())).thenReturn(1);

        assertEquals(1, service.list("7").size());
        assertTrue(service.suspend("7", 12L));
        assertTrue(service.revoke("7", 12L));
        assertThrows(IllegalArgumentException.class, () -> service.issue("7", "", Set.of("sessions.events.read")));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> service.issue("7", "crm", Set.of("INVALID SCOPE"))).getStatus());
        assertThrows(IllegalArgumentException.class, () -> service.issue("", "crm", Set.of("sessions.events.read")));
    }

    @Test
    void rotateRejectsMissingOrContendedPrincipal() {
        when(mapper.selectOne(any())).thenReturn(null);
        var missing = assertThrows(ApiException.class, () -> service.rotate("7", 12L));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatus());
        assertEquals("NOT_FOUND", missing.getCode());

        ServicePrincipalDO existing = existing();
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.update(any(), any())).thenReturn(0);
        when(crypto.encrypt(any())).thenReturn("enc");
        assertThrows(IllegalStateException.class, () -> service.rotate("7", 12L));
    }

    /** scope 格式拒绝早于随机凭据、加密及所有持久化访问。 */
    @Test void invalidScopeIsValidationErrorWithoutCredentialWrites() {
        var failure = assertThrows(ApiException.class, () -> service.issue("7", "crm", Set.of("UPPER BAD")));
        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatus());
        assertEquals("VALIDATION_ERROR", failure.getCode());
        verifyNoInteractions(mapper, crypto);
    }

    /** 停用身份轮换是状态冲突，保留包括密文和时间在内的整行原值。 */
    @Test void suspendedRotateIsConflictWithoutChangingStoredCredentials() {
        var row = existing();
        row.setStatus(ServicePrincipalService.SUSPENDED);
        var json = new ObjectMapper().findAndRegisterModules();
        var before = json.valueToTree(row);
        when(mapper.selectOne(any())).thenReturn(row);

        var failure = assertThrows(ApiException.class, () -> service.rotate("7", 12L));
        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        assertEquals("CONFLICT", failure.getCode());
        assertEquals(before, json.valueToTree(row));
        verify(mapper, never()).update(any(), any());
        verifyNoInteractions(crypto);
    }

    /** 当前授权必须使用全部固定身份谓词和scope，而非整机构凭据列表。 */
    @Test void currentAuthorizationUsesExactIdentityVersionAndScopePredicate() {
        var identity=new com.skillforge.common.context.ServicePrincipalContext.Identity("sp-existing","7","crm",1,Set.of("sessions.events.read"));
        when(mapper.selectOne(any())).thenReturn(existing());
        service.requireCurrent(identity,"sessions.events.read");
        var query=ArgumentCaptor.forClass(QueryWrapper.class);verify(mapper).selectOne(query.capture());
        String sql=query.getValue().getSqlSegment();
        for(String column:List.of("tenant_id","integration_id","principal_id","status","credential_version","scopes"))
            assertTrue(sql.contains(column),column);
        assertTrue(sql.contains("jsonb_build_array"));
        assertEquals(Set.of(7L,"crm","sp-existing","active",1L,"sessions.events.read"),
                Set.copyOf(query.getValue().getParamNameValuePairs().values()));
        verify(mapper,never()).selectList(any());verifyNoInteractions(crypto);
        org.junit.jupiter.api.Assertions.assertNull(com.skillforge.common.context.TenantContext.getTenantId());
    }

    /** 身份缺失或旧scope缺失时不查询凭据；当前撤销、scope收窄仍失败关闭。 */
    @Test void currentAuthorizationRejectsAbsentStaleAndNarrowedCredentialsWithoutWriting() {
        var identity=new com.skillforge.common.context.ServicePrincipalContext.Identity("sp-existing","7","crm",1,Set.of("sessions.events.read"));
        assertThrows(ApiException.class,()->service.requireCurrent(null,"sessions.events.read"));
        assertThrows(ApiException.class,()->service.requireCurrent(identity,"sessions.write"));
        verifyNoInteractions(mapper,crypto);
        assertThrows(ApiException.class,()->service.requireCurrent(identity,"sessions.events.read"));
        var narrowed=existing();narrowed.setScopes(List.of("sessions.read"));when(mapper.selectOne(any())).thenReturn(narrowed);
        assertThrows(ApiException.class,()->service.requireCurrent(identity,"sessions.events.read"));
        verify(mapper,never()).update(any(),any());verifyNoInteractions(crypto);
        org.junit.jupiter.api.Assertions.assertNull(com.skillforge.common.context.TenantContext.getTenantId());
    }

    private ServicePrincipalDO existing() {
        ServicePrincipalDO row = new ServicePrincipalDO();
        row.setId(12L);
        row.setTenantId(7L);
        row.setPrincipalId("sp-existing");
        row.setIntegrationId("crm");
        row.setApiKeyHash("old-hash");
        row.setApiKeyPrefix("sfsp_old");
        row.setSigningSecretEncrypted("old-enc");
        row.setScopes(List.of("sessions.events.read"));
        row.setStatus(ServicePrincipalService.ACTIVE);
        row.setCredentialVersion(1L);
        row.setCreatedAt(Instant.parse("2026-10-12T00:00:00.123456Z"));
        row.setUpdatedAt(Instant.parse("2026-10-12T01:00:00.654321Z"));
        return row;
    }
}
