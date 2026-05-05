package com.singleagent.Model.MultiAgent;

public record AgentTaskResult(
        String taskId,
        String agentName,
        String title,
        TaskStatus status,
        String output,
        String errorMessage
) {
}
