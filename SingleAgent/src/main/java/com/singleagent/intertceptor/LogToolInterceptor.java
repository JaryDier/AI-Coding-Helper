package com.singleagent.intertceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LogToolInterceptor extends ToolInterceptor {

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
//        log.error("ToolInterceptor: Tool {} is called!", request.getToolName());
        return handler.call(request);
    }

    @Override
    public String getName() {
        return "LogToolInterceptor";
    }
}
