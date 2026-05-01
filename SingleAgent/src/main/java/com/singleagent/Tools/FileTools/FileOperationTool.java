package com.singleagent.Tools.FileTools;

import com.singleagent.Tools.FileTools.model.*;
import com.singleagent.Tools.FileTools.model.FileReadResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Component
@Slf4j
public class FileOperationTool {

    @Tool(name = "createFile", description = """
            创建文件或文件夹工具。用于在指定工作区内创建新的文件或目录。
            创建文件时可以写入内容，并可选择是否覆盖已有文件；创建文件夹时可以创建单级或多级目录。
            当任务需要新增文件或初始化目录结构时使用该工具；如果只是修改已有文件的局部内容，应优先使用文件局部替换工具。
            """)
    public FileCreateResponse createFile(
            @ToolParam(description = """
                    创建文件或文件夹所需参数。必须提供 targetPath，并通过 targetType 指定创建 file 或 directory。
                    targetPath 可以是绝对路径，也可以是相对于 rootPath 的相对路径。
                    """) FileCreateParam fileCreateParam
    ) {
        FileCreateResponse response = new FileCreateResponse();
        if (fileCreateParam == null || StringUtils.isBlank(fileCreateParam.getTargetPath())) {
            response.setSuccess(false);
            response.setErrorMessage("targetPath must not be blank.");
            response.setSummary("Create resource failed because targetPath is blank.");
            return response;
        }

        String targetType = StringUtils.defaultIfBlank(fileCreateParam.getTargetType(), "file").trim().toLowerCase();
        if (!"file".equals(targetType) && !"directory".equals(targetType)) {
            response.setSuccess(false);
            response.setTargetType(targetType);
            response.setErrorMessage("targetType must be file or directory.");
            response.setSummary("Create resource failed because targetType is invalid.");
            return response;
        }

        try {
            Path targetPath = resolveTargetPath(fileCreateParam);
            response.setTargetPath(targetPath.toString());
            response.setTargetType(targetType);

            if ("directory".equals(targetType)) {
                createDirectory(fileCreateParam, response, targetPath);
            } else {
                createRegularFile(fileCreateParam, response, targetPath);
            }
            return response;
        } catch (Exception e) {
            response.setSuccess(false);
            response.setErrorMessage(e.getMessage());
            response.setSummary("Create resource failed: " + e.getMessage());
            return response;
        }
    }

    @Tool(name = "readFile", description = """
            读取文件内容工具。用于读取指定文件的全部内容，或按起止行读取局部内容。
            适合在已经定位到目标文件后，进一步查看源码、配置、脚本或日志的具体内容。
            """)
    public FileReadResult readFile(
            @ToolParam(description = """
                    读取文件所需参数。必须提供文件绝对路径或相对项目根目录的文件路径。
                    可选提供起始行和结束行，用于只读取局部内容。行号从 1 开始。
                    """) FileReadParam fileReadParam
    ) {
        if (fileReadParam == null || StringUtils.isBlank(fileReadParam.getFilePath())) {
            throw new IllegalArgumentException("filePath must not be blank.");
        }

        Path absolutePath = Paths.get(fileReadParam.getFilePath()).toAbsolutePath().normalize();
        try {
            List<String> lines = Files.readAllLines(absolutePath, StandardCharsets.UTF_8);
            int totalLines = lines.size();
            int startLine = fileReadParam.getStartLine() == null ? 1 : Math.max(1, fileReadParam.getStartLine());
            int endLine = fileReadParam.getEndLine() == null ? totalLines : Math.min(totalLines, fileReadParam.getEndLine());
            if (endLine < startLine) {
                throw new IllegalArgumentException("endLine must be greater than or equal to startLine.");
            }

            StringBuilder content = new StringBuilder();
            for (int i = startLine; i <= endLine; i++) {
                content.append(i).append(":").append(lines.get(i - 1)).append(System.lineSeparator());
            }

            FileReadResult result = new FileReadResult();
            result.setFilePath(absolutePath.toString());
            result.setTotalLines(totalLines);
            result.setStartLine(startLine);
            result.setEndLine(endLine);
            result.setContent(content.toString());
            return result;
        } catch (IOException e) {
            throw new RuntimeException("Read file failed.", e);
        }
    }

    @Tool(name = "searchCode", description = """
            代码片段搜索工具。用于在指定项目根目录下递归搜索关键字、方法名、类名、异常信息或代码片段。
            适合在不知道具体文件位置时，先定位相关代码出现的文件、行号和上下文片段。
            """)
    public SearchCodeResponse searchCode(
            @ToolParam(description = """
                    代码搜索所需参数。必须提供项目根路径和搜索关键字；可选提供文件扩展名、最大结果数、
                    是否区分大小写和上下文半径，用于缩小搜索范围。
                    """) SearchCodeParam searchCodeParam
    ) {
        if (searchCodeParam == null || StringUtils.isBlank(searchCodeParam.getRootPath())) {
            throw new IllegalArgumentException("rootPath must not be blank.");
        }
        if (StringUtils.isBlank(searchCodeParam.getKeyword())) {
            throw new IllegalArgumentException("keyword must not be blank.");
        }

        Path rootPath = Paths.get(searchCodeParam.getRootPath()).toAbsolutePath().normalize();
        List<SearchCodeResult> results = new ArrayList<>();
        int maxResults = searchCodeParam.getMaxResults() == null ? 20 : Math.max(1, searchCodeParam.getMaxResults());
        String keyword = searchCodeParam.isCaseSensitive()
                ? searchCodeParam.getKeyword()
                : searchCodeParam.getKeyword().toLowerCase();

        try (Stream<Path> stream = searchCodeParam.isRecursive() ? Files.walk(rootPath) : Files.list(rootPath)) {
            List<Path> paths = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !shouldIgnore(path))
                    .filter(path -> matchesExtensions(path, searchCodeParam.getExtensions()))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();

            for (Path path : paths) {
                if (results.size() >= maxResults) {
                    break;
                }
                collectSearchResults(path, searchCodeParam, keyword, maxResults, results);
            }

            SearchCodeResponse response = new SearchCodeResponse();
            response.setRootPath(searchCodeParam.getRootPath());
            response.setKeyword(searchCodeParam.getKeyword());
            response.setResults(results);
            response.setTotalMatches(results.size());
            return response;
        } catch (Exception e) {
            throw new RuntimeException("Search code failed.", e);
        }
    }

    @Tool(name = "listFiles", description = """
            文件列表查询工具。用于查看目标路径下的文件和目录结构。
            可用于了解项目布局、查找配置文件、定位模块目录，或在搜索代码前确认可用范围。
            """)
    public ListFilesResponse listFiles(
            @ToolParam(description = """
                    文件列表查询所需参数。必须提供目标路径；可选指定是否递归遍历、是否包含目录、
                    是否按文件后缀过滤。
                    """) ListFilesParam listFilesParam
    ) {
        if (listFilesParam == null || StringUtils.isBlank(listFilesParam.getPath())) {
            throw new IllegalArgumentException("path must not be blank.");
        }

        Path rootPath = Path.of(listFilesParam.getPath()).toAbsolutePath().normalize();
        List<FileEntity> fileEntities = new ArrayList<>();
        try (Stream<Path> pathStream = listFilesParam.isRecursive() ? Files.walk(rootPath) : Files.list(rootPath)) {
            pathStream
                    .filter(path -> !path.equals(rootPath))
                    .filter(path -> !shouldIgnore(path))
                    .filter(path -> listFilesParam.isIncludeDirectories() || Files.isRegularFile(path))
                    .filter(path -> Files.isDirectory(path) || matchesExtensions(path, listFilesParam.getExtensions()))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> {
                        FileEntity fileEntity = new FileEntity();
                        fileEntity.setFileName(path.getFileName().toString());
                        fileEntity.setFileAbsolutePath(path.toAbsolutePath().toString());
                        fileEntity.setDirectory(Files.isDirectory(path));
                        try {
                            fileEntity.setSize(Files.isDirectory(path) ? 0L : Files.size(path));
                        } catch (IOException e) {
                            fileEntity.setSize(0L);
                        }
                        fileEntities.add(fileEntity);
                    });

            ListFilesResponse response = new ListFilesResponse();
            response.setFiles(fileEntities);
            response.setTotal(fileEntities.size());
            response.setRooPath(rootPath.toString());
            return response;
        } catch (Exception e) {
            log.error("List files failed.", e);
            throw new RuntimeException("List files failed.", e);
        }
    }

    private Path resolveTargetPath(FileCreateParam param) {
        Path rawTargetPath = Paths.get(param.getTargetPath());
        Path targetPath;
        if (rawTargetPath.isAbsolute()) {
            targetPath = rawTargetPath.toAbsolutePath().normalize();
        } else {
            if (StringUtils.isBlank(param.getRootPath())) {
                throw new IllegalArgumentException("rootPath must not be blank when targetPath is relative.");
            }
            targetPath = Paths.get(param.getRootPath()).resolve(rawTargetPath).toAbsolutePath().normalize();
        }

        if (StringUtils.isNotBlank(param.getRootPath())) {
            Path rootPath = Paths.get(param.getRootPath()).toAbsolutePath().normalize();
            if (!targetPath.startsWith(rootPath)) {
                throw new IllegalArgumentException("targetPath must be under rootPath.");
            }
        }
        return targetPath;
    }

    private void createDirectory(FileCreateParam param, FileCreateResponse response, Path targetPath) throws IOException {
        if (Files.exists(targetPath)) {
            response.setAlreadyExists(true);
            response.setSuccess(Files.isDirectory(targetPath));
            response.setCreated(false);
            response.setSummary(Files.isDirectory(targetPath)
                    ? "Directory already exists."
                    : "Target path already exists and is not a directory.");
            return;
        }

        Path parent = targetPath.getParent();
        response.setParentDirectoriesCreated(parent != null && !Files.exists(parent));
        if (param.isCreateParentDirectories()) {
            Files.createDirectories(targetPath);
        } else {
            Files.createDirectory(targetPath);
        }

        response.setSuccess(true);
        response.setCreated(true);
        response.setSummary("Directory created successfully.");
    }

    private void createRegularFile(FileCreateParam param, FileCreateResponse response, Path targetPath) throws IOException {
        if (Files.exists(targetPath)) {
            response.setAlreadyExists(true);
            if (Files.isDirectory(targetPath)) {
                response.setSuccess(false);
                response.setSummary("Target path already exists and is a directory.");
                return;
            }
            if (!param.isOverwrite()) {
                response.setSuccess(false);
                response.setSummary("File already exists and overwrite is false.");
                return;
            }
        }

        Path parent = targetPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            if (!param.isCreateParentDirectories()) {
                response.setSuccess(false);
                response.setSummary("Parent directory does not exist and createParentDirectories is false.");
                return;
            }
            Files.createDirectories(parent);
            response.setParentDirectoriesCreated(true);
        }

        String content = param.getContent() == null ? "" : param.getContent();
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        if (Files.exists(targetPath)) {
            Files.writeString(targetPath, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            response.setOverwritten(true);
        } else {
            Files.writeString(targetPath, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }

        response.setSuccess(true);
        response.setCreated(!response.isOverwritten());
        response.setBytesWritten(contentBytes.length);
        response.setSummary(response.isOverwritten()
                ? "File overwritten successfully."
                : "File created successfully.");
    }

    private void collectSearchResults(
            Path path,
            SearchCodeParam param,
            String keyword,
            int maxResults,
            List<SearchCodeResult> results
    ) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<String> allLines = reader.lines().toList();
            for (int i = 0; i < allLines.size() && results.size() < maxResults; i++) {
                String line = allLines.get(i);
                String comparedLine = param.isCaseSensitive() ? line : line.toLowerCase();
                if (comparedLine.contains(keyword)) {
                    SearchCodeResult result = new SearchCodeResult();
                    result.setFilePath(path.toAbsolutePath().toString());
                    result.setHitLineNumber(i + 1);
                    result.setTotalLines(allLines.size());
                    result.setHitLineContent(line);
                    result.setSnippet(buildSnippet(allLines, i, param.getSnippetRadius()));
                    results.add(result);
                }
            }
        }
    }

    private String buildSnippet(List<String> allLines, int hitIndex, int snippetRadius) {
        int radius = Math.max(0, snippetRadius);
        int start = Math.max(0, hitIndex - radius);
        int end = Math.min(allLines.size() - 1, hitIndex + radius);
        StringBuilder snippet = new StringBuilder();
        for (int i = start; i <= end; i++) {
            snippet.append(i + 1)
                    .append(": ")
                    .append(allLines.get(i))
                    .append(System.lineSeparator());
        }
        return snippet.toString();
    }

    private boolean matchesExtensions(Path path, List<String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return true;
        }
        String fileName = path.getFileName().toString();
        return extensions.stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .anyMatch(fileName::endsWith);
    }

    private boolean shouldIgnore(Path path) {
        Set<String> ignoredNames = Set.of(".git", ".idea", ".DS_Store", "target");
        return path.getFileName() != null && ignoredNames.contains(path.getFileName().toString());
    }
}
