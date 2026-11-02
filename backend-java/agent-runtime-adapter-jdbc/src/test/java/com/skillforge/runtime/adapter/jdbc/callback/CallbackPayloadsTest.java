package com.skillforge.runtime.adapter.jdbc.callback;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CallbackPayloadsTest {
    private static final TenantId TENANT=new TenantId("tenant");
    private static final CallbackRef CALLBACK=new CallbackRef(TENANT,"cb");
    private static final CallbackDeliveryRef DELIVERY=new CallbackDeliveryRef(TENANT,"d");
    private static final String EXECUTION="[\"tenant\",\"execution\",\"run\",\"action\",\"7\",\"result.v1\",\"{\\\"message\\\":\\\"你好😀\\\"}\"]";

    @Test void exactExecutionSchemaKeepsOriginalSignedBytesAndBusinessPayload() {
        var value=CallbackPayloads.decode(registration(),bytes(EXECUTION));
        assertEquals(CallbackPayloads.EXECUTION_SCHEMA,value.schemaId());assertEquals(EXECUTION,value.canonicalJson());
        assertEquals(new TypedPayload("result.v1","{\"message\":\"你好😀\"}"),CallbackPayloads.businessPayload(registration(),bytes(EXECUTION)));
    }
    @Test void executionRequiresEveryFrozenTargetFieldAndCanonicalRevision() {
        for(String value:List.of(EXECUTION.replace("tenant","foreign"),EXECUTION.replace("execution","other"),
                EXECUTION.replace("\"run\"","\"other\""),EXECUTION.replace("action","other"),
                EXECUTION.replace("\"7\"","\"8\""),EXECUTION.replace("\"7\"","\"07\""),EXECUTION.replace("\"7\"","\"+7\"")))reject(bytes(value));
        reject(bytes("[\"result.v1\",\"{}\"]"));
        reject(bytes("[\"tenant\",\"run\",\"action\",\"invocation\",\"7\",\"COMPLETED\",\"result.v1\",\"{}\"]"));
        var original=registration();var d=original.draft();
        for(String oldSchema:List.of("skillforge.runtime.callback.session-event.v1","skillforge.runtime.callback.action-result.v1","unknown")) {
            var unknown=new CallbackRegistration(new CallbackRegistrationDraft(d.callbackRef(),d.owner(),d.target(),d.source(),oldSchema,d.signaturePolicyId(),d.signingCredentialRef()),
                    original.causationIdentityRef(),original.status(),original.revision(),original.createdAt(),original.updatedAt());
            assertThrows(IllegalArgumentException.class,()->CallbackPayloads.decode(unknown,bytes(EXECUTION)));
        }
    }
    @Test void rejectsMalformedUtf8AndNoncanonicalOuterJsonWithoutLeakingInput() {
        for(String malformed:List.of(" "+EXECUTION,EXECUTION+" ",EXECUTION+"[]","[]","[null,\"{}\"]",
                "[\"result.v1\",\"{}\",\"extra\"]",EXECUTION.replace("result.v1",""),EXECUTION.replace("tenant","\\u0074enant"),
                EXECUTION.replace("你好😀","\\ud83d\\ude00")))reject(bytes(malformed));
        reject(new byte[]{(byte)0xc0,(byte)0xaf});reject(new byte[]{(byte)0xed,(byte)0xa0,(byte)0x80});reject(new byte[]{(byte)0xf0,(byte)0x9f});
    }
    @Test void signingBytesMatchFrozenVectorAndBindTenantCallbackDeliveryTimeAndBody() {
        var signed=CallbackPayloads.signingBytes(CALLBACK,DELIVERY,"1700000000",bytes("abc"));
        assertEquals("callback-v1\ndGVuYW50\nY2I\nZA\n1700000000\nba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",new String(signed,StandardCharsets.UTF_8));
        assertEquals("hmac-sha256-callback.v1",CallbackPayloads.SIGNATURE_POLICY);
        assertFalse(java.util.Arrays.equals(signed,CallbackPayloads.signingBytes(CALLBACK,new CallbackDeliveryRef(TENANT,"other"),"1700000000",bytes("abc"))));
        assertFalse(java.util.Arrays.equals(signed,CallbackPayloads.signingBytes(new CallbackRef(TENANT,"other"),DELIVERY,"1700000000",bytes("abc"))));
        assertFalse(java.util.Arrays.equals(signed,CallbackPayloads.signingBytes(CALLBACK,DELIVERY,"1700000001",bytes("abc"))));
        assertFalse(java.util.Arrays.equals(signed,CallbackPayloads.signingBytes(CALLBACK,DELIVERY,"1700000000",bytes("abd"))));
        assertThrows(IllegalArgumentException.class,()->CallbackPayloads.signingBytes(CALLBACK,new CallbackDeliveryRef(new TenantId("other"),"d"),"1700000000",bytes("abc")));
        for(String invalid:List.of("0","-1","+1","01"," 1","1.0","9223372036854775808"))assertThrows(IllegalArgumentException.class,()->CallbackPayloads.signingBytes(CALLBACK,DELIVERY,invalid,bytes("abc")));
    }
    private static void reject(byte[] value) { assertEquals("callback_payload_invalid",assertThrows(IllegalArgumentException.class,()->CallbackPayloads.decode(registration(),value)).getMessage()); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static CallbackRegistration registration() {
        var owner=SessionOwner.user(new SessionRef(TENANT,"session"),new AgentId("agent"),new EndUserId(1));
        var target=new CallbackTarget.ExecutionTarget(new ExecutionRef(TENANT,"execution"),new RunRef(TENANT,"run"),"action",7);
        var draft=new CallbackRegistrationDraft(CALLBACK,owner,target,"source",CallbackPayloads.EXECUTION_SCHEMA,CallbackPayloads.SIGNATURE_POLICY,new CredentialRef(TENANT,"credential"));
        var now=Instant.parse("2026-09-10T00:00:00Z");return new CallbackRegistration(draft,new ResultRef("causation"),CallbackRegistrationStatus.ACTIVE,1,now,now);
    }
}
