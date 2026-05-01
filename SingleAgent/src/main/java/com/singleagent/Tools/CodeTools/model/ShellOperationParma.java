package com.singleagent.Tools.CodeTools.model;

import lombok.Data;
import org.springframework.ai.tool.annotation.ToolParam;

@Data
public class ShellOperationParma {

    @ToolParam(description = """
            要执行的Shell命令，例如 mvn test、java -jar app.jar、ls -la、grep -R \\"keyword\\" src、ps -ef | grep java。
            """)
    private String command;

    @ToolParam(description = """
            命令执行时所在的工作目录，可选。如果不传，则自动使用当前用户主目录下的默认专用工作目录，例如 ~/.xxx。
            """)
    private String operationDirectory;

    @ToolParam(description = """
            命令执行超时时间，单位为秒，可选。超过该时间后将终止执行，避免长时间阻塞。
            """)
    private Long timeOutSeconds = 30L;
}
