package com.singleagent.Tools.CodeTools;

import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.singleagent.Tools.CodeTools.model.ReplaceFileRangeParam;
import com.singleagent.Tools.CodeTools.model.ReplaceFileRangeResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
@Slf4j
public class CodeUpdateTool {

    private final ParameterNamesModule parameterNamesModule;

    public CodeUpdateTool(ParameterNamesModule parameterNamesModule) {
        this.parameterNamesModule = parameterNamesModule;
    }

    @Tool(name = "replaceByLineRange", description = """
            文件局部修改工具。适用于代码修复、方法替换、配置调整等场景。在已经通过搜索或读文件定位到具体文件和行范围后，使用该工具将指定行范围替换为新的内容。
            """)
    public ReplaceFileRangeResponse codeUpdateTool(@ToolParam(description = """
                文件替换参数。需要提供目标文件路径、要替换的起始行和结束行，以及新的文本内容；可选开启备份以保留原始文件副本。
            """) ReplaceFileRangeParam replaceFileRangeParam) {
        //错误参数过滤
        if(replaceFileRangeParam == null){
            throw new NullPointerException("replaceFileRangeParam is null");
        }

        if(StringUtils.isBlank(replaceFileRangeParam.getFilePath())){
            throw new NullPointerException("filePath is null");
        }

        if(replaceFileRangeParam.getNewContent() == null) {
            throw new NullPointerException("newContent is null");
        }
        if (replaceFileRangeParam.getStartLine() == null || replaceFileRangeParam.getStartLine() < 1) {
            throw new IllegalArgumentException("startLine must be greater than or equal to 1.");
        }
        if (replaceFileRangeParam.getEndLine() == null || replaceFileRangeParam.getEndLine() < replaceFileRangeParam.getStartLine()) {
            throw new IllegalArgumentException("endLine must be greater than or equal to startLine.");
        }

        Path filePath = Paths.get(replaceFileRangeParam.getFilePath()).toAbsolutePath().normalize();
        if(Files.isDirectory(filePath)){
            throw new IllegalStateException("filePath is not file.");
        }

        //备份
        String backupPath = null;
        if (replaceFileRangeParam.isCreateBackUp()) {
            Path backup = filePath.resolveSibling(filePath.getFileName() + ".bak");
            try {
                Files.copy(filePath, backup, StandardCopyOption.REPLACE_EXISTING);
                backupPath = backup.toString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        //读取所有行内容
        try {
            List<String> lineStrings = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            List<String> resultLineStrings = new ArrayList<>();
            int startLine = replaceFileRangeParam.getStartLine();
            int endLine = replaceFileRangeParam.getEndLine();
            //待替换内容前的行文本
            for(int i = 0; i < startLine - 1; i++){
                resultLineStrings.add(lineStrings.get(i));
            }
            //待替换的文本
            String normalizedNewContent = replaceFileRangeParam.getNewContent().replace("\r\n","\n");
            if(!normalizedNewContent.isBlank()) {
                String[] splitContent = normalizedNewContent.split("\n", -1);
                resultLineStrings.addAll(Arrays.asList(splitContent));
            }
            //后部分内容
            for(int i = endLine + 1; i < lineStrings.size(); i++){
                resultLineStrings.add(lineStrings.get(i));
            }

            //写入文件
            Files.write(filePath,resultLineStrings, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            ReplaceFileRangeResponse replaceFileRangeResponse = new ReplaceFileRangeResponse();
            replaceFileRangeResponse.setFilePath(filePath.toString());
            replaceFileRangeResponse.setReplaceStartLine(startLine);
            replaceFileRangeResponse.setReplaceEndLine(endLine);
            replaceFileRangeResponse.setSuccess(true);
            replaceFileRangeResponse.setBackupFilePath(backupPath);
            replaceFileRangeResponse.setSummary("Successfully replaced lines " + replaceFileRangeParam.getStartLine() + " to " + replaceFileRangeParam.getEndLine() + ".");


            return replaceFileRangeResponse;

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
