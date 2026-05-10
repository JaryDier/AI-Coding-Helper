package com.singleagent.Controller;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.singleagent.Controller.Request.AgentApprovalRequest;
import com.singleagent.Controller.Request.AgentChatRequest;
import com.singleagent.Service.AgentService;
import com.singleagent.Service.MultiAgentOrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("agent")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final AgentService agentService;
    private final MultiAgentOrchestrationService multiAgentOrchestrationService;

    @PostMapping("/chatStream")
    public Flux<String> chatStream(@RequestBody AgentChatRequest agentChatRequest) throws GraphRunnerException {
        return agentService.streamChat2(agentChatRequest);
    }

//    @PostMapping("/multiAgent/chatStream")
//    public Flux<String> multiAgentChatStream(@RequestBody AgentChatRequest agentChatRequest) {
//        return multiAgentOrchestrationService.streamChat(agentChatRequest);
//    }

    @PostMapping("/approval")
    public Flux<String> approval(@RequestBody AgentApprovalRequest agentApprovalRequest) throws GraphRunnerException {
        return agentService.approval(agentApprovalRequest);
    }

}
