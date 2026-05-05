package com.singleagent;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeActionWithConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.action.NodeActionWithConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.AgentStateFactory;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.singleagent.Node.OneTestNode;
import com.singleagent.Util.StateConstant;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import javax.swing.plaf.nimbus.State;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Slf4j
@SpringBootTest
public class GraphTest {

    @Autowired
    public OneTestNode  oneTestNode;
    @Autowired
    public ReactAgent singleAgent;

    @Test
    public void testGraph() throws GraphStateException, InterruptedException {

        StateGraph stateGraph = new StateGraph();
        stateGraph.addNode("OneTest", AsyncNodeActionWithConfig.node_async(oneTestNode));
        stateGraph.addNode(singleAgent.name(),singleAgent.asNode(true,true));
        stateGraph.addEdge(StateGraph.START,singleAgent.name());
        stateGraph.addEdge(singleAgent.name(),"OneTest");
        stateGraph.addEdge("OneTest", StateGraph.END);

        CompiledGraph compiledGraph = stateGraph.compile(
                CompileConfig.builder()
                        .saverConfig(
                                SaverConfig
                                        .builder()
                                        .register(new MemorySaver())
                                        .build()
                        )
                        .build()
        );
        RunnableConfig runnableConfig = RunnableConfig.builder().threadId("1").addMetadata(StateConstant.CONVERSATION_ID, "1")
                .addMetadata(StateConstant.SKIP_USER_PROMPT_ENHANCE, false).build();
        compiledGraph.stream(
                Map.of(
                        "input","你是谁",
                        "messages", List.of(new UserMessage("你是谁"))
                        ),
                runnableConfig
               ).blockLast();

        List<StateSnapshot> stateHistory = (List<StateSnapshot>)compiledGraph.getStateHistory(runnableConfig);
        for (int i = 0; i < stateHistory.size(); i++) {
            StateSnapshot snapshot = stateHistory.get(i);
            System.out.printf("Step %d: %s\n", i, snapshot.state());
            System.out.printf("  Checkpoint ID: %s\n", snapshot.config().checkPointId().orElse("N/A"));
            System.out.printf("  Node: %s\n", snapshot.node());
        }

        log.warn("基于历史状态创建新分支------------------------------------------------");
        RunnableConfig newRunnableConfig = RunnableConfig.builder()
                .checkPointId(stateHistory.getLast().config().checkPointId().orElse("N/A"))
                .threadId("2")
                .build();
        compiledGraph.stream(
                Map.of(
                        "input","大厦的扩散的空间三打死都不能撒看见你的",
                        "messages", List.of(new UserMessage("大厦的扩散的空间三打死都不能撒看见你的"))
                ),
                newRunnableConfig
        ).blockLast();
        List<StateSnapshot> stateHistory2 = (List<StateSnapshot>)compiledGraph.getStateHistory(newRunnableConfig);

        for (int i = 0; i < stateHistory2.size(); i++) {
            StateSnapshot snapshot = stateHistory2.get(i);
            System.out.printf("Step %d: %s\n", i, snapshot.state());
            System.out.printf("  Checkpoint ID: %s\n", snapshot.config().checkPointId().orElse("N/A"));
            System.out.printf("  Node: %s\n", snapshot.node());
        }


        Thread.sleep(60000);

    }
}
