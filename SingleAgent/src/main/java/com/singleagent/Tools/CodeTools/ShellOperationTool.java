package com.singleagent.Tools.CodeTools;

import com.singleagent.Tools.CodeTools.model.ShellOperationParma;
import com.singleagent.Tools.CodeTools.model.ShellOperationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;

@Component
@Slf4j
public class ShellOperationTool {

    private static final String DEFAULT_SHELL_WORKSPACE = ".hik-agent-shell";


    @Tool(name = "runShell", description = """
            通用Shell操作工具。适用于所有需要通过命令行完成的任务，
            例如编译代码、运行程序、执行测试、查看日志、搜索文本、检查文件、查看端口和进程、执行脚本以及进行系统环境排查。
            工具会返回执行状态、退出码、标准输出和错误输出，便于后续分析。
            若未指定工作目录，则自动在当前用户主目录下使用默认专用工作目录执行。
            """)
    public ShellOperationResponse shellOperationTool(@ToolParam(description = """
            命令执行参数。需要提供命令内容；可选提供执行目录和超时时间。若工作目录为空，则在默认目录执行。
            """) ShellOperationParma shellOperationParma) {
        if (shellOperationParma == null) {
            throw new IllegalArgumentException("shellOperationParma is null");
        }
//        if (StringUtils.isBlank(shellOperationParma.getOperationDirectory())) {
//            throw new IllegalArgumentException("shellOperationParma.getOperationDirectory() is blank");
//        }
        if (shellOperationParma.getCommand() == null) {
            throw new IllegalArgumentException("shellOperationParma.getCommand() is null");
        }

        //构建命令执行处理器
        //进程构建器
        ProcessBuilder processBuilder = buildProcessBuilder(shellOperationParma.getCommand());
        //shell操作路径
        Path workPath = null;
        workPath = resolveWorkingDirectory(shellOperationParma.getOperationDirectory());
        if(!Files.isDirectory(workPath)){
            throw new IllegalArgumentException(String.format("workPath:%s is not a directory", workPath));
        }
        //设置操作的根路径
        processBuilder.directory(workPath.toFile());
        String workingDir = workPath.toString();
        try {
            //启动进程
            Process process = processBuilder.start();
            //多线程分别获取正常输出和错误输出
            ExecutorService executor = Executors.newFixedThreadPool(2);
            Future<String> stdoutFuture = executor.submit(() -> readStream(process.getInputStream()));
            Future<String> stderrFuture = executor.submit(() -> readStream(process.getErrorStream()));

            boolean processFinished = process.waitFor(shellOperationParma.getTimeOutSeconds(), TimeUnit.SECONDS);

            String stdOut = stdoutFuture.get(3, TimeUnit.SECONDS);
            String stdErr = stderrFuture.get(3, TimeUnit.SECONDS);

            ShellOperationResponse shellOperationResponse = new ShellOperationResponse();
            shellOperationResponse.setCommand(shellOperationParma.getCommand());
            shellOperationResponse.setOperationDirectory(workingDir);
            if(!processFinished){
                process.destroyForcibly();
                shellOperationResponse.setTimeOut(true);
                shellOperationResponse.setSuccess(false);
                shellOperationResponse.setSummary("Command timed out after" +  shellOperationParma.getTimeOutSeconds() + " seconds");
                executor.shutdown();
            } else {
                int exitCode = process.exitValue();
                shellOperationResponse.setExitCode(exitCode);
                shellOperationResponse.setTimeOut(false);
                shellOperationResponse.setSuccess(exitCode == 0);
                shellOperationResponse.setSummary(
                        exitCode == 0
                                ? "Command executed successfully."
                                : "Command execution failed with exit code " + exitCode + "."
                );
                executor.shutdown();
            }
            shellOperationResponse.setStdout(stdOut);
            shellOperationResponse.setStderr(stdErr);
            return shellOperationResponse;

        } catch (InterruptedException | IOException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * 解析命令执行目录：
     * 1. 如果用户传了 workingDirectory，则使用该目录
     * 2. 如果没传，则默认使用用户主目录下的隐藏目录 ~/.hik-agent-shell
     * 3. 若目录不存在，则自动创建
     */
    private Path resolveWorkingDirectory(String workingDirectory) {
        Path path;

        if (workingDirectory != null && !workingDirectory.isBlank()) {
            path = Paths.get(workingDirectory).toAbsolutePath().normalize();
        } else {
            path = Paths.get(System.getProperty("user.home"), DEFAULT_SHELL_WORKSPACE)
                    .toAbsolutePath()
                    .normalize();
        }

        try {
            Files.createDirectories(path);
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException("Working directory is not a valid directory: " + path);
            }

            return path;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /*
        命令集成输入流、错误流读取解析
     */
    private String readStream(InputStream inputStream) {
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) != -1) {
                out.write(buffer, 0, length);
            }
            return out.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /*
        适配操作系统的 进程构建器
     */
    private ProcessBuilder buildProcessBuilder(String command) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            return new ProcessBuilder("cmd.exe", "/c", command);
        }
        return new ProcessBuilder("/bin/sh", "-c", command);
    }

}
