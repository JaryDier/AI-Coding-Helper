package com.singleagent.Controller.Request;

import lombok.Data;

@Data
public class AgentChatRequest {

    String threadId;
    String conversationId;
    String content;
}
