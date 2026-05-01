package com.singleagent.Controller;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.singleagent.Controller.Request.AgentApprovalRequest;
import com.singleagent.Controller.Request.AgentChatRequest;
import com.singleagent.Service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("agent")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final AgentService agentService;

    @PostMapping("/chatStream")
    public Flux<String> chatStream(@RequestBody AgentChatRequest agentChatRequest) throws GraphRunnerException {
        return agentService.streamChat(agentChatRequest);
    }

    @PostMapping("/approval")
    public Flux<String> approval(@RequestBody AgentApprovalRequest agentApprovalRequest) throws GraphRunnerException {
        return agentService.approval(agentApprovalRequest);
    }

}
