package com.singleagent.Config;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class OtherConfig {

    private final OllamaEmbeddingModel ollamaEmbeddingModel;

    @Bean
    public SimpleVectorStore simpleVectorStore() {
        return SimpleVectorStore.builder(ollamaEmbeddingModel).build();
    }
}
