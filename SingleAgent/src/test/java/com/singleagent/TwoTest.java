package com.singleagent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.singleagent.Util.StateConstant;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Slf4j
@SpringBootTest
public class TwoTest {

    @Autowired
    private CompiledGraph codeGraph;

    @Test
    public void test() {
        RunnableConfig runnableConfig = RunnableConfig.builder()
                .threadId("1")
                .addMetadata(StateConstant.CONVERSATION_ID, "1")
                .addMetadata(StateConstant.SKIP_USER_PROMPT_ENHANCE, false)
                .build();

        String userInput = "这是我项目的根目录，分析项目F:\\javaLearn\\AI\\AICodingTest,然后添加图书管理功能，包括基础的增删改查，同时要支持根据书名模糊增删改查";
        Flux<NodeOutput> stream = codeGraph.stream(Map.of(
                        "input", userInput,
                        "messages", List.of(new UserMessage(userInput))),
                runnableConfig);

        stream.doOnNext(output -> {
                    log.warn(output.state().toString());
                    if (output instanceof StreamingOutput<?> streamingOutput) {
                        OutputType type = streamingOutput.getOutputType();

                        if (type == OutputType.AGENT_MODEL_STREAMING) {
                            System.out.print(streamingOutput.message().getText());
                        } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                            System.out.println("\n模型输出完成");
                        }

                        if (type == OutputType.AGENT_TOOL_FINISHED) {
                            System.out.println("工具调用完成: " + output.node());
                        }

                        if (type == OutputType.AGENT_HOOK_FINISHED) {
                            System.out.println("Hook 执行完成: " + output.node());
                        }
                    }
                })
                .doOnError(error -> System.err.println("错误: " + error))
                .doOnComplete(() -> System.out.println("Agent 执行完成"))
                .blockLast();
    }
}
