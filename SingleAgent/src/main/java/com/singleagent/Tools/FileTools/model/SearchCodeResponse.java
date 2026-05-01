package com.singleagent.Tools.FileTools.model;

import lombok.Data;

import java.util.List;

@Data
public class SearchCodeResponse {
    private String rootPath;
    private String keyword;
    private int totalMatches;
    private List<SearchCodeResult> results;

}
