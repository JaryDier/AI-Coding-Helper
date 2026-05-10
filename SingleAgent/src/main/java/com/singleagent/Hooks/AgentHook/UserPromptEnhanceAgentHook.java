package com.singleagent.Hooks.AgentHook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import com.singleagent.Prompt.PromptConstant;
import com.singleagent.Util.MemoryHelperUtil;
import com.singleagent.Util.StateConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;


@Component
@Slf4j
@RequiredArgsConstructor
@HookPositions({HookPosition.BEFORE_AGENT, HookPosition.AFTER_AGENT})
public class UserPromptEnhanceAgentHook extends AgentHook {

    private final ChatClient chatClient;
//    private static final int MAX_NORMALIZED_TASK_CHARS = 240;

    @Override
    public String getName() {
        return "UserPromptEnhanceAgentHook";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {

        boolean isSkipEnhanceMessage = Boolean.TRUE.equals(
                config.metadata(StateConstant.SKIP_USER_PROMPT_ENHANCE).orElse(false));
        if (isSkipEnhanceMessage) {
            return CompletableFuture.completedFuture(Map.of());
        }

        //1、获取最近一条userMessage
        List<Message> messages = (List<Message>) state.value("messages").orElse(null);
        if(messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String userInput = "";
        Integer userInputIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof UserMessage userMessage) {
                userInput = userMessage.getText();
                userInputIndex = i;
                break;
            }
        }
        if(StringUtils.isBlank(userInput) || userInputIndex < 0) {
            return CompletableFuture.completedFuture(Map.of());
        }
        //2、获取最近几条message上下文 进行增强
        log.error("original User Input ==> {}",userInput);
        List<Message> latestMessagesContext = new ArrayList<>();
        for (int i = 0; i < userInputIndex; i++) {
            latestMessagesContext.add(messages.get(i));
        }
        String enhanceUserInput = enhanceUserInput(userInput,latestMessagesContext);
        if(StringUtils.isBlank(enhanceUserInput)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        log.error("enhanceUserInput ==> {}",enhanceUserInput);

        //记录任务步骤到redis
        Optional<Object> conversationIdOpt = config.metadata(StateConstant.CONVERSATION_ID);
        if(conversationIdOpt.isEmpty()) {
            log.error("CONVERSATION_ID is empty");
            throw new IllegalArgumentException("conversationId is empty");
        }
        String conversationId = (String) conversationIdOpt.get();
        MemoryHelperUtil.normalizeUserTask.put(conversationId,enhanceUserInput);

        //3、替换消息
        List<Message> newMessages = new ArrayList<>(messages);
        newMessages.set(userInputIndex, new UserMessage(enhanceUserInput));
        return CompletableFuture.completedFuture(Map.of(
                "messages", ReplaceAllWith.of(newMessages)
        ));
    }

    private String enhanceUserInput(String userInput,List<Message> latestMessageContext) {

        StringBuilder contextAbstractBuilder = new StringBuilder();
        if (latestMessageContext == null || latestMessageContext.isEmpty()) {
            latestMessageContext = List.of();
        }

        //超出最近四条 需要提取摘要
        List<Message> needExtractAbstractMessages = new LinkedList<>();
        int recentStartIndex = Math.max(0, latestMessageContext.size() - 4);
        for (int i = 0; i < recentStartIndex; i++) {
            needExtractAbstractMessages.add(latestMessageContext.get(i));
        }
        if (!needExtractAbstractMessages.isEmpty()) {
            contextAbstractBuilder.append("历史上下文摘要：\n");
            contextAbstractBuilder.append(extractContextAbstract(needExtractAbstractMessages));
            contextAbstractBuilder.append("\n\n");
        }

        //最近四条
        contextAbstractBuilder.append("最近 4 条原始上下文：\n");
        for (int i = recentStartIndex; i < latestMessageContext.size(); i++) {
            Message message = latestMessageContext.get(i);
            if (message instanceof UserMessage userMessage) {
                contextAbstractBuilder.append("[user]：\n" + userMessage.getText() + "\n");
            }
            if (message instanceof AssistantMessage assistantMessage) {
                contextAbstractBuilder.append("[assistant]：\n" + assistantMessage.getText() + "\n");
            }
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                contextAbstractBuilder.append("[tool]：\n" + toolResponseMessage.getText() + "\n");
            }
        }


        List<String> chunks = chatClient.prompt()
                .system("""
                        你是“用户输入意图归一化器”，负责结合对话摘要、最近对话上下文和当前用户输入，将当前用户这一轮真实意图改写成 Agent 可直接执行的任务说明。

                        你的职责：
                        1. 只归一化当前用户输入，不回答用户问题，不执行任务，不输出代码，不输出 Markdown。
                        2. 必须优先理解当前用户输入与最近上下文的关系。
                        3. 如果当前用户输入是在回答上一轮 assistant 的确认项、澄清问题或选项问题，例如“是”“都可以”“1是 2是 3是”“按第一个”“继续”“确认”，必须结合最近上下文还原成完整、明确、可执行的需求。
                        4. 如果当前用户输入是新任务，则按新任务归一化，不要强行关联旧上下文。
                        5. 如果上下文摘要与最近 4 条消息冲突，以最近 4 条消息为准；如果最近上下文不足，再参考摘要。
                        6. 不要编造上下文中没有出现的文件名、接口、类名、字段、命令、工具结果或实现细节。
                        7. 不要把“最近对话上下文”本身当成用户的新需求；它只用于消解代词、简称、确认回复和省略表达。
                        8. 如果当前输入仍然无法确定真实意图，输出一个“普通问答”任务，目标是向用户询问最少量的必要澄清信息。

                        输出必须使用中文，并严格按以下格式：

                        任务类型：普通问答 / 代码修改 / 文件操作 / Shell操作 / 调试排错 / 项目分析 / 其他
                        任务目标：一句话说明最终要完成什么。
                        关键约束：列出用户明确确认或上下文中已确定的范围、接口、字段、路径、技术栈、输出格式、限制条件；没有则写“无”。

                        执行步骤：
                        1. 使用【工具名称】对【操作对象】执行【具体动作】，得到【预期结果】。
                        2. 使用【工具名称】对【操作对象】执行【具体动作】，得到【预期结果】。
                        3. 使用【工具名称】对【操作对象】执行【具体动作】，得到【预期结果】。

                        完成标准：
                        - 写明任务完成后应满足的可验证结果。
                        - 如果是代码修改任务，必须包含定位文件、读取上下文、最小修改、验证结果。
                        - 如果是用户确认上一轮方案，完成标准必须继承上一轮被确认的具体规则。

                        可用工具名称只能从以下名称中选择：
                        文件读取工具、文件搜索工具、文件写入工具、Shell执行工具、项目结构查看工具、代码分析工具、普通回答工具、无需工具。
                        """)
                .user("""
                        最近对话上下文：
                        %s
                        
                        当前用户输入：
                        %s

                        将其拆解为 Agent 可分步骤执行任务说明。
                        """.formatted(contextAbstractBuilder.toString().trim(),userInput))
                .stream()
                .content()
                .collectList()
                .block();
        return chunks == null ? "" : String.join("", chunks).trim();
    }

    //提取上下文摘要
    private String extractContextAbstract(List<Message> latestMessageContext) {

        StringBuilder latestMessageContextStr = new StringBuilder();
        for (Message message : latestMessageContext) {
            if (message instanceof UserMessage userMessage) {
                latestMessageContextStr.append("用户输入：" + userMessage.getText() + "\n");
            }
            if (message instanceof AssistantMessage assistantMessage) {
                latestMessageContextStr.append("输出：" + assistantMessage.getText() + "\n");
            }
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                latestMessageContextStr.append("工具调用结果：" + toolResponseMessage.getText() + "\n");
            }
        }

        List<String> chunks = chatClient.prompt()
                .system("""
                        你是“对话工作记忆摘要器”，负责把较早的多轮对话压缩成后续 Agent 理解用户意图所需的上下文摘要。

                        摘要目标：
                        1. 帮助后续模型理解当前用户输入中的省略、代词、确认回复、继续执行、方案选择等含义。
                        2. 保留已经确定的任务目标、实现边界、用户确认过的规则、待澄清项、待执行动作和当前进度。
                        3. 保留关键工程信息：项目名或模块名、文件路径、类名、方法名、接口路径、字段名、配置项、命令、错误信息、测试结论。
                        4. 工具调用结果只保留结论和关键证据，不要保留大段原始输出。

                        必须保留的信息：
                        - 用户最终想完成什么。
                        - 用户明确同意、拒绝、选择或修改过的方案。
                        - assistant 曾经提出但等待用户确认/补充的问题。
                        - 已经查看、修改、创建、验证过的文件或模块。
                        - 已发现的约束、风险、报错、失败原因和验证结果。
                        - 最近任务推进到哪一步，下一步应该继续什么。

                        必须避免：
                        - 不要回答用户，不要执行任务，不要提出新计划。
                        - 不要编造原文没有出现的路径、类名、接口、字段、命令或结果。
                        - 不要保留寒暄、重复解释、无关闲聊和大段代码/日志原文。
                        - 不要丢失编号选项与其含义，例如“1/2/3 分别是什么”，这类信息对后续确认回复非常重要。

                        输出要求：
                        - 使用中文。
                        - 只输出摘要，不输出 Markdown 标题。
                        - 尽量控制在 300 字以内；如果信息较多，可使用短句分号分隔。
                        - 如果没有有效上下文，输出“无有效历史上下文”。
                        """)
                .user("""
                        最近对话上下文：
                        %s


                        提取其关键信息，形成摘要。
                        """.formatted(latestMessageContextStr.toString()))
                .stream()
                .content()
                .collectList()
                .block();
        return chunks == null ? "" : String.join("", chunks).trim();
//        return limitText(normalizedTask, MAX_NORMALIZED_TASK_CHARS);
    }

    private String limitText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }
}
