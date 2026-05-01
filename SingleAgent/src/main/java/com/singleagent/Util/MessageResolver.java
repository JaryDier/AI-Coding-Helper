package com.singleagent.Util;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;

import com.singleagent.Controller.Response.AgentChatResponse;
import com.singleagent.Controller.Response.InterruptMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@Slf4j
public class MessageResolver {

    //消息解析
    public static String messageResolve(NodeOutput nodeOutput) {
        AgentChatResponse agentChatResponse = new AgentChatResponse();


        //工具人工中断返回
        if(nodeOutput instanceof InterruptionMetadata interruptionMetadata){
            List<InterruptionMetadata.ToolFeedback> interruptedToolFeedbacks = interruptionMetadata.toolFeedbacks();
            List<InterruptMessage>  interruptMessages = new ArrayList<>();
            interruptedToolFeedbacks.forEach(toolFeedback -> {
                InterruptMessage interruptMessage = new InterruptMessage();
                BeanUtils.copyProperties(toolFeedback,interruptMessage);
                interruptMessages.add(interruptMessage);
            });
            agentChatResponse.setInterruptMessages(interruptMessages);
//            return JSON.toJSONString(agentChatResponse);
            return agentChatResponse.getMessage();
        }

        //正常输出
        else if(nodeOutput instanceof StreamingOutput<?> streamingOutput){
            OutputType outputType = streamingOutput.getOutputType();
            Message message = streamingOutput.message();
            String rs = "";
            switch (outputType){
                case AGENT_MODEL_STREAMING -> {
                    //模型消息下
                    if(message instanceof AssistantMessage assistantMessage){
                        //推理消息
                        if(assistantMessage instanceof DeepSeekAssistantMessage deepSeekAssistantMessage) {
                            String resTest = deepSeekAssistantMessage.getText();
                            rs = StringUtils.defaultString(resTest);
                        } else {
                            rs = assistantMessage.getText();
                        }

                        boolean hasToolCalls = assistantMessage.hasToolCalls();
                        if(hasToolCalls){
                            log.error("toolCalls = {}",assistantMessage.getToolCalls());
                        }
                    }
                }
                case AGENT_MODEL_FINISHED ->  {
                    if(message instanceof AssistantMessage assistantMessage){
                        log.info("AGENT_MODEL_FINISHED：模型推理完成");
                    }
                }
                case AGENT_TOOL_STREAMING -> {
                    if(message instanceof ToolResponseMessage toolResponseMessage) {
                        List<ToolResponseMessage.ToolResponse> responses = toolResponseMessage.getResponses();
                        String collected = responses.stream().map(toolResponse -> {
                            return Objects.toString(toolResponse.responseData(), "");
                        }).collect(Collectors.joining());
                        agentChatResponse.setMessage(collected);
                        rs = collected;
                    }
                }
                case AGENT_TOOL_FINISHED ->  {
                    log.info("AGENT_TOOL_FINISHED：工具执行完成");
                }
            }
            if (StringUtils.isNotBlank(rs)) {
                agentChatResponse.setMessage(rs);
//                return JSON.toJSONString(agentChatResponse);
                return agentChatResponse.getMessage();
            } else {
                return "";
            }
        }
        return "";
    }
}
