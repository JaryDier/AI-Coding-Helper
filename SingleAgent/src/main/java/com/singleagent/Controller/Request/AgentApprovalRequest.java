package com.singleagent.Controller.Request;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import lombok.Data;

@Data
public class AgentApprovalRequest {
    String id;
    String name;
    String arguments;
    String description;
    InterruptionMetadata.ToolFeedback.FeedbackResult approvalStatus;
}
