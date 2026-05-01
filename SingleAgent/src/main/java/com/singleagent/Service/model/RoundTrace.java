package com.singleagent.Service.model;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Data;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Data
public class RoundTrace {
    boolean hasToolCall = false;
    boolean hasToolResult = false;
    boolean hasShellCommand = false;
//    boolean hasValidation = false;
    List<String> toolNames = new ArrayList<>();

    public void record(NodeOutput nodeOutput) {
        if(nodeOutput instanceof StreamingOutput<?> streamingOutput){
            OutputType outputType = streamingOutput.getOutputType();

            if(outputType == OutputType.AGENT_TOOL_STREAMING || outputType == OutputType.AGENT_TOOL_FINISHED) {
                hasToolCall = true;
                hasToolResult = true;
            }

            Message message = streamingOutput.message();
            if (message instanceof AssistantMessage assistantMessage
                    && assistantMessage.hasToolCalls()) {
                hasToolCall = true;
                assistantMessage.getToolCalls()
                        .forEach(call -> toolNames.add(call.name()));
            }

            if (message instanceof ToolResponseMessage toolResponseMessage) {
                String data = toolResponseMessage.getResponses()
                        .stream()
                        .map(ToolResponseMessage.ToolResponse::responseData)
                        .collect(Collectors.joining("\n"));

//                if (data.contains("mvn")
//                        || data.contains("test")
//                        || data.contains("compile")
//                        || data.contains("BUILD SUCCESS")) {
//                    hasValidation = true;
//                }
            }

        }
    }

}
