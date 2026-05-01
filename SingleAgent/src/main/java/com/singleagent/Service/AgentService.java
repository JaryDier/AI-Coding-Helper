package com.singleagent.Service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.fastjson.JSON;
import com.singleagent.Controller.Request.AgentApprovalRequest;
import com.singleagent.Controller.Request.AgentChatRequest;
import com.singleagent.Service.model.CompletionJudgeResult;
import com.singleagent.Service.model.RoundTrace;
import com.singleagent.Util.MemoryHelperUtil;
import com.singleagent.Util.MessageResolver;
import com.singleagent.Util.StateConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentService {

    private final ReactAgent singleAgent;

    private final ChatClient chatClient;

    private static final Integer MAX_LOOP_COUNT = 5;



    //流式对话
    public Flux<String> streamChat(AgentChatRequest chatRequest) throws GraphRunnerException {
        if(chatRequest == null || StringUtils.isBlank(chatRequest.getContent())){
            return Flux.empty();
        }
        if (StringUtils.isBlank(chatRequest.getThreadId()) || StringUtils.isBlank(chatRequest.getConversationId())) {
            return Flux.just("参数thread、conversationId 不能为空");
        }
        RunnableConfig runnableConfig = RunnableConfig.builder()
                .threadId(chatRequest.getThreadId())
                .addMetadata(StateConstant.CONVERSATION_ID,chatRequest.getConversationId())
                .build();
        return runAgentRound(
                chatRequest.getContent(),
                runnableConfig,
                0
                );
    }

    //循环触发agent
    private Flux<String> runAgentRound(
            String currentInput,
            RunnableConfig runnableConfig,
            int round
    ) {
        log.info("第 {} 次",round);
        RoundTrace trace = new RoundTrace();

        try {
            return singleAgent.stream(currentInput,runnableConfig)
                    .doOnNext(trace::record)
                    .map(MessageResolver::messageResolve)
                    .filter(StringUtils::isNotBlank)
                    .collectList()
                    .flatMapMany(outputs -> {
                        String roundOutput = String.join("",outputs);

                        String enhanceOriginalInput = MemoryHelperUtil.normalizeUserTask.get(StateConstant.CONVERSATION_ID);

                        //判断本次返回是否完成用户需求
                        CompletionJudgeResult judgeResult = judgeCompletionByChatClient(enhanceOriginalInput, roundOutput, trace);
                        if(judgeResult.isCompleted()) {
                            log.debug("成功完成需求{}",roundOutput);
                            //完成 返回
                            return Flux.just(roundOutput);
                        }

                        //超过最大次数，直接返回
                        if(round >= MAX_LOOP_COUNT){
                            log.error("超过最大循环轮次还依然没有执行完需求:roundOutput->{}\n,next_instruction->{}",roundOutput,judgeResult.getNext_instruction());
                            return Flux.fromIterable(outputs)
                                    .concatWith(Flux.just("""
                                    已达到最大自动执行轮次，自动继续已停止。
                                    未完成原因或下一步建议：%s
                                    """.formatted(judgeResult.getNext_instruction())));
                        }
                        log.info("目前完成部分：output -> {}\\n,接下来需要做的next_instruction -> {}",roundOutput,judgeResult.getNext_instruction());
                        //未完成 构建新的用户消息 再次请求
                        String nextUserInput = buildContinuePrompt(enhanceOriginalInput,judgeResult.getNext_instruction(),trace);
                        return runAgentRound(nextUserInput,runnableConfig,round+1);
                    });
        } catch (GraphRunnerException e) {
            throw new RuntimeException(e);
        }
    }

    private CompletionJudgeResult judgeCompletionByChatClient(String enhanceOriginalInput,String currentRoundOutput,RoundTrace trace) {
        String resultJson = chatClient.prompt()
                .system(
                        """
                                你是任务完成度判断器。只输出JSON，不要解释。
                                格式：{"completed":true|false,"next_instruction":"不超过80字"}
                                规则：
                                1. 输出只是计划/建议/询问继续，completed=false。
                                2. 用户要求真实操作但未调用工具，completed=false。
                                3. 用户要求代码修改但无验证结果，completed=false。
                                4. 已明确完成或已向用户询问必要阻塞信息，completed=true。
                                """
                )
                .user(
                        """
                            用户原始需求：
                            %s
        
                            本轮工具调用：
                            %s
       
                            本轮输出：
                            %s
                            """
                            .formatted(
                                    enhanceOriginalInput,
                                    trace.getToolNames(),
                                    currentRoundOutput
                            )
                )
                .stream()
                .content()
                .collectList()
                .map(list -> String.join("",list))
                .block();
        return JSON.parseObject(resultJson, CompletionJudgeResult.class);

    }

    //构建下一轮用户输入
    private String buildContinuePrompt(String enhanceOriginalInput,String nextUserInput,RoundTrace trace) {
        return """
                上一轮尚未满足用户原始需求，请继续执行，不要最终总结。
                
                用户原始需求：
                %s
    
                下一步：
                %s
    
                约束：
                - 需要真实操作时必须调用工具。
                - 如果已经修改代码，必须运行合理验证命令。
                - 不要重复上一轮自然语言总结。
                """
                .formatted(enhanceOriginalInput,nextUserInput);
    }

    //人工介入批准
    public Flux<String> approval(AgentApprovalRequest agentApprovalRequest) {
        if (agentApprovalRequest == null || StringUtils.isBlank(agentApprovalRequest.getName())) {
            return Flux.just("ok");
        }
        //构建人工批准后的工具反馈
        InterruptionMetadata approvedToolFeedBack = InterruptionMetadata.builder()
                .addToolFeedback(
                        InterruptionMetadata.ToolFeedback.builder()
                                .id(agentApprovalRequest.getId())
                                .name(agentApprovalRequest.getName())
                                .arguments(agentApprovalRequest.getArguments())
                                .description(agentApprovalRequest.getDescription())
                                .result(agentApprovalRequest.getApprovalStatus())
                                .build()
                ).build();

        log.info("工具反馈批准成功，继续执行");
        RunnableConfig runnableConfig = RunnableConfig.builder()
                .addHumanFeedback(approvedToolFeedBack)
                .threadId("1")
                .build();

        try {
            return singleAgent.stream("",runnableConfig).map(MessageResolver::messageResolve);
        } catch (GraphRunnerException e) {
            return Flux.just(e.getLocalizedMessage());
        }

    }


}
