package com.singleagent.Tools.FileTools.model;

import lombok.Data;
import org.springframework.ai.tool.annotation.ToolParam;

@Data
public class FileReadParam {

    @ToolParam(description = """
            要读取的文件路径。建议传绝对路径；如果系统约定了项目根目录，也可以传相对路径。
            """)
    private String filePath;

    @ToolParam(description = """
            起始行号，可选。行号从1开始；如果不传，则默认从文件第一行开始读取。
            """)
    private Integer startLine;

    @ToolParam(description = """
            结束行号，可选。必须大于等于起始行；如果不传，则默认读取到文件最后一行。
            """)
    private Integer endLine;
}
