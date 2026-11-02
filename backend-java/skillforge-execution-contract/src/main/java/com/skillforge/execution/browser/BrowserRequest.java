package com.skillforge.execution.browser;
import java.time.Instant;

/** 仅在认证后的宿主与浏览器服务之间传递的调用信封。 */
public record BrowserRequest(String tenant, String endUser, String invocation, String task,
        String operation, BrowserParameters.Parameters parameters, String session, String run, Instant deadlineAt) {
    public BrowserRequest {
        if(tenant==null || !tenant.matches("[1-9][0-9]*") || endUser==null || !endUser.matches("[1-9][0-9]*"))
            throw new IllegalArgumentException("browser_owner_required");
        if(deadlineAt==null)throw new IllegalArgumentException("browser_deadline_required");
        if(invocation==null || invocation.isBlank()) throw new IllegalArgumentException("browser_invocation_required");
        if(parameters==null||!operation.equals(parameters.operation()))throw new IllegalArgumentException("browser_parameters_required");
    }
    @Override public String toString(){return "BrowserRequest[operation="+operation+"]";}
}
