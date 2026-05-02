package com.singleagent.Hooks.MessageModelHook;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.networknt.schema.utils.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
@HookPositions(value = {HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL})
@RequiredArgsConstructor
public class RagMessageHook extends MessagesModelHook {

    private static final int MAX_RAG_TOP_K = 3;
    private final SimpleVectorStore simpleVectorStore;

    @Override
    public String getName() {
        return "RagMessageHook";
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
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
        String systemStrength = similaritySearchRs.stream().map(Document::getText).collect(Collectors.joining());
        String oldLatestSystemPrompt = "";
        for(int i = previousMessages.size() - 1; i >= 0; i--) {
            Message message = previousMessages.get(i);
            if (message instanceof SystemMessage systemMessage) {
                oldLatestSystemPrompt = systemMessage.getText();
                break;
            }
        }
        if(StringUtils.isBlank(oldLatestSystemPrompt)) {
            previousMessages.add(new SystemMessage(systemStrength + """
                    基于以下上下文回答问题。
                    如果上下文中没有相关信息，请说明你不知道。上下文：
                    """));
        } else {
            previousMessages.add(new SystemMessage(oldLatestSystemPrompt +  systemStrength));
        }

        return new AgentCommand(previousMessages, UpdatePolicy.REPLACE);
    }
}
