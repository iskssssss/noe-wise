package com.skillforge.platform.config;

import com.skillforge.core.shared.web.HostTraceIdFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** 注册共享请求上下文与追踪生命周期边界。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends HostTraceIdFilter {
    public TraceIdFilter(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        super(errors);
    }
}
