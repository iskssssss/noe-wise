package com.skillforge.runtime.adapter.jdbc.identity;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.SessionOwner;
import java.util.*;

/** 输入能力冻结的独立IAM资源，不把领取Session或DTO存在视为创建许可。 */
public final class SessionCapabilityPolicyResource {
    public static final String ACTION="session.capabilities.freeze";
    public static final String SCHEMA="skillforge.runtime.session.capability-freeze-resource.v1";
    private SessionCapabilityPolicyResource(){}
    public static TypedPayload resource(SessionOwner owner,InputId input){
        var base=SessionPolicyResource.claim(owner).canonicalJson();
        return new TypedPayload(SCHEMA,base.substring(0,base.length()-1)+","+quote(input.value())+"]");
    }
    public static Optional<SessionOwner> owner(String action,TypedPayload payload){
        if(!ACTION.equals(action)||!SCHEMA.equals(payload.schemaId()))return Optional.empty();
        try {
            var f=AdmissionResource.flatStrings(payload.canonicalJson(),6);
            var owner=OwnerPolicyFields.owner(new TenantId(f.get(0)),f.get(3),f.get(1),f.get(2),f.get(4));
            return resource(owner,new InputId(f.get(5))).equals(payload)?Optional.of(owner):Optional.empty();
        }catch(RuntimeException invalid){return Optional.empty();}
    }
    private static String quote(String value){
        var out=new StringBuilder("\"");for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){
            case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\b'->out.append("\\b");case '\t'->out.append("\\t");
            case '\n'->out.append("\\n");case '\f'->out.append("\\f");case '\r'->out.append("\\r");
            default->{if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}}}return out.append('"').toString();
    }
}
