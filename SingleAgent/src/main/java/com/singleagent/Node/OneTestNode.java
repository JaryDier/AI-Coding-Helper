package com.singleagent.Node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
@RequiredArgsConstructor
public class OneTestNode implements NodeActionWithConfig {
    @Override
    public Map<String, Object> apply(OverAllState state, RunnableConfig config) throws Exception {
        log.warn("dasdsadsaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        List<Message> messages = (List<Message>) state.value("messages").orElse(null);
        if(messages == null || messages.isEmpty()) {
            return Map.of();
        }
        messages.forEach(message -> {
            System.out.println("message: " + message);
        });
        return Map.of();
    }
}
