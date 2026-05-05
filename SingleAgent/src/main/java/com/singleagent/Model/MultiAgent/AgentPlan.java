package com.singleagent.Model.MultiAgent;

import java.util.List;

public record AgentPlan(
        String goal,
        List<AgentTask> tasks
) {
}
