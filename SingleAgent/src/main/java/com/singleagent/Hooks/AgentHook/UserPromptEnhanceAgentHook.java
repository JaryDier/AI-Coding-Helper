package com.singleagent.Hooks.AgentHook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import com.singleagent.Prompt.PromptConstant;
import com.singleagent.Util.MemoryHelperUtil;
import com.singleagent.Util.StateConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;


@Component
@Slf4j
@RequiredArgsConstructor
@HookPositions({HookPosition.BEFORE_AGENT, HookPosition.AFTER_AGENT})
public class UserPromptEnhanceAgentHook extends AgentHook {

    private final ChatClient chatClient;
    private static final int MAX_NORMALIZED_TASK_CHARS = 240;

    @Override
    public String getName() {
        return "UserPromptEnhanceAgentHook";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {

        boolean isSkipEnhanceMessage = Boolean.TRUE.equals(
                config.metadata(StateConstant.SKIP_USER_PROMPT_ENHANCE).orElse(false));
        if (isSkipEnhanceMessage) {
            return CompletableFuture.completedFuture(Map.of());
        }

        //1、获取最近一条userMessage
        List<Message> messages = (List<Message>) state.value("messages").orElse(null);
        if(messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String userInput = "";
        Integer userInputIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof UserMessage userMessage) {
                userInput = userMessage.getText();
                userInputIndex = i;
                break;
            }
        }
        if(StringUtils.isBlank(userInput) || userInputIndex < 0) {
            return CompletableFuture.completedFuture(Map.of());
        }
        //2、增强
        log.error("original User Input ==> {}",userInput);
        String enhanceUserInput = enhanceUserInput(userInput);
        if(StringUtils.isBlank(enhanceUserInput)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        log.error("enhanceUserInput ==> {}",enhanceUserInput);

        //记录任务步骤到redis
        Optional<Object> conversationIdOpt = config.metadata(StateConstant.CONVERSATION_ID);
        if(conversationIdOpt.isEmpty()) {
            log.error("CONVERSATION_ID is empty");
            throw new IllegalArgumentException("conversationId is empty");
        }
        String conversationId = (String) conversationIdOpt.get();
        MemoryHelperUtil.normalizeUserTask.put(conversationId,enhanceUserInput);

        //3、替换消息
        List<Message> newMessages = new ArrayList<>(messages);
        newMessages.set(userInputIndex, new UserMessage(enhanceUserInput));
        return CompletableFuture.completedFuture(Map.of(
                "messages", ReplaceAllWith.of(newMessages)
        ));
    }

    private String enhanceUserInput(String originalText) {
        List<String> chunks = chatClient.prompt()
                .system(PromptConstant.AGENT_TASK_NORMALIZE_SYSTEM_PROMPT)
                .user("""
                        原始输入：
                        %s

                        将其拆解为 Agent 可分步骤执行任务说明。
                        """.formatted(originalText))
                .stream()
                .content()
                .collectList()
                .block();
        String normalizedTask = chunks == null ? "" : String.join("", chunks).trim();
        return limitText(normalizedTask, MAX_NORMALIZED_TASK_CHARS);
    }

    private String limitText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }
}
