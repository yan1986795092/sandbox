package com.codesandbox;

/**
 * @Author yqq
 * @Date 2024/7/24 1:05
 * @Description 沙箱工厂
 * @Version 1.0
 */
public abstract class CodeSandboxFactory {
    public static CodeSandbox createSandbox(String language) {
        switch (language.toLowerCase()) {
            case "java":
                return new JavaDockerCodeSandbox();
            case "c":
                return new CLanguageDockerCodeSandbox();
            // 添加更多语言的支持
            default:
                throw new IllegalArgumentException("Unsupported language: " + language);
        }
    }
}