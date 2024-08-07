package com.codesandbox;

import cn.hutool.core.util.ArrayUtil;
import com.codesandbox.model.ExecuteCodeRequest;
import com.codesandbox.model.ExecuteCodeResponse;
import com.codesandbox.model.ExecuteMessage;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 基于 Docker 的 Java 代码沙箱实现类。
 * 该类使用 Docker 提供隔离环境来安全地编译和执行用户提交的 Java 代码。
 */
@Component
public class JavaDockerCodeSandbox implements CodeSandbox {

    private static final long TIME_OUT = 5000L; // 命令执行的超时时间，单位为毫秒
    private static final String IMAGE = "openjdk:8-alpine"; // Docker 镜像名称，使用 OpenJDK 8 的 Alpine 版本
    private boolean firstInit = true; // 标志是否为第一次初始化，确保 Docker 镜像仅拉取一次


    /**
     * 处理用户提交的代码请求。
     * @param request 执行代码请求对象，包含代码和输入参数
     * @return 执行代码的响应对象，包含执行结果和错误信息
     */
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {
        // 创建一个临时文件，将用户的 Java 代码写入该文件
        File userCodeFile = createTempCodeFile(request.getCode());

        // 在 Docker 容器中运行用户代码，并获取执行消息列表
        List<ExecuteMessage> executeMessageList = runFile(userCodeFile, request.getInputList());

        // 将 ExecuteMessage 转换为 String
        List<String> outputList = executeMessageList.stream()
                .map(ExecuteMessage::getMessage) // 假设 ExecuteMessage 类有 getMessage 方法
                .collect(Collectors.toList());

        // 封装执行结果并返回
        ExecuteCodeResponse response = new ExecuteCodeResponse();
        response.setOutputList(outputList);
        return response;
    }


    /**
     * 创建临时 Java 代码文件。
     * @param code 用户提交的 Java 代码
     * @return 临时文件对象，包含用户代码
     */
    private File createTempCodeFile(String code) {
        try {
            Path tempFilePath = Files.createTempFile("UserCode", ".java");
            Files.write(tempFilePath, code.getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE);
            File tempFile = tempFilePath.toFile();

            // 在文件使用完成后，确保文件被删除
            tempFile.deleteOnExit(); // 设置在 JVM 退出时删除文件

            return tempFile;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temporary file", e);
        }
    }


    /**
     * 在 Docker 容器中编译并运行 Java 代码。
     * @param userCodeFile 用户代码的临时文件
     * @param inputList 输入参数列表
     * @return 执行结果消息的列表
     */
    private List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList) {
        // 获取用户代码文件的父目录的绝对路径
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();

        // 创建 Docker 客户端实例
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();

        // 如果是第一次运行，拉取 Docker 镜像
        if (firstInit) {
            pullImage(dockerClient);
            firstInit = false; // 更新初始化标志
        }

        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        // 创建并启动 Docker 容器
        String containerId = createAndStartContainer(dockerClient, userCodeParentPath);

        // 对于每个输入参数，执行相应的代码，并收集执行结果
        for (String inputArgs : inputList) {
            ExecuteMessage executeMessage = executeCommand(dockerClient, containerId, userCodeFile, inputArgs);
            executeMessageList.add(executeMessage);
        }

        return executeMessageList;
    }


    /**
     * 拉取 Docker 镜像，以确保容器可以使用指定的环境运行代码。
     * @param dockerClient Docker 客户端实例
     */
    private void pullImage(DockerClient dockerClient) {
        PullImageCmd pullImageCmd = dockerClient.pullImageCmd(IMAGE);
        PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
            @Override
            public void onNext(PullResponseItem item) {
                // 打印镜像下载的进度信息
                System.out.println("Downloading image: " + item.getStatus());
                super.onNext(item);
            }
        };
        try {
            // 执行镜像拉取操作并等待完成
            pullImageCmd.exec(pullImageResultCallback).awaitCompletion();
        } catch (InterruptedException e) {
            // 捕获异常并抛出运行时异常
            throw new RuntimeException("Image pull interrupted", e);
        }
    }


    /**
     * 创建并启动 Docker 容器，以提供执行代码的隔离环境。
     * @param dockerClient Docker 客户端实例
     * @param userCodeParentPath 用户代码文件的父目录路径
     * @return 启动的 Docker 容器 ID
     */
    private String createAndStartContainer(DockerClient dockerClient, String userCodeParentPath) {
        // 创建一个 Docker 容器命令
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(IMAGE);
        HostConfig hostConfig = new HostConfig();
        // 配置容器的内存、交换空间和 CPU 资源限制
        hostConfig.withMemory(100 * 1000 * 1000L); // 100 MB 内存
        hostConfig.withMemorySwap(0L); // 禁用交换空间
        hostConfig.withCpuCount(1L); // 设置 CPU 核心数
        // 将主机目录挂载到容器中的 /app 目录
        hostConfig.setBinds(new Bind(userCodeParentPath, new Volume("/app")));

        // 配置容器的创建参数
        CreateContainerResponse response = containerCmd
                .withHostConfig(hostConfig) // 应用主机配置
                .withNetworkDisabled(true) // 禁用容器网络
                .withReadonlyRootfs(true) // 将根文件系统设置为只读
                .withAttachStdin(true) // 附加标准输入
                .withAttachStderr(true) // 附加标准错误
                .withAttachStdout(true) // 附加标准输出
                .withTty(true) // 启用伪终端
                .exec();

        // 获取容器 ID 并启动容器
        String containerId = response.getId();
        dockerClient.startContainerCmd(containerId).exec();
        return containerId;
    }


    /**
     * 编译并运行用户代码，返回执行结果。
     * @param dockerClient Docker 客户端实例
     * @param containerId Docker 容器 ID
     * @param userCodeFile 用户代码的临时文件
     * @param inputArgs 运行代码时的输入参数
     * @return 执行消息对象，包含输出、错误和执行时间
     */
    private ExecuteMessage executeCommand(DockerClient dockerClient, String containerId, File userCodeFile, String inputArgs) {
        StopWatch stopWatch = new StopWatch(); // 计时器，用于记录代码执行时间

        // 构建编译和运行命令
        String compileCmd = "javac /app/" + userCodeFile.getName(); // 编译命令
        String[] runCmdArray = ArrayUtil.append(new String[]{"java", "-cp", "/app", "Main"}, inputArgs.split(" ")); // 运行命令

        // 执行编译命令
        String compileExecId = createAndExecuteCommand(dockerClient, containerId, compileCmd.split(" "));
        executeCommandAndWait(dockerClient, containerId, compileExecId);

        // 创建执行消息对象并执行运行命令
        ExecuteMessage executeMessage = new ExecuteMessage();
        String runExecId = createAndExecuteCommand(dockerClient, containerId, runCmdArray);
        executeCommandAndWait(dockerClient, containerId, runExecId, executeMessage);

        return executeMessage;
    }


    /**
     * 创建并执行 Docker 命令。
     * @param dockerClient Docker 客户端实例
     * @param containerId Docker 容器 ID
     * @param cmdArray 命令及其参数数组
     * @return 执行 ID，用于后续的命令启动
     */
    private String createAndExecuteCommand(DockerClient dockerClient, String containerId, String[] cmdArray) {
        // 创建一个 Docker 命令
        ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                .withCmd(cmdArray) // 设置命令及参数
                .withAttachStderr(true) // 附加标准错误
                .withAttachStdin(true) // 附加标准输入
                .withAttachStdout(true) // 附加标准输出
                .exec();
        return execCreateCmdResponse.getId(); // 返回执行 ID
    }


    /**
     * 执行 Docker 命令并等待其完成。
     * @param dockerClient Docker 客户端实例
     * @param containerId Docker 容器 ID
     * @param execId 执行 ID
     */
    private void executeCommandAndWait(DockerClient dockerClient, String containerId, String execId) {
        try {
            // 启动命令执行并等待完成
            dockerClient.execStartCmd(execId)
                    .exec(new ExecStartResultCallback())
                    .awaitCompletion(TIME_OUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 捕获异常并抛出运行时异常
            throw new RuntimeException("Command execution interrupted", e);
        }
    }


    /**
     * 执行 Docker 命令并等待其完成，同时收集输出和错误信息。
     * @param dockerClient Docker 客户端实例
     * @param containerId Docker 容器 ID
     * @param execId 执行 ID
     * @param executeMessage 执行消息对象，用于存储输出和错误信息
     */
    private void executeCommandAndWait(DockerClient dockerClient, String containerId, String execId, ExecuteMessage executeMessage) {
        StopWatch stopWatch = new StopWatch(); // 计时器，用于记录代码执行时间
        final String[] message = {null}; // 标准输出消息
        final String[] errorMessage = {null}; // 错误输出消息

        // 处理命令执行结果回调
        ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {
            @Override
            public void onComplete() {
                super.onComplete();
            }

            @Override
            public void onNext(Frame frame) {
                // 根据输出流类型处理标准输出和错误输出
                if (frame.getStreamType() == StreamType.STDERR) {
                    errorMessage[0] = new String(frame.getPayload());
                } else {
                    message[0] = new String(frame.getPayload());
                }
                super.onNext(frame);
            }
        };

        try {
            stopWatch.start(); // 开始计时
            // 启动命令执行并等待完成
            dockerClient.execStartCmd(execId)
                    .exec(execStartResultCallback)
                    .awaitCompletion(TIME_OUT, TimeUnit.MILLISECONDS);
            stopWatch.stop(); // 停止计时
            // 设置执行消息的输出和错误信息
            executeMessage.setMessage(message[0]);
            executeMessage.setErrorMessage(errorMessage[0]);
            executeMessage.setTime(stopWatch.getLastTaskTimeMillis()); // 设置执行时间
        } catch (InterruptedException e) {
            // 捕获异常并抛出运行时异常
            throw new RuntimeException("Command execution interrupted", e);
        }
    }
}
