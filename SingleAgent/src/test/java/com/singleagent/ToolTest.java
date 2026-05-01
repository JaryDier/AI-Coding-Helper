package com.singleagent;

import com.hik.testagent.Tools.CodeTools.CodeUpdateTool;
import com.hik.testagent.Tools.CodeTools.ShellOperationTool;
import com.hik.testagent.Tools.CodeTools.model.ReplaceFileRangeParam;
import com.hik.testagent.Tools.CodeTools.model.ReplaceFileRangeResponse;
import com.hik.testagent.Tools.CodeTools.model.ShellOperationParma;
import com.hik.testagent.Tools.CodeTools.model.ShellOperationResponse;
import com.hik.testagent.Tools.FileTools.FileOperationTool;
import com.hik.testagent.Tools.FileTools.model.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@Slf4j
@SpringBootTest
public class ToolTest {

    @Autowired
    private FileOperationTool fileSearchTool;

    @Autowired
    private CodeUpdateTool codeUpdateTool;

    @Autowired
    private ShellOperationTool shellOperationTool;

    @Test
    public void shellToolTest() {
        ShellOperationParma  shellOperationParma = new ShellOperationParma();
        shellOperationParma.setOperationDirectory("/Users/gaojialong/Documents/Ai/test");
        shellOperationParma.setCommand("cp FastSearch.java new.java");
        shellOperationParma.setTimeOutSeconds(30L);
        ShellOperationResponse shellOperationResponse = shellOperationTool.shellOperationTool(shellOperationParma);
        System.out.println("success = " + shellOperationResponse.isSuccess());
        System.out.println("exitCode = " + shellOperationResponse.getExitCode());
        System.out.println("stdout = " + shellOperationResponse.getStdout());
        System.out.println("stderr = " + shellOperationResponse.getStderr());
    }


    @Test
    public void codeUpdateToolTest() {
        ReplaceFileRangeParam replaceFileRangeParam = new ReplaceFileRangeParam();
        //throw new IllegalArgumentException("text/pattern 不能为 null");
        replaceFileRangeParam.setFilePath("/Users/gaojialong/Documents/Ai/test/FastSearch.java");
        replaceFileRangeParam.setStartLine(8);
        replaceFileRangeParam.setEndLine(8);
        replaceFileRangeParam.setNewContent("throw new IllegalArgumentException(\"text/pattern 可以为 null\");");
        replaceFileRangeParam.setCreateBackUp(true);
        ReplaceFileRangeResponse response = codeUpdateTool.codeUpdateTool(replaceFileRangeParam);
        System.out.println(response.getSummary());
    }


    @Test
    public void test() {
        //项目结构读取
        ListFilesParam param = new ListFilesParam();
        param.setPath("/Users/gaojialong/Documents/Ai/shellTest");
        param.setRecursive(true);
        param.setIncludeDirectories(false);
        param.setExtensions(List.of(".java", ".xml", ".yml"));

        ListFilesResponse response = fileSearchTool.listFiles(param);
//        response.getFiles().forEach(e -> System.out.println(e.getFileAbsolutePath()));

        //搜索文件
        SearchCodeParam searchCodeParam = new SearchCodeParam();
        searchCodeParam.setRootPath("/Users/gaojialong/Documents/Ai/shellTest");
        searchCodeParam.setKeyword("throw new IllegalArgumentException(\"text/pattern 不能为 null\");");
        searchCodeParam.setExtensions(List.of(".java"));
        searchCodeParam.setRecursive(true);
        searchCodeParam.setMaxResults(10);
        SearchCodeResponse searchCodeResponse = fileSearchTool.searchCode(searchCodeParam);
        for (SearchCodeResult r : searchCodeResponse.getResults()) {
            System.out.println(r.getFilePath() + " line " + r.getHitLineNumber());
            System.out.println(r.getSnippet());
        }
        //读取文件
        FileReadParam fileReadParam = new FileReadParam();
        fileReadParam.setFilePath("/Users/gaojialong/Documents/Ai/shellTest/FastSearch.java");
        fileReadParam.setStartLine(0);

        FileReadResult fileReadResult = fileSearchTool.readFile(fileReadParam);
        System.out.println(fileReadResult.getContent());
    }


}
