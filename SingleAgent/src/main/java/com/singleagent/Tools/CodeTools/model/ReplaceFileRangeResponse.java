package com.singleagent.Tools.CodeTools.model;

import lombok.Data;

/*
    按行替换文本工具响应结果
 */
@Data
public class ReplaceFileRangeResponse {
    private String filePath;
    private Integer replaceStartLine;
    private Integer replaceEndLine;
    private boolean success;
    private String backupFilePath;
    private String summary;
}
