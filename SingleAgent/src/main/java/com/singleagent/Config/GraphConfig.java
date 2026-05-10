package com.singleagent.Config;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeActionWithConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.singleagent.Node.SuperviseNode;
import com.singleagent.Util.JsonUtil;
import lombok.RequiredArgsConstructor;
import net.bytebuddy.implementation.bind.annotation.Super;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Configuration
@RequiredArgsConstructor
public class GraphConfig {

    private static final int MAX_SUPERVISOR_LOOP_COUNT = 5;

    private final ChatClient chatClient;

    private final SuperviseNode superviseNode;

    @Bean
    public MemorySaver graphMemorySaver() {
        return new MemorySaver();
    }

    @Bean
    public CompiledGraph codeGraph(ReactAgent singleAgent) {

        KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                .addStrategies(Map.of(
                        "messages", new AppendStrategy(),
                        "input", new ReplaceStrategy(),
                        "supervisor_result", new ReplaceStrategy(),
                        "supervisor_loop_count", new ReplaceStrategy()
                )).build();


        try {
            StateGraph stateGraph = new StateGraph(keyStrategyFactory)
                    .addNode("supervisorNode",  AsyncNodeActionWithConfig.node_async(superviseNode))
                    .addNode("code_review_agent", singleAgent.asNode(true, false))
                    .addEdge(StateGraph.START, "code_review_agent")
                    .addEdge("code_review_agent", "supervisorNode")
                    .addConditionalEdges(
                            "supervisorNode",
                            AsyncEdgeActionWithConfig.edge_async(((state, runnableConfig) -> {
                                Optional<Object> supervisorResultOpt = state.value("supervisor_result");
                                if (supervisorResultOpt.isEmpty()) {
                                    return "CONTINUE";
                                }
                                String supervisorResult = (String) supervisorResultOpt.get();
                                if (StringUtils.isBlank(supervisorResult)) {
                                    return "CONTINUE";
                                }

                                String json = JsonUtil.normalizeJson(supervisorResult);
                                JSONObject result = JSON.parseObject(json, JSONObject.class);
                                if ("END".equalsIgnoreCase(result.getString("status"))) {
                                    return "END";
                                }
                                return "CONTINUE";
                            })),
                            Map.of(
                                    "CONTINUE", "code_review_agent",
                                    "END", StateGraph.END
                            ));

            CompileConfig compileConfig = CompileConfig.builder()
                    .saverConfig(SaverConfig.builder().register(graphMemorySaver()).build())
                    .build();

            return stateGraph.compile(compileConfig);

        } catch (GraphStateException e) {
            throw new RuntimeException(e);
        }
    }
}
