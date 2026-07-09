package com.example.leetcode.service.runner;

import org.springframework.stereotype.Component;

@Component
public class JavaRunner implements CodeRunner {

    private final DockerProcessRunner dockerProcessRunner;

    public JavaRunner(DockerProcessRunner dockerProcessRunner) {
        this.dockerProcessRunner = dockerProcessRunner;
    }

    @Override
    public boolean supports(String language) {
        return "java".equalsIgnoreCase(language);
    }

    @Override
    public ExecutionResult runCode(String code, String testCasesJson) {
        // Full dynamic Java evaluation is complex without a JSON parser in the container 
        // or a dynamic AST parser in the host. For the scope of this prototype, we will return a stubbed response.
        return new ExecutionResult("COMPILE_ERROR", 0, 0, 0, "Java execution is not fully supported in this prototype version yet.");
    }
}
