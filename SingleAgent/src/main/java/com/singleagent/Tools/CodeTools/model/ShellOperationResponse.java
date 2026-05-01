package com.singleagent.Tools.CodeTools.model;

import lombok.Data;

@Data
public class ShellOperationResponse {
    private boolean success;
    private boolean timeOut;
    private String command;
    private String operationDirectory;
    private String stdout;
    private String stderr;
    private Integer exitCode; //进程执行结果
    private String summary;  //进程执行总结

}
