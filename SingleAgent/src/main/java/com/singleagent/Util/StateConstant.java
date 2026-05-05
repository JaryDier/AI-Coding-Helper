package com.singleagent.Util;

public class StateConstant {

    public static final String CONVERSATION_ID = "conversationId";

    public static final String PENDING_ACTION_KEY = "pending_action_key";

    //用户消息增强标志，如果存在递归多轮调用，只增强第一次的
    public static final String SKIP_USER_PROMPT_ENHANCE = "skipUserPromptEnhance";

    public static final String PARENT_THREAD_ID = "parentThreadId";

    public static final String TASK_ID = "taskId";

    public static final String PLAN_ID = "planId";
}
