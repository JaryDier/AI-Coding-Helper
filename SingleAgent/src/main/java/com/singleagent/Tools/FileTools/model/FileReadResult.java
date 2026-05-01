package com.singleagent.Tools.FileTools.model;

import lombok.Data;

@Data
public class FileReadResult {
    private String filePath;
    private Integer startLine;
    private Integer endLine;
    private Integer totalLines;
    private String content;
}
