package com.singleagent.Tools.CodeTools.model;

import lombok.Data;
import org.springframework.ai.tool.annotation.ToolParam;

/*
    按行替换文本模型调用传参
 */
@Data
public class ReplaceFileRangeParam {

    @ToolParam(description = """
            要修改的目标文件路径。建议传绝对路径；如果系统约定了项目根目录，也可以传相对路径。
            """)
    private String filePath;  //文件路径

    @ToolParam(description = """
            替换起始行号。行号从1开始，表示从该行开始替换。
            """)
    private Integer startLine; //替换文本的起始位置

    @ToolParam(description = """
            替换结束行号。必须大于等于起始行号，表示替换到该行结束。
            """)
    private Integer endLine; //替换文本的结束位置

    @ToolParam(description = """
            新的替换内容。将用这段文本替换指定行范围内的原始内容，可包含多行。
            """)
    private String newContent; //替换的新文本

    @ToolParam(description = """
            是否在修改前创建备份文件。为 true 时会保留原文件副本，便于回滚。
            """)
    private boolean createBackUp = false; //是否需要备份

}
