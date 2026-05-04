package com.singleagent.Tools.TodosCheckTool;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.singleagent.Tools.TodosCheckTool.model.TodoItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class TodosCheckTool {

    private static List<TodoItem> todos = new LinkedList<>();

    @Tool(name = "todosCheckTool", description = "添加计划工具")
    public List<TodoItem> addTodosCheck(@ToolParam TodoItem todoItem, ToolContext toolContext) {


        if (todoItem == null) {

            return todos;
        }
        if (StringUtils.isBlank(todoItem.getDescription())) {
            return todos;
        }
        todos.add(todoItem);
        return todos;
    }

}
