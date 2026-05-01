package com.singleagent.Tools.TodosCheckTool.model;

import lombok.Data;

@Data
public class TodoItem {

    private String id;
    private String todoName;
    private String description;
    private boolean finished;
}
