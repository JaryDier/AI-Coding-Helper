package com.singleagent.Model.MultiAgent;

import java.util.List;

public record AgentTask(
        String taskId,
        String agentName,
        String title,
        String instruction,
        List<String> dependsOn,
        String expectedOutput
) {
}
