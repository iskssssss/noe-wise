package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.time.Duration;
import java.util.*;

/** 宿主输出协议的固定控制能力；只管理本Run交付，不继承任何业务工具或外部系统权限。 */
final class OutputControlTools {
    /** 宿主固定控制契约的字节事实独立于 Spring mapper；递归排序对象键，数组顺序保持原契约。 */
    private static final ObjectWriter SCHEMA_WRITER=new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writer();
    /** 仅宿主拥有的成果控制名称，不从挂载工具继承执行权限。 */
    private static final Set<String> NAMES=Set.of("finalize_task_outputs");
    /** 判断是否为宿主固定成果控制能力。 */
    static boolean control(String name){return NAMES.contains(name);}
    /** 替换同名候选并追加固定宿主控制；schema 使用独立 writer 形成跨进程稳定字节。 */
    static List<SessionToolDirectory.Entry> append(PlatformToolCatalog catalog, List<SessionToolDirectory.Entry> tools){
        var result=new ArrayList<>(tools.stream().filter(t->!control(t.discovery().name())).toList());
        for(var entry:catalog.entries()) if(control(entry.name())){
            try{
                var binding=new ToolBinding(ToolBinding.ProviderType.PLATFORM,entry.name(),entry.name());
                var target=new FrozenToolTarget(new ToolHandle("tool:host-output:"+entry.name()),
                        new CapabilityRef(CapabilityType.TOOL,"host-output:"+entry.name(),entry.publishedVersionKey()),
                        "host-output:"+entry.name(),1,new TypedPayload("json-schema",SCHEMA_WRITER.writeValueAsString(entry.parametersSchema())),
                        new TypedPayload("json-schema",SCHEMA_WRITER.writeValueAsString(entry.outputSchema())),entry.effect(),entry.idempotent(),
                        Duration.ofMillis(entry.timeoutMs()),true,ToolConfirmationPolicy.NEVER,Set.of(),Set.of("platform_handler:"+entry.name()),true,entry.callIdentityPolicy());
                result.add(new SessionToolDirectory.Entry(target,binding,new SessionToolDirectory.Discovery(binding.serverIdentity(),binding.serverIdentity()+"/"+entry.name(),entry.name(),Optional.of(entry.title()),entry.description()),false));
            }catch(Exception failure){throw new IllegalStateException("output_control_contract_invalid",failure);}
        }
        return List.copyOf(result);
    }
    private OutputControlTools() { }
}
