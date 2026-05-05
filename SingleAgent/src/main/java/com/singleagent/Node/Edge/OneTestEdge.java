package com.singleagent.Node.Edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeActionWithConfig;

import java.util.concurrent.CompletableFuture;

public class OneTestEdge implements AsyncEdgeActionWithConfig {
    @Override
    public CompletableFuture<String> apply(OverAllState state, RunnableConfig runnableConfig) {

        return null;
    }
}
