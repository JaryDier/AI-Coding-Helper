package com.singleagent.Hooks.MessageModelHook;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.networknt.schema.utils.StringUtils;
import com.singleagent.Prompt.PromptConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


@Slf4j
@Component
@HookPositions(value = HookPosition.BEFORE_MODEL)
@RequiredArgsConstructor
public class UserPromptStrengthHook extends MessagesModelHook {

    private static final int MAX_NORMALIZED_TASK_CHARS = 240;

    private final ChatClient chatClient;
    private final OpenAiChatModel openAiChatModel;

    @Override
    public String getName() {
        return "UserPromptStrengthHook";
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        //1、获取最近一条userMessage
        String buildEnhancedUserText = "";
        int lastIndex = previousMessages.size() - 1;
        for (int i = previousMessages.size() - 1; i >= 0; i--) {
            Message message = previousMessages.get(i);
            if(message instanceof UserMessage userMessage) {
                //2、规范化为细粒度执行步骤
                log.error("original User Input ==> {}",userMessage.getText());
                String normalizeUserTask = normalizeUserTask(userMessage.getText());
                if (StringUtils.isBlank(normalizeUserTask)) {
                    return new AgentCommand(previousMessages);
                }
                //可以补充：用redis存储增强后的用户输入
                buildEnhancedUserText =  normalizeUserTask;
                lastIndex = i;
                break;
            }
        }


//        log.info("enhanceUserMessage:{}",buildEnhancedUserText);

        //3、构建增强后的消息list
        List<Message> enhancedMessages = new ArrayList<>(previousMessages);
        if(StringUtils.isBlank(buildEnhancedUserText)) {
            return new AgentCommand(previousMessages);
        }
        Message message = previousMessages.get(lastIndex);
        UserMessage enhanceUserMessage = new UserMessage(buildEnhancedUserText);
        if(message instanceof UserMessage) {
            enhancedMessages.set(lastIndex, enhanceUserMessage);
        } else {
            enhancedMessages.add(enhanceUserMessage);
        }
        return new AgentCommand(enhancedMessages, UpdatePolicy.REPLACE);
    }

    private String normalizeUserTask(String originalText) {
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

//    private String buildEnhancedUserText(String originalText, String normalizedTask) {
//        return PromptConstant.USER_TASK_ENHANCE_USER_PROMPT.formatted(originalText, normalizedTask);
//    }

    private String limitText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }
}
