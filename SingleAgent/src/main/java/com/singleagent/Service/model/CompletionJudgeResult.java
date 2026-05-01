package com.singleagent.Service.model;

import lombok.Data;

@Data
public class CompletionJudgeResult {

    private boolean completed;
    private String next_instruction;
}
