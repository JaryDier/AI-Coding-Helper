package com.singleagent.Service;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.store.stores.MemoryStore;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.singleagent.Controller.Request.AgentChatRequest;
import com.singleagent.Model.MultiAgent.AgentPlan;
import com.singleagent.Model.MultiAgent.AgentTask;
import com.singleagent.Model.MultiAgent.AgentTaskResult;
import com.singleagent.Model.MultiAgent.TaskStatus;
import com.singleagent.Service.model.CompletionJudgeResult;
import com.singleagent.Util.MessageResolver;
import com.singleagent.Util.StateConstant;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MultiAgentOrchestrationService {

    private static final int MAX_PARALLEL_TASKS = 3;
    private static final int MAX_LOOP_COUNT = 5;

    private final ReactAgent plannerAgent;
    private final ReactAgent codeReviewAgent;
    private final ReactAgent codeProgramAgent;
    private final ReactAgent codeExecTestAgent;
    private final ChatClient chatClient;

    public MultiAgentOrchestrationService(
            @Qualifier("plannerAgent") ReactAgent plannerAgent,
            @Qualifier("codeReviewAgent") ReactAgent codeReviewAgent,
            @Qualifier("codeProgramAgent") ReactAgent codeProgramAgent,
            @Qualifier("codeExecTestAgent") ReactAgent codeExecTestAgent,
            ChatClient chatClient
    ) {
        this.plannerAgent = plannerAgent;
        this.codeReviewAgent = codeReviewAgent;
        this.codeProgramAgent = codeProgramAgent;
        this.codeExecTestAgent = codeExecTestAgent;
        this.chatClient = chatClient;
    }

    public Flux<String> streamChat(AgentChatRequest chatRequest) {
        if (chatRequest == null || StringUtils.isBlank(chatRequest.getContent())) {
            return Flux.empty();
        }
        if (StringUtils.isBlank(chatRequest.getThreadId()) || StringUtils.isBlank(chatRequest.getConversationId())) {
            return Flux.just("参数 threadId、conversationId 不能为空");
        }

        return executeRound(
                chatRequest.getContent(),
                chatRequest.getContent(),
                chatRequest.getConversationId(),
                chatRequest.getThreadId(),
                UUID.randomUUID().toString(),
                0
        );
    }


    private Flux<String> executeRound2(
            String originInput,
            String latestRoundOutput,
            RunnableConfig runnableConfig,
            int round
    ) throws GraphRunnerException {

        if (round > MAX_LOOP_COUNT) {
            return Flux.just("\n 有达到最大自动执行次数，自动停止等地人工介入");
        }

        Flux<String> planStream = plannerAgent.stream(originInput, runnableConfig).map(MessageResolver::messageResolve);
        return planStream.publish(share -> Flux.merge(
                //计划输出流1 直接返回 展示计划
                share,
                //流2 执行计划
                share.collectList().flatMapMany(nodeOutputs -> {
                    String randOutput = StringUtils.join(nodeOutputs, "\n");
                    CompletionJudgeResult completionJudgeResult = judgeCompletion(originInput,randOutput);
                    if (completionJudgeResult.isCompleted()) {
                        return Flux.just("\n[summary] 多 Agent 编排已完成。\n");
                    }

                    if (round + 1 >= MAX_LOOP_COUNT) {
                        return Flux.just("""

                                            [summary] 已达到最大自动执行轮次，自动继续已停止。
                                            未完成原因或下一步建议：%s
                                            """.formatted(StringUtils.defaultString(completionJudgeResult.getNext_instruction())));
                    }

                    String nextRoundInput = buildContinuePrompt(originInput, randOutput, completionJudgeResult.getNext_instruction());
                    try {
                        return Flux.concat(
                                Flux.just("da"),
                                executeRound2(originInput,latestRoundOutput,runnableConfig,round+1)
                        );
                    } catch (GraphRunnerException e) {
                        throw new RuntimeException(e);
                    }
                }).onErrorResume(e -> {
                    return Flux.just(e.getMessage());
                })
        ));


    }


    private Flux<String> executeRound(
            String originalInput,
            String currentInput,
            String conversationId,
            String parentThreadId,
            String planId,
            int round
    ) {
        if (round >= MAX_LOOP_COUNT) {
            return Flux.just("\n已达到最大自动执行轮次，自动继续已停止。");
        }

        return generatePlan(currentInput, conversationId, parentThreadId, planId, round)
                .flatMapMany(plan -> {
                    Flux<String> roundStream = Flux.concat(
                            Flux.just(formatPlan(plan, round)),
                            executePlanAsText(plan, conversationId, parentThreadId, planId, round)
                    );

                    return roundStream.publish(shared -> Flux.merge(
                            shared,
                            shared.collectList().flatMapMany(outputs -> {
                                String roundOutput = String.join("", outputs);
                                CompletionJudgeResult judgeResult = judgeCompletion(originalInput, roundOutput);

                                if (judgeResult.isCompleted()) {
                                    return Flux.just("\n[summary] 多 Agent 编排已完成。\n");
                                }

                                if (round + 1 >= MAX_LOOP_COUNT) {
                                    return Flux.just("""

                                            [summary] 已达到最大自动执行轮次，自动继续已停止。
                                            未完成原因或下一步建议：%s
                                            """.formatted(StringUtils.defaultString(judgeResult.getNext_instruction())));
                                }

                                String nextInput = buildContinuePrompt(originalInput, roundOutput, judgeResult.getNext_instruction());
                                return Flux.just("""

                                                [summary] 本轮尚未完全满足需求，准备进入下一轮。
                                                下一步：%s
                                                """.formatted(StringUtils.defaultString(judgeResult.getNext_instruction())))
                                        .concatWith(executeRound(
                                                originalInput,
                                                nextInput,
                                                conversationId,
                                                parentThreadId,
                                                planId,
                                                round + 1
                                        ));
                            })
                    ));
                })
                .onErrorResume(ex -> {
                    log.error("多 Agent 编排执行失败", ex);
                    return Flux.just("\n[multi-agent-error] " + ex.getMessage());
                });
    }

    private Mono<AgentPlan> generatePlan(
            String userInput,
            String conversationId,
            String parentThreadId,
            String planId,
            int round
    ) {
        return Mono.defer(() -> {
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(parentThreadId + "::planner::round::" + round)
                    .store(new MemoryStore())
                    .addMetadata(StateConstant.CONVERSATION_ID, conversationId)
                    .addMetadata(StateConstant.PARENT_THREAD_ID, parentThreadId)
                    .addMetadata(StateConstant.PLAN_ID, planId)
                    .addMetadata(StateConstant.SKIP_USER_PROMPT_ENHANCE, true)
                    .build();

            try {
                return plannerAgent.stream(userInput, config)
                        .map(MessageResolver::messageResolve)
                        .filter(StringUtils::isNotBlank)
                        .collectList()
                        .map(chunks -> String.join("", chunks))
                        .map(this::parseAgentPlan)
                        .map(this::validatePlan);
            } catch (GraphRunnerException e) {
                return Mono.error(e);
            }
        });
    }

    private Flux<String> executePlanAsText(
            AgentPlan plan,
            String conversationId,
            String parentThreadId,
            String planId,
            int round
    ) {
        List<List<AgentTask>> batches = splitIntoBatches(plan.tasks());
        Map<String, AgentTaskResult> contextResults = new ConcurrentHashMap<>();

        Flux<TaskStreamEvent> events = Flux.empty();
        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            List<AgentTask> batch = batches.get(batchIndex);
            int currentBatchIndex = batchIndex;
            Flux<TaskStreamEvent> batchEvents = Flux.defer(() -> Flux.concat(
                    Flux.just(TaskStreamEvent.text("\n[batch:%d] 开始执行，任务数：%d\n".formatted(currentBatchIndex + 1, batch.size()))),
                    Flux.fromIterable(batch)
                            .flatMap(task -> executeTaskAsEvents(
                                    task,
                                    contextResults,
                                    conversationId,
                                    parentThreadId,
                                    planId,
                                    round
                            ), MAX_PARALLEL_TASKS)
                            .doOnNext(event -> {
                                if (event.result() != null) {
                                    contextResults.put(event.result().taskId(), event.result());
                                }
                            })
            ));
            events = events.concatWith(batchEvents);
        }

        return events.map(TaskStreamEvent::text).filter(StringUtils::isNotBlank);
    }

    private Flux<TaskStreamEvent> executeTaskAsEvents(
            AgentTask task,
            Map<String, AgentTaskResult> previousResults,
            String conversationId,
            String parentThreadId,
            String planId,
            int round
    ) {
        if (hasBlockedDependency(task, previousResults)) {
            AgentTaskResult skipped = new AgentTaskResult(
                    task.taskId(),
                    task.agentName(),
                    task.title(),
                    TaskStatus.SKIPPED,
                    "",
                    "依赖任务未成功，跳过当前任务"
            );
            return Flux.just(TaskStreamEvent.result(formatTaskResult(skipped), skipped));
        }

        ReactAgent agent = agentMap().get(task.agentName());
        String taskInput = buildTaskInput(task, previousResults);
        RunnableConfig config = RunnableConfig.builder()
                .threadId(parentThreadId + "::round::" + round + "::task::" + task.taskId())
                .store(new MemoryStore())
                .addMetadata(StateConstant.CONVERSATION_ID, conversationId)
                .addMetadata(StateConstant.TASK_ID, task.taskId())
                .addMetadata(StateConstant.PARENT_THREAD_ID, parentThreadId)
                .addMetadata(StateConstant.PLAN_ID, planId)
                .addMetadata(StateConstant.SKIP_USER_PROMPT_ENHANCE, true)
                .build();

        return Flux.defer(() -> {
            StringBuilder output = new StringBuilder();
            Flux<TaskStreamEvent> contentStream;
            try {
                contentStream = agent.stream(taskInput, config)
                        .map(MessageResolver::messageResolve)
                        .filter(StringUtils::isNotBlank)
                        .doOnNext(output::append)
                        .map(chunk -> TaskStreamEvent.text("[task:%s] %s".formatted(task.taskId(), chunk)));
            } catch (GraphRunnerException e) {
                return Flux.just(buildFailedTaskEvent(task, e));
            }

            return Flux.concat(
                    Flux.just(TaskStreamEvent.text("\n[task:%s] 开始：%s\n".formatted(task.taskId(), task.title()))),
                    contentStream,
                    Mono.fromSupplier(() -> {
                        AgentTaskResult result = new AgentTaskResult(
                                task.taskId(),
                                task.agentName(),
                                task.title(),
                                TaskStatus.SUCCESS,
                                output.toString(),
                                null
                        );
                        return TaskStreamEvent.result(formatTaskResult(result), result);
                    })
            ).onErrorResume(ex -> Flux.just(buildFailedTaskEvent(task, ex)));
        });
    }

    private TaskStreamEvent buildFailedTaskEvent(AgentTask task, Throwable ex) {
        AgentTaskResult failed = new AgentTaskResult(
                task.taskId(),
                task.agentName(),
                task.title(),
                TaskStatus.FAILED,
                "",
                ex.getMessage()
        );
        return TaskStreamEvent.result(formatTaskResult(failed), failed);
    }

    private AgentPlan parseAgentPlan(String content) {
        String json = normalizeJson(content);
        JSONObject object = JSON.parseObject(json);
        JSONArray taskArray = object.getJSONArray("tasks");
        List<AgentTask> tasks = new ArrayList<>();
        if (taskArray != null) {
            for (int i = 0; i < taskArray.size(); i++) {
                JSONObject task = taskArray.getJSONObject(i);
                tasks.add(new AgentTask(
                        firstNotBlank(task.getString("taskId"), task.getString("task_id")),
                        firstNotBlank(task.getString("agentName"), task.getString("agent_name")),
                        task.getString("title"),
                        task.getString("instruction"),
                        parseDependsOn(task),
                        firstNotBlank(task.getString("expectedOutput"), task.getString("expected_output"))
                ));
            }
        }
        return new AgentPlan(object.getString("goal"), tasks);
    }

    private AgentPlan validatePlan(AgentPlan plan) {
        if (plan == null || CollectionUtils.isEmpty(plan.tasks())) {
            throw new IllegalArgumentException("Planner 未生成有效任务");
        }

        Set<String> allowedAgents = agentMap().keySet();
        Set<String> taskIds = new HashSet<>();
        for (AgentTask task : plan.tasks()) {
            if (StringUtils.isBlank(task.taskId())) {
                throw new IllegalArgumentException("任务 taskId 不能为空");
            }
            if (!taskIds.add(task.taskId())) {
                throw new IllegalArgumentException("任务 taskId 重复: " + task.taskId());
            }
            if (!allowedAgents.contains(task.agentName())) {
                throw new IllegalArgumentException("不支持的 agentName: " + task.agentName());
            }
        }

        for (AgentTask task : plan.tasks()) {
            for (String dep : defaultList(task.dependsOn())) {
                if (!taskIds.contains(dep)) {
                    throw new IllegalArgumentException("任务依赖不存在: " + dep);
                }
            }
        }

        checkNoCycle(plan.tasks());
        return plan;
    }

    private void checkNoCycle(List<AgentTask> tasks) {
        splitIntoBatches(tasks);
    }

    private List<List<AgentTask>> splitIntoBatches(List<AgentTask> tasks) {
        List<List<AgentTask>> batches = new ArrayList<>();
        Set<String> completed = new HashSet<>();
        List<AgentTask> remaining = new ArrayList<>(tasks);

        while (!remaining.isEmpty()) {
            List<AgentTask> readyTasks = remaining.stream()
                    .filter(task -> completed.containsAll(defaultList(task.dependsOn())))
                    .toList();

            if (readyTasks.isEmpty()) {
                throw new IllegalArgumentException("任务依赖存在循环或无法满足");
            }

            batches.add(readyTasks);
            readyTasks.forEach(task -> completed.add(task.taskId()));
            remaining.removeAll(readyTasks);
        }

        return batches;
    }

    private CompletionJudgeResult judgeCompletion(String originalUserInput, String roundOutput) {
        String resultJson = chatClient.prompt()
                .system("""
                        你是多 Agent 任务完成度判断助手。
                        只输出 JSON，不要 Markdown，不要解释。
                        输出格式：
                        {"completed":true|false,"next_instruction":"如果未完成，给出下一轮明确执行指令；已完成则为空字符串"}
                        判断规则：
                        1. 用户需求已经被真实执行或已经明确无法继续时，completed=true。
                        2. 只是计划、建议、分析但没有完成用户要求的真实操作，completed=false。
                        3. 需要代码修改但没有验证结果，通常 completed=false。
                        4. 子任务失败但仍有明确下一步修复动作，completed=false。
                        """)
                .user("""
                        原始用户需求：
                        %s

                        本轮多 Agent 执行结果：
                        %s
                        """.formatted(originalUserInput, roundOutput))
                .stream()
                .content()
                .collectList()
                .map(chunks -> String.join("", chunks))
                .block();

        try {
            CompletionJudgeResult result = JSON.parseObject(normalizeJson(resultJson), CompletionJudgeResult.class);
            if (result == null) {
                return defaultUncompleted("完成度判断为空，请根据原始需求继续推进。");
            }
            return result;
        } catch (Exception e) {
            log.warn("多 Agent 完成度判断 JSON 解析失败: {}", resultJson, e);
            return defaultUncompleted("完成度判断 JSON 解析失败，请根据原始需求继续推进。");
        }
    }

    private CompletionJudgeResult defaultUncompleted(String nextInstruction) {
        CompletionJudgeResult result = new CompletionJudgeResult();
        result.setCompleted(false);
        result.setNext_instruction(nextInstruction);
        return result;
    }

    private String buildTaskInput(AgentTask task, Map<String, AgentTaskResult> previousResults) {
        String dependencyContext = defaultList(task.dependsOn()).stream()
                .map(previousResults::get)
                .filter(Objects::nonNull)
                .map(result -> """
                        依赖任务：%s
                        执行状态：%s
                        输出内容：
                        %s
                        """.formatted(result.taskId(), result.status(), result.output()))
                .collect(Collectors.joining("\n\n"));

        return """
                你正在执行一个被主 Agent 分配的子任务。

                任务 ID：
                %s

                任务标题：
                %s

                任务指令：
                %s

                期望输出：
                %s

                依赖任务结果：
                %s

                约束：
                - 只完成当前任务，不要扩展执行未被要求的任务。
                - 如果当前任务需要真实操作，必须调用你可用的工具。
                - 如果当前任务只是分析，请不要修改代码。
                - 输出要清晰说明本任务完成情况。
                """.formatted(
                task.taskId(),
                task.title(),
                task.instruction(),
                task.expectedOutput(),
                StringUtils.defaultIfBlank(dependencyContext, "无")
        );
    }

    private String buildContinuePrompt(String originalInput, String roundOutput, String nextInstruction) {
        return """
                上一轮多 Agent 执行尚未完全满足用户原始需求，请继续规划并执行下一轮。

                用户原始需求：
                %s

                上一轮执行结果：
                %s

                下一步：
                %s

                约束：
                - 不要重复已经成功完成的任务。
                - 只规划仍然缺失的任务。
                - 需要真实操作时必须交给合适的执行 Agent。
                """.formatted(originalInput, roundOutput, StringUtils.defaultString(nextInstruction));
    }

    private String formatPlan(AgentPlan plan, int round) {
        String tasks = plan.tasks().stream()
                .map(task -> "- %s -> %s：%s，依赖：%s"
                        .formatted(task.taskId(), task.agentName(), task.title(), defaultList(task.dependsOn())))
                .collect(Collectors.joining("\n"));
        return """
                [plan] 第 %d 轮任务计划：%s
                %s
                """.formatted(round + 1, StringUtils.defaultString(plan.goal()), tasks);
    }

    private String formatTaskResult(AgentTaskResult result) {
        if (result.status() == TaskStatus.SUCCESS) {
            return "\n[task:%s] 完成：%s\n".formatted(result.taskId(), result.title());
        }
        return "\n[task:%s] %s：%s\n".formatted(
                result.taskId(),
                result.status(),
                StringUtils.defaultString(result.errorMessage())
        );
    }

    private boolean hasBlockedDependency(AgentTask task, Map<String, AgentTaskResult> previousResults) {
        for (String dep : defaultList(task.dependsOn())) {
            AgentTaskResult depResult = previousResults.get(dep);
            if (depResult == null || depResult.status() != TaskStatus.SUCCESS) {
                return true;
            }
        }
        return false;
    }

    private Map<String, ReactAgent> agentMap() {
        Map<String, ReactAgent> agents = new HashMap<>();
        agents.put("code_review_agent", codeReviewAgent);
        agents.put("code_program_agent", codeProgramAgent);
        agents.put("code_exec_test_agent", codeExecTestAgent);
        return agents;
    }

    private List<String> parseDependsOn(JSONObject task) {
        JSONArray dependsOn = task.getJSONArray("dependsOn");
        if (dependsOn == null) {
            dependsOn = task.getJSONArray("depends_on");
        }
        if (dependsOn == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < dependsOn.size(); i++) {
            String dep = dependsOn.getString(i);
            if (StringUtils.isNotBlank(dep)) {
                result.add(dep);
            }
        }
        return result;
    }

    private List<String> defaultList(List<String> list) {
        return list == null ? List.of() : list;
    }

    private String firstNotBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : second;
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

    private record TaskStreamEvent(String text, AgentTaskResult result) {

        static TaskStreamEvent text(String text) {
            return new TaskStreamEvent(text, null);
        }

        static TaskStreamEvent result(String text, AgentTaskResult result) {
            return new TaskStreamEvent(text, result);
        }
    }
}
