package com.singleagent.Config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@RequiredArgsConstructor
public class EmbeddingConfig {

    private final OllamaEmbeddingModel ollamaEmbeddingModel;

    @Value("classpath:rag.txt")
    private Resource resource;

    private final SimpleVectorStore simpleVectorStore;

    @PostConstruct
    public void init() {
        //1、加载文档
        TextReader textReader = new TextReader(resource);
        List<Document> originalDocuments = textReader.read();
        //2、转换文档 以适应模型上下文窗口
        //2.1、构建文档转换器
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkLengthToEmbed(10)
                .withMaxNumChunks(1000)
                .withKeepSeparator(true)
                .build();
        //2.2、文档转换
        List<Document> transferDocuments = splitter.split(originalDocuments).stream().map(document -> {
            Map<String, Object> metadata = document.getMetadata();
            metadata.put("fileName", resource.getFilename());
            return document;
        }).collect(Collectors.toList());
        //3、转换向量 并存储
        simpleVectorStore.add(transferDocuments);
    }

}
