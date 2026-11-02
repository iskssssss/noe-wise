package com.skillforge.institution.execution;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformMediaToolHandler;
import com.skillforge.core.capability.connector.ProgramToolInvoker;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 在既有 Node 沙箱中执行程序；tools.call 只能调用该模型步骤已显示的授权句柄。 */
@Component
public final class ProgramCodeToolHandler implements PlatformMediaToolHandler {
    private final CodeExecutionToolHandler execution;
    private final ObjectProvider<ProgramToolInvoker> tools;
    public ProgramCodeToolHandler(CodeExecutionToolHandler execution, ObjectProvider<ProgramToolInvoker> tools) {
        this.execution = execution; this.tools = tools;
    }
    @Override public String toolName() { return "run_code"; }
    @Override public Map<String,Object> execute(Map<String,Object> arguments, PlatformToolExecutionContext context) throws Exception {
        if(context.programChild()||context.workflowNode())throw new IllegalArgumentException("program_recursion_forbidden");
        if (!Set.of("code", "dependencies", "artifact_inputs", "attachment_ids", "input_files", "output_files", "use_workspace").containsAll(arguments.keySet())
                || !(arguments.get("code") instanceof String code) || code.isBlank())
            return Map.of("success", false, "error", "invalid_arguments");
        var args = new LinkedHashMap<>(arguments);
        args.put("mode", "inline"); args.put("language", "node");
        return execution.executeProgram(args, context, tools.getObject());
    }
}
