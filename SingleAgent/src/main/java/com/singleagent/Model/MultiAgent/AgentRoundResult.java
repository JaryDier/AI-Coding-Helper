package com.singleagent.Model.MultiAgent;

import java.util.List;

public record AgentRoundResult(
        String roundOutput,
        List<AgentTaskResult> taskResults,
        boolean completed,
        String nextInstruction
) {
}
