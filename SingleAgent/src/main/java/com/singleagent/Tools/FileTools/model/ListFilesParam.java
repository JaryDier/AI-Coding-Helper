package com.singleagent.Tools.FileTools.model;

import lombok.Data;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

@Data
public class ListFilesParam {
    @ToolParam(description = """
            要查询的目标路径。可以是项目根目录，也可以是任意子目录。
            """)
    private String path; //文件目录路径

    @ToolParam(description = """
            是否递归遍历子目录。若只想查看当前目录内容可传 false；若需要查看完整目录树可传 true。
            """)
    private boolean recursive = true; //是否递归查找

    @ToolParam(description = """
            是否在结果中包含目录。若只关心文件可传 false。
            """)
    private boolean includeDirectories;  //查找结果中是否包含目录

    @ToolParam(description = """
            文件后缀过滤，可选。例如 .java、.xml、.yml。用于只返回指定类型文件。
            """)
    private List<String> extensions; //查找哪些扩展类型的文件
}
