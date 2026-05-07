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
import lombok.RequiredArgsConstructor;
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

        AsyncNodeActionWithConfig supervisorNode = AsyncNodeActionWithConfig.node_async(((state, config) -> {
            Optional<Object> messagesOpt = state.value("messages");
            if (messagesOpt.isEmpty()) {
                throw new RuntimeException("messages is empty");
            }

            List<Message> messages = (List<Message>) messagesOpt.get();
            String latestUserText = getLatestMessageText(messages, UserMessage.class);
            String latestAssistantText = getLatestMessageText(messages, AssistantMessage.class);
            String recentConversation = buildRecentConversation(messages, 8);
            String currentInput = state.value("input")
                    .map(Object::toString)
                    .orElse(latestUserText);
            int loopCount = state.value("supervisor_loop_count")
                    .map(Object::toString)
                    .map(Integer::parseInt)
                    .orElse(0);

            String content = chatClient.prompt()
                    .system("""
                            你是代码任务监督节点，只负责判断 singleAgent 本轮输出是否已经真正完成用户的代码任务。
                            你不能继续执行任务，不能写代码，不能输出 Markdown，只能输出严格 JSON。

                            输出格式：
                            {
                              "status": "END" | "CONTINUE",
                              "reason": "简短说明判断原因",
                              "next_instruction": "如果 status=CONTINUE，给 singleAgent 的下一轮明确执行指令；如果 END，输出空字符串"
                            }

                            判断规则：
                            1. 如果用户只是咨询/解释，且 assistant 已经直接回答清楚，status=END。
                            2. 如果用户要求创建、修改、修复、运行、测试、查看文件、排查问题，但 assistant 只是给建议或计划，没有真实工具结果，status=CONTINUE。
                            3. 如果代码已经修改但没有合理验证结果，status=CONTINUE，并要求运行合适的验证命令。
                            4. 如果工具执行失败，且还有明确修复方向，status=CONTINUE。
                            5. 如果缺少用户必须补充的信息，assistant 已经明确向用户提问，status=END。
                            6. 如果已经完成真实操作、验证通过，或已经给出不能继续的明确原因，status=END。
                            7. next_instruction 必须具体、可执行，不要写泛泛的“继续完成任务”。
                            """)
                    .user("""
                            当前监督循环次数：
                            %d

                            当前输入：
                            %s

                            最新用户消息：
                            %s

                            最新 assistant 输出：
                            %s

                            最近对话：
                            %s

                            请判断任务是否完成。
                            """.formatted(
                            loopCount,
                            StringUtils.defaultString(currentInput),
                            StringUtils.defaultString(latestUserText),
                            StringUtils.defaultString(latestAssistantText),
                            recentConversation
                    ))
                    .call()
                    .content();

            JSONObject supervisorResult = parseSupervisorResult(content);
            if (loopCount + 1 >= MAX_SUPERVISOR_LOOP_COUNT
                    && !"END".equalsIgnoreCase(supervisorResult.getString("status"))) {
                supervisorResult.put("status", "END");
                supervisorResult.put("reason", "已达到监督循环最大次数，停止自动继续。"
                        + StringUtils.defaultString(supervisorResult.getString("reason")));
                supervisorResult.put("next_instruction", "");
            }

            String status = StringUtils.defaultIfBlank(supervisorResult.getString("status"), "CONTINUE");
            String nextInstruction = StringUtils.defaultString(supervisorResult.getString("next_instruction"));

            if ("END".equalsIgnoreCase(status)) {
                return Map.of(
                        "supervisor_result", supervisorResult.toJSONString(),
                        "supervisor_loop_count", loopCount + 1
                );
            }

            return Map.of(
                    "supervisor_result", supervisorResult.toJSONString(),
                    "supervisor_loop_count", loopCount + 1,
                    "messages", List.of(new UserMessage(buildContinueInput(latestUserText, nextInstruction)))
            );
        }));

        try {
            StateGraph stateGraph = new StateGraph(keyStrategyFactory)
                    .addNode("supervisorNode", supervisorNode)
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

                                JSONObject result = parseSupervisorResult(supervisorResult);
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

    private String getLatestMessageText(List<Message> messages, Class<? extends Message> messageType) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (messageType.isInstance(message)) {
                return StringUtils.defaultString(message.getText());
            }
        }
        return "";
    }

    private String buildRecentConversation(List<Message> messages, int maxCount) {
        int start = Math.max(0, messages.size() - maxCount);
        List<String> recentMessages = new ArrayList<>();
        for (int i = start; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof UserMessage) {
                recentMessages.add("用户：" + StringUtils.defaultString(message.getText()));
            } else if (message instanceof AssistantMessage assistantMessage) {
                String toolCalls = assistantMessage.hasToolCalls()
                        ? "\n工具调用：" + JSON.toJSONString(assistantMessage.getToolCalls())
                        : "";
                recentMessages.add("助手：" + StringUtils.defaultString(assistantMessage.getText()) + toolCalls);
            } else if (message instanceof ToolResponseMessage toolResponseMessage) {
                String toolResponses = toolResponseMessage.getResponses().stream()
                        .map(response -> "%s：%s".formatted(response.name(), response.responseData()))
                        .collect(Collectors.joining("\n"));
                recentMessages.add("工具结果：" + toolResponses);
            } else {
                recentMessages.add(message.getMessageType() + "：" + StringUtils.defaultString(message.getText()));
            }
        }
        return String.join("\n\n", recentMessages);
    }

    private JSONObject parseSupervisorResult(String content) {
        String json = normalizeJson(content);
        try {
            JSONObject result = JSON.parseObject(json);
            if (result == null) {
                return defaultSupervisorResult("监督节点返回为空。");
            }
            String status = StringUtils.defaultIfBlank(result.getString("status"), "CONTINUE");
            result.put("status", "END".equalsIgnoreCase(status) ? "END" : "CONTINUE");
            result.put("reason", StringUtils.defaultString(result.getString("reason")));
            result.put("next_instruction", StringUtils.defaultString(result.getString("next_instruction")));
            return result;
        } catch (Exception e) {
            return defaultSupervisorResult("监督节点 JSON 解析失败：" + StringUtils.defaultString(content));
        }
    }

    private JSONObject defaultSupervisorResult(String reason) {
        JSONObject result = new JSONObject();
        result.put("status", "CONTINUE");
        result.put("reason", reason);
        result.put("next_instruction", "根据用户原始需求和上一轮输出继续推进，必须使用工具完成真实操作，并在代码修改后执行合理验证。");
        return result;
    }

    private String normalizeJson(String content) {
        String json = StringUtils.trimToEmpty(content);
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```[a-zA-Z]*", "");
            json = json.replaceFirst("```$", "");
        }
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return json.substring(start, end + 1).trim();
        }
        return json;
    }

    private String buildContinueInput(String latestUserText, String nextInstruction) {
        return """
                上一轮尚未完全满足用户需求，请继续执行，不要只做总结。

                用户原始需求：
                %s

                下一步明确指令：
                %s

                约束：
                - 需要真实操作时必须调用工具。
                - 如果已经修改代码，必须运行合理验证命令。
                - 不要重复已经完成的部分。
                """.formatted(
                StringUtils.defaultString(latestUserText),
                StringUtils.defaultString(nextInstruction)
        );
    }
}
