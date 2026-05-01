package com.singleagent.Controller.Response;

import lombok.Data;

import java.util.List;

@Data
public class AgentChatResponse {
    String message;
    List<InterruptMessage> interruptMessages;
}

