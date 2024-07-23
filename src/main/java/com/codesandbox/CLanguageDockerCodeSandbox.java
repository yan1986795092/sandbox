package com.codesandbox;

import cn.hutool.core.util.ArrayUtil;
import com.codesandbox.model.ExecuteCodeRequest;
import com.codesandbox.model.ExecuteCodeResponse;
import com.codesandbox.model.ExecuteMessage;
import com.github.dockerjava.api.DockerClient;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * C 语言代码沙箱实现类
 */
@Component
public class CLanguageDockerCodeSandbox implements CodeSandbox {

    private static final long TIME_OUT = 5000L;
    private static final String IMAGE = "gcc:latest";
    private boolean firstInit = true;

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {
        File userCodeFile = createTempCodeFile(request.getCode());
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

    private List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList) {
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();

        if (firstInit) {
            pullImage(dockerClient);
            firstInit = false;
        }

        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        String containerId = createAndStartContainer(dockerClient, userCodeParentPath);

        for (String inputArgs : inputList) {
            ExecuteMessage executeMessage = executeCommand(dockerClient, containerId, userCodeFile, inputArgs);
            executeMessageList.add(executeMessage);
        }

        return executeMessageList;
    }

    private void pullImage(DockerClient dockerClient) {
        PullImageCmd pullImageCmd = dockerClient.pullImageCmd(IMAGE);
        PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
            @Override
            public void onNext(PullResponseItem item) {
                System.out.println("Downloading image: " + item.getStatus());
                super.onNext(item);
            }
        };
        try {
            pullImageCmd.exec(pullImageResultCallback).awaitCompletion();
        } catch (InterruptedException e) {
            throw new RuntimeException("Image pull interrupted", e);
        }
    }

    private String createAndStartContainer(DockerClient dockerClient, String userCodeParentPath) {
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(IMAGE);
        HostConfig hostConfig = new HostConfig();
        hostConfig.withMemory(100 * 1000 * 1000L);
        hostConfig.withMemorySwap(0L);
        hostConfig.withCpuCount(1L);
        hostConfig.setBinds(new Bind(userCodeParentPath, new Volume("/app")));

        CreateContainerResponse response = containerCmd
                .withHostConfig(hostConfig)
                .withNetworkDisabled(true)
                .withReadonlyRootfs(true)
                .withAttachStdin(true)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withTty(true)
                .exec();

        String containerId = response.getId();
        dockerClient.startContainerCmd(containerId).exec();
        return containerId;
    }

    private ExecuteMessage executeCommand(DockerClient dockerClient, String containerId, File userCodeFile, String inputArgs) {
        StopWatch stopWatch = new StopWatch();
        String[] inputArgsArray = inputArgs.split(" ");
        String compileCmd = "gcc /app/" + userCodeFile.getName() + " -o /app/a.out";
        String[] runCmdArray = ArrayUtil.append(new String[]{"/app/a.out"}, inputArgsArray);

        String compileExecId = createAndExecuteCommand(dockerClient, containerId, compileCmd.split(" "));
        executeCommandAndWait(dockerClient, containerId, compileExecId);

        ExecuteMessage executeMessage = new ExecuteMessage();
        String runExecId = createAndExecuteCommand(dockerClient, containerId, runCmdArray);
        executeCommandAndWait(dockerClient, containerId, runExecId, executeMessage);

        return executeMessage;
    }

    private String createAndExecuteCommand(DockerClient dockerClient, String containerId, String[] cmdArray) {
        ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                .withCmd(cmdArray)
                .withAttachStderr(true)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .exec();
        return execCreateCmdResponse.getId();
    }

    private void executeCommandAndWait(DockerClient dockerClient, String containerId, String execId) {
        try {
            dockerClient.execStartCmd(execId)
                    .exec(new ExecStartResultCallback())
                    .awaitCompletion(TIME_OUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException("Command execution interrupted", e);
        }
    }

    private void executeCommandAndWait(DockerClient dockerClient, String containerId, String execId, ExecuteMessage executeMessage) {
        StopWatch stopWatch = new StopWatch();
        final String[] message = {null};
        final String[] errorMessage = {null};

        ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() {
            @Override
            public void onComplete() {
                super.onComplete();
            }

            @Override
            public void onNext(Frame frame) {
                if (frame.getStreamType() == StreamType.STDERR) {
                    errorMessage[0] = new String(frame.getPayload());
                } else {
                    message[0] = new String(frame.getPayload());
                }
                super.onNext(frame);
            }
        };

        try {
            stopWatch.start();
            dockerClient.execStartCmd(execId)
                    .exec(execStartResultCallback)
                    .awaitCompletion(TIME_OUT, TimeUnit.MILLISECONDS);
            stopWatch.stop();
            executeMessage.setMessage(message[0]);
            executeMessage.setErrorMessage(errorMessage[0]);
            executeMessage.setTime(stopWatch.getLastTaskTimeMillis());
        } catch (InterruptedException e) {
            throw new RuntimeException("Command execution interrupted", e);
        }
    }
}
