package com.singleagent.Tools.FileTools.model;

import lombok.Data;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

@Data
public class SearchCodeParam {

    @ToolParam(description = """
            项目根目录路径。搜索将从该路径开始进行，通常传项目根路径或某个源码目录。
            """)
    private String rootPath;

    @ToolParam(description = """
            要搜索的关键字或代码片段。例如类名、方法名、变量名、异常信息、SQL片段、配置项等。
            """)
    private String keyword;

    @ToolParam(description = """
            是否递归搜索子目录。通常代码搜索建议传 true。
            """)
    private boolean recursive = true;

    @ToolParam(description = """
            文件后缀过滤，可选。例如 .java、.xml、.yml、.properties。用于缩小搜索范围。
            """)
    private List<String> extensions;

    @ToolParam(description = """
            最大返回结果数，可选。用于限制搜索结果数量，避免一次返回过多命中。
            """)
    private Integer maxResults = 20;

    @ToolParam(description = """
            是否区分大小写，可选。默认通常为 false。
            """)
    private boolean caseSensitive = false;

    @ToolParam(description = """
            搜索命中后返回的上下文范围。表示在命中行前后各保留多少行内容作为代码片段。例如 80 表示返回命中行前 80 行和后 80 行。
            """)
    private int snippetRadius = 80;
}
