package com.singleagent.Hooks.ModelHook;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.singleagent.Prompt.PromptConstant;
import com.singleagent.Util.StateConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
@HookPositions(value = HookPosition.AFTER_MODEL)
@RequiredArgsConstructor
public class AiMessagePendingActionExtractHook extends ModelHook {

    private static final int MAX_PENDING_ACTION_CHARS = 240;

    private final ChatClient chatClient;

    @Override
    public String getName() {
        return "";
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        //1、获取消息
        Optional<Object> messagesOpt = state.value("messages");
        if(messagesOpt.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        List<Message> messages = (List<Message>) messagesOpt.get();
        if(messages.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        Message lastMessage = messages.getLast();
        //1.1
        //非模型消息 不需要额外添加什么状态
        if(!(lastMessage instanceof AssistantMessage assistantMessage)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        //1.2
        //工具消息 也不需要
        if(assistantMessage.hasToolCalls()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String aiText = assistantMessage.getText();

        if(StringUtils.isBlank(aiText)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        //2、调用chatClient进行ai建议提取 暂存状态
        Optional<String> extractedAiPendingAction = extractAiPendingAction(aiText);
        if(extractedAiPendingAction.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        //设置ai 建议行为key的更新策略为替换
        Map<String, KeyStrategy> stringKeyStrategyMap = state.keyStrategies();
        stringKeyStrategyMap.put(StateConstant.PENDING_ACTION_KEY,KeyStrategy.REPLACE);
        //存储pendingAction到OverAllState中
        return CompletableFuture.completedFuture(Map.of(
                StateConstant.PENDING_ACTION_KEY, extractedAiPendingAction.get()
        ));
    }

    public Optional<String> extractAiPendingAction(String aiText) {

        if(StringUtils.isBlank(aiText)) {
            return Optional.empty();
        }

        List<String> pendingActionDescriptionChunks = chatClient.prompt()
                .system(PromptConstant.EXTRACT_AI_PENDING_ACTION_SYSTEM_PROMPT)
                .user("""
                        assistant回复如下:
                        %s
                        
                        请将其中的可继续执行建议细化成Agent可执行步骤
                        """.formatted(aiText))
                .stream()
                .content()
                .collectList()
                .block();
        String pendingActionDescription = pendingActionDescriptionChunks == null
                ? ""
                : String.join("", pendingActionDescriptionChunks);


        if(StringUtils.isBlank(pendingActionDescription)) {
            return Optional.empty();
        }
        pendingActionDescription = pendingActionDescription.trim();
        if ("NONE".equalsIgnoreCase(pendingActionDescription)
                || pendingActionDescription.contains("无可继续执行建议")) {
            return Optional.empty();
        }
        return Optional.of(limitText(pendingActionDescription, MAX_PENDING_ACTION_CHARS));
    }

    private String limitText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }
}
