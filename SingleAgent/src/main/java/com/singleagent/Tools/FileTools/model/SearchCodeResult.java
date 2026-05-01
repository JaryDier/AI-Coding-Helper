package com.singleagent.Tools.FileTools.model;

import lombok.Data;

@Data
public class SearchCodeResult {

    private String filePath;
    private int hitLineNumber;  //关键所在行
    private int totalLines = 11; //匹配完成后返回的总行数
    private String hitLineContent;
    private String snippet; //匹配行+前后5行的总片段
}
