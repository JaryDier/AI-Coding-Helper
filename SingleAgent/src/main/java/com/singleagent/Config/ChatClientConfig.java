package com.singleagent.Config;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class ChatClientConfig {
    private final OpenAiApi  openAiApi;

    @Bean
    public ChatClient chatClient() {

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("gpt-5.5")
                .build();

        OpenAiChatModel chatClientModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();

        return ChatClient.builder(chatClientModel)
                .build();
    }
}
