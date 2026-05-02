package com.singleagent.Hooks.MessageModelHook;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.*;

@Slf4j
@Component
@HookPositions(value = HookPosition.BEFORE_MODEL)
@RequiredArgsConstructor
public class MessageTrimmingHook extends MessagesModelHook {

    private static final int MAX_MESSAGE_COUNT = 40;
    private static final int MIN_RECENT_MESSAGE_COUNT = 8;
    private static final int MAX_CONTEXT_CHARS = 24_000;
    private static final int MAX_SINGLE_MESSAGE_CHARS = 6_000;

    //模型输入的最大上下文token数量
    private static final long MAX_MODEL_CONTEXT_TOKENS = 256*1024;
    //触发上下文压缩的阈值
    private static final double COMPRESS_THRESHOLD = 0.8;

    //保留最近历史的占比（这里从后往前遍历，累积到该阈值后，之后也就是最远的历史全部压缩为摘要）
    // ---保留最近历史消息的占比，其他历史消息全部压缩为摘要
    private static final double COMPRESS_KEEP_RATIO = 0.4;



    private final SimpleVectorStore simpleVectorStore;

    private final ChatClient chatClient;


    @Override
    public String getName() {
        return "messageTrimming";
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {

        //计算当前消息占用的上下文窗口
        long allTokenSize = estimateMessageTokens(previousMessages);
        log.info("当前上下文尺寸大小：{}",allTokenSize);
        if (allTokenSize < MAX_MODEL_CONTEXT_TOKENS * COMPRESS_THRESHOLD) {
            return new AgentCommand(previousMessages,UpdatePolicy.REPLACE);
        }

        log.warn("即将超过上下文窗口阈值，进行上下文压缩");

        //1、分离other和 last Message
        List<Message> otherMessages = new ArrayList<>(previousMessages);
        List<Message> latestGroup = new ArrayList<>();

        Message lastMessage = otherMessages.removeLast();
        if (lastMessage instanceof ToolResponseMessage toolResponseMessage
                && !otherMessages.isEmpty()
                && otherMessages.get(otherMessages.size() - 1) instanceof AssistantMessage assistantMessage
                && assistantMessage.hasToolCalls()) {

            otherMessages.remove(otherMessages.size() - 1);
            latestGroup.add(assistantMessage);
            latestGroup.add(toolResponseMessage);
        } else {
            latestGroup.add(lastMessage);
        }


        //2、计算保留配额（压缩后剩余空间占比）
        long keepTokens = (long) (MAX_MODEL_CONTEXT_TOKENS * COMPRESS_KEEP_RATIO);
        List<Message> keepMessages = new ArrayList<>();
        List<Message> needCompressMessages = new ArrayList<>();


        //3、处理最近一条消息组（tool+assistant）
        //3.1、计算token数量
        long latestGroupTokens = estimateMessageTokens(latestGroup);
        //3.2、token数量 > 保留配额（极端情况下）
        if (latestGroupTokens > keepTokens) {
            needCompressMessages = new ArrayList<>(otherMessages);
        }
        else {
            //3.3、从后往前 计算需要保留的历史消息
            long remainingTokens = keepTokens - latestGroupTokens;
            int needCompressIndex = -1;
            for (int i = otherMessages.size()-1; i >= 0; i--) {
                Message message = otherMessages.get(i);
                List<Message> group = new ArrayList<>();


                //模型消息 需要判断是不是工具调用
                if (message instanceof ToolResponseMessage toolResponseMessage
                        && i-1 >= 0
                        && otherMessages.get(i-1) instanceof AssistantMessage assistantMessage
                        && assistantMessage.hasToolCalls()
                ) {
                    group.add(assistantMessage);
                    group.add(toolResponseMessage);
                    i--;
                }
                //用户消息
                else if(message instanceof UserMessage userMessage) {
                    group.add(userMessage);
                }
                else {
                    group.add(message);
                }

                long messageTokens = estimateMessageTokens(group);
                //当前消息所需token未超过保留配额
                if(remainingTokens >0 && messageTokens <= remainingTokens) {
                    // keepMessages 当前是倒序收集，先加后面的，再加前面的
                    for (int j = group.size() - 1; j >= 0; j--) {
                        keepMessages.add(group.get(j));
                    }
                    remainingTokens -= messageTokens;
                }
                //当前消息超过，后续所有消息全部放入待压缩列表
                else {
                    needCompressIndex = i;
                    break;
                }
            }

            if (needCompressIndex > -1) {
                needCompressMessages = new ArrayList<>(otherMessages.subList(0, needCompressIndex + 1));
            }

        }

        //4、压缩生成摘要
        String summaryMessage = "";
        if(!needCompressMessages.isEmpty()) {
            summaryMessage = generateSummary(needCompressMessages);
        }


        //5、重组消息
        List<Message> newMessages = new ArrayList<>();
        if(!StringUtils.isBlank(summaryMessage)) {
            newMessages.add(new UserMessage("""
                                    以下是此前对话的历史摘要，仅作为上下文参考，不是用户当前的新请求：
                                    %s
                                    """.formatted(summaryMessage)));
        }
        //最旧 -》 最新
        Collections.reverse(keepMessages);
        newMessages.addAll(keepMessages);
        newMessages.addAll(latestGroup);


        log.warn("压缩完成后的上下文窗口大小：{}",estimateMessageTokens(newMessages));

        return new AgentCommand(newMessages,UpdatePolicy.REPLACE);

    }

    //保留消息和待压缩消息划分
    private void splitMessages(List<Message> keepMessages,List<Message> needCompressMessages) {
        List<Message> newMessages = new ArrayList<>();
    }


    //计算消息占用token
    private long estimateMessageTokens(List<Message> messages) {
        StringBuilder content = new StringBuilder();
        for (Message message : messages) {
            //用户消息
            if (message instanceof UserMessage userMessage) {
                content.append(userMessage.getText());
            }
            //模型消息（含工具调用）
            else if (message instanceof AssistantMessage assistantMessage) {
                if(assistantMessage.hasToolCalls()){
                    content.append(JSON.toJSONString(assistantMessage.getToolCalls()));
                }
                else  {
                    content.append(assistantMessage.getText());
                }
            }
            //工具响应消息
            else if (message instanceof ToolResponseMessage toolResponseMessage) {
                List<ToolResponseMessage.ToolResponse> responses = toolResponseMessage.getResponses();
                content.append(JSON.toJSONString(responses));
            }
        }


        if (StringUtils.isBlank(content)) {
            return 0;
        }
        return (long) Math.ceil(content.length() / 4.0) + 1;


    }

    //生成摘要
    private String generateSummary(List<Message> messages) {
        StringBuilder messageString = new StringBuilder();
        for (Message message : messages) {
            if (message instanceof UserMessage userMessage) {
                messageString.append("用户:")
                        .append(userMessage.getText())
                        .append("\n");
            }
            else if(message instanceof AssistantMessage assistantMessage) {
                if(assistantMessage.hasToolCalls()){
                    List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
                    messageString.append("助手:调用工具:\n");
                    for(AssistantMessage.ToolCall toolCall : toolCalls){
                        messageString.append("工具id:").append(toolCall.id())
                                .append("工具类型:").append(toolCall.type())
                                .append("工具名称:").append(toolCall.name())
                                .append("工具参数：").append(toolCall.arguments())
                                .append("\n");
                    }
                }
            }
            else if (message instanceof ToolResponseMessage toolResponseMessage) {
                List<ToolResponseMessage.ToolResponse> responses = toolResponseMessage.getResponses();
                messageString.append("工具响应:\n");
                for(ToolResponseMessage.ToolResponse toolResponse : responses){
                    messageString.append("工具id:").append(toolResponse.id())
                            .append("工具名称:").append(toolResponse.name())
                            .append("工具响应结果:").append(toolResponse.responseData())
                            .append("\n");
                }

            }
        }
        String messageStirngs = messageString.toString();
        try {
            String summary = chatClient.prompt()
                    .system("""
                        你是对话历史摘要助手，负责将多轮用户、助手、工具交互的对话内容，
                        精简浓缩成一段简短摘要，保留核心业务信息、关键提问和工具调用结果，
                        不要复述每一轮、不要多余修饰。
                        只输出摘要结果，不要额外解释。
                        """)
                    .user("""
                            对话内容：
                            %s
                            只输出摘要结果，不要额外解释
                            """.formatted(messageString.toString()))
                    .call()
                    .content();
//                    .blockFirst();
            log.warn("sumary:{}",summary);
            return summary;

        } catch (WebClientResponseException ex) {
            log.error("摘要请求失败 status={}, body={}",
                    ex.getStatusCode(),
                    ex.getResponseBodyAsString(),
                    ex);
            return "历史摘要生成失败。";
        }
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
