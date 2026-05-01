package com.singleagent.Tools.FileTools.model;

import lombok.Data;
import org.springframework.ai.tool.annotation.ToolParam;

@Data
public class FileCreateParam {

    @ToolParam(description = "工作区根路径。如果 targetPath 是相对路径，将基于 rootPath 解析为最终目标路径。")
    private String rootPath;

    @ToolParam(description = "要创建的目标路径。可以是文件路径，也可以是文件夹路径；可以是绝对路径，也可以是相对于 rootPath 的相对路径。")
    private String targetPath;

    @ToolParam(description = "要创建的资源类型。可选值：file 表示创建文件，directory 表示创建文件夹。默认值为 file。")
    private String targetType = "file";

    @ToolParam(description = "创建文件时要写入的内容。targetType 为 directory 时忽略该字段。")
    private String content;

    @ToolParam(description = "当目标文件已存在时，是否允许覆盖。默认 false，表示不覆盖已有文件。该字段只对文件生效，创建文件夹时不会删除或覆盖已有目录。")
    private boolean overwrite = false;

    @ToolParam(description = "当父目录不存在时，是否自动创建父目录。默认 true。创建多级文件夹时也使用该字段。")
    private boolean createParentDirectories = true;
}
