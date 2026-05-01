package com.singleagent.Hooks.MessageModelHook;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Slf4j
@Component
@HookPositions(value = HookPosition.BEFORE_MODEL)
@RequiredArgsConstructor
public class MessageTrimmingHook extends MessagesModelHook {

    private static final int MAX_MESSAGE_COUNT = 40;
    private static final int MIN_RECENT_MESSAGE_COUNT = 8;
    private static final int MAX_CONTEXT_CHARS = 24_000;
    private static final int MAX_SINGLE_MESSAGE_CHARS = 6_000;
    private static final int MAX_RAG_TOP_K = 3;

    private final SimpleVectorStore simpleVectorStore;


    @Override
    public String getName() {
        return "messageTrimming";
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        previousMessages.forEach(message -> {
            System.out.println(message.getText());
        });

        //rag检索增强实现
        //1、提取本次的用户消息
        String userMessageText = "";
        for(int i = previousMessages.size() - 1; i >= 0; i--) {
            Message message = previousMessages.get(i);
            if (message instanceof UserMessage userMessage) {
                userMessageText = userMessage.getText();
                break;
            }
        }
        //2、通过向量库进行检索
        //2.1 相似度检索 初选
        List<Document> similaritySearchRs = simpleVectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(userMessageText)
                        .topK(MAX_RAG_TOP_K)
                        .filterExpression("fileName == 'rag.txt'")
                .build()
        );
        //3、构建增强的上下文
//        String systemStrength = similaritySearchRs.stream().map(Document::getText).collect(Collectors.joining());
//        String oldLatestSystemPrompt = "";
//        for(int i = previousMessages.size() - 1; i >= 0; i--) {
//            Message message = previousMessages.get(i);
//            if (message instanceof SystemMessage systemMessage) {
//                oldLatestSystemPrompt = systemMessage.getText();
//                break;
//            }
//        }
//        if(StringUtils.isBlank(oldLatestSystemPrompt)) {
//            previousMessages.add(new SystemMessage("""
//                    你是一个有用的助手。基于以下上下文回答问题。
//                    如果上下文中没有相关信息，请说明你不知道。上下文：
//                    """ + systemStrength));
//        } else {
//            previousMessages.add(new SystemMessage(oldLatestSystemPrompt +  systemStrength));
//        }
        //4、裁剪历史上下文，避免会话、工具输出、增强提示累积后超过模型上下文窗口。
        List<Message> trimmedMessages = trimMessages(previousMessages);
        if (trimmedMessages.size() != previousMessages.size()) {
            log.info("Trimmed messages from {} to {}, chars from {} to {}.",
                    previousMessages.size(),
                    trimmedMessages.size(),
                    estimateMessagesChars(previousMessages),
                    estimateMessagesChars(trimmedMessages));
        }
        return new AgentCommand(trimmedMessages,UpdatePolicy.REPLACE);
    }

    private List<Message> trimMessages(List<Message> previousMessages) {
        if (previousMessages == null || previousMessages.isEmpty()) {
            return previousMessages;
        }
        if (previousMessages.size() <= MAX_MESSAGE_COUNT
                && estimateMessagesChars(previousMessages) <= MAX_CONTEXT_CHARS) {
            return previousMessages;
        }

        List<Message> selected = new ArrayList<>();
        int totalChars = 0;
        boolean preservedSystemMessage = false;

        for (int i = previousMessages.size() - 1; i >= 0; i--) {
            Message message = shrinkLargeMessage(previousMessages.get(i));
            int messageChars = estimateMessageChars(message);
            boolean mustKeepRecent = selected.size() < MIN_RECENT_MESSAGE_COUNT;
            boolean withinBudget = selected.size() < MAX_MESSAGE_COUNT
                    && totalChars + messageChars <= MAX_CONTEXT_CHARS;

            if (mustKeepRecent || withinBudget) {
                selected.add(message);
                totalChars += messageChars;
                if (message instanceof SystemMessage) {
                    preservedSystemMessage = true;
                }
                continue;
            }

            // 如果历史里有 SystemMessage，尽量保留最近的一条，避免丢失运行期追加的关键约束。
            if (!preservedSystemMessage && message instanceof SystemMessage) {
                Message compactSystemMessage = new SystemMessage(limitText(message.getText(), MAX_SINGLE_MESSAGE_CHARS));
                selected.add(compactSystemMessage);
                totalChars += estimateMessageChars(compactSystemMessage);
                preservedSystemMessage = true;
            }
        }

        Collections.reverse(selected);
        return selected;
    }

    private Message shrinkLargeMessage(Message message) {
        if (estimateMessageChars(message) <= MAX_SINGLE_MESSAGE_CHARS) {
            return message;
        }

        if (message instanceof ToolResponseMessage toolResponseMessage) {
            List<ToolResponseMessage.ToolResponse> compactResponses = toolResponseMessage.getResponses()
                    .stream()
                    .map(response -> new ToolResponseMessage.ToolResponse(
                            response.id(),
                            response.name(),
                            limitText(response.responseData(), MAX_SINGLE_MESSAGE_CHARS)
                    ))
                    .toList();
            return ToolResponseMessage.builder()
                    .responses(compactResponses)
                    .metadata(toolResponseMessage.getMetadata())
                    .build();
        }

        if (message instanceof UserMessage) {
            return new UserMessage(limitText(message.getText(), MAX_SINGLE_MESSAGE_CHARS));
        }

        if (message instanceof SystemMessage) {
            return new SystemMessage(limitText(message.getText(), MAX_SINGLE_MESSAGE_CHARS));
        }

        return message;
    }

    private int estimateMessagesChars(List<Message> messages) {
        return messages.stream().mapToInt(this::estimateMessageChars).sum();
    }

    private int estimateMessageChars(Message message) {
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            return toolResponseMessage.getResponses()
                    .stream()
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .filter(Objects::nonNull)
                    .mapToInt(String::length)
                    .sum();
        }
        return Objects.toString(message.getText(), "").length();
    }

    private String limitText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        int headChars = Math.max(0, maxChars - 120);
        return text.substring(0, headChars)
                + "\n\n[内容过长，已在进入模型前截断，原始长度="
                + text.length()
                + " chars]";
    }
}
