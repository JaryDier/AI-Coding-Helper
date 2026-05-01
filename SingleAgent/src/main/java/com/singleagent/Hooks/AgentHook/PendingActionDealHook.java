package com.singleagent.Hooks.AgentHook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import com.singleagent.Prompt.PromptConstant;
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
import java.util.concurrent.CompletableFuture;


@Component
@Slf4j
@RequiredArgsConstructor
@HookPositions({HookPosition.BEFORE_AGENT, HookPosition.AFTER_AGENT})
public class PendingActionDealHook extends AgentHook {

    private final ChatClient chatClient;

    @Override
    public String getName() {
        return "PendingActionDealHook";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        //1、获取用户消息
        List<Message> messages = (List<Message>) state.value("messages").orElse(null);
        if(messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String lastUserText= null;
        int lastUserIndex = -1;
        for (int i = messages.size()-1; i >= 0; i--) {
            Message message = messages.get(i);
            if(message instanceof UserMessage userMessage) {
                lastUserText = userMessage.getText();
                lastUserIndex = i;
                break;
            }
        }
        if(StringUtils.isBlank(lastUserText)) {
            return CompletableFuture.completedFuture(Map.of());
        }


        //2、判断OverAllStata中是否存在对应pendingAction的key
        Map<String, Object> data = state.data();
        if(data == null || data.isEmpty() || !data.containsKey(StateConstant.PENDING_ACTION_KEY)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String normalizedPendingAction = (String)data.get(StateConstant.PENDING_ACTION_KEY);


        //3、模型分析用户是否同意
        List<String> userSuggestChunks = chatClient.prompt()
                .system("""
                        你只负责判断用户当前输入是否是在确认执行一个待确认动作。
                        只输出 CONFIRM、REJECT 或 UNRELATED。
                        不要解释。
                        """)
                .user(PromptConstant.JUDGMENT_IS_CONFIRMED_PENDING_ACTION_USER_PROMPT.formatted(
                        normalizedPendingAction, lastUserText
                ))
                .stream()
                .content()
                .collectList()
                .block();
        String userSuggest = userSuggestChunks == null ? "" : String.join("", userSuggestChunks).trim();
        if(StringUtils.isBlank(userSuggest)) {
            return CompletableFuture.completedFuture(Map.of());
        }


        String enhancedUserText = "";
        switch (userSuggest) {
            //确认
            case "CONFIRM": {
                log.info("CONFIRM");
              enhancedUserText= PromptConstant.CONFIRM_EXECUTE_PENDING_ACTION_SYSTEM_MESSAGE.formatted(
                            normalizedPendingAction
                      );
              break;
            }
            //拒绝
            case "REJECT": {
                log.info("REJECT");
                enhancedUserText = """
                            用户当前输入表示拒绝或取消上一轮待确认动作。
                            已取消的待确认的规范化执行任务：
                            %s
                            接下来请不要继续执行该动作。
                            如果用户当前输入中还有新的明确需求，请只处理新的明确需求。
                            否则只需要简短确认已取消。
                        """.formatted(normalizedPendingAction);
                break;
            }
            //默认 换话题
            default: {
                log.info("DEFAULT");
                return CompletableFuture.completedFuture(Map.of());
            }
        }

        List<Message> enhancedMessages = new ArrayList<>(messages);
        enhancedMessages.set(lastUserIndex, new UserMessage(enhancedUserText));
        return CompletableFuture.completedFuture(Map.of(
                "messages", ReplaceAllWith.of(enhancedMessages)
        ));
    }
}
