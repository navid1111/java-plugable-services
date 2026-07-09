package com.example.leetcode.service.runner;

import org.springframework.stereotype.Component;

@Component
public class JavascriptRunner implements CodeRunner {

    private final DockerProcessRunner dockerProcessRunner;

    public JavascriptRunner(DockerProcessRunner dockerProcessRunner) {
        this.dockerProcessRunner = dockerProcessRunner;
    }

    @Override
    public boolean supports(String language) {
        return "javascript".equalsIgnoreCase(language);
    }

    @Override
    public ExecutionResult runCode(String code, String testCasesJson) {
        String wrapper = String.join("\n",
                "try {",
                "    const testCases = JSON.parse(`" + testCasesJson + "`);",
                "",
                "    // --- User Code ---")
                + "\n" + code + "\n"
                + String.join("\n",
                "    // -----------------",
                "",
                "    const candidates = [];",
                "    if (typeof twoSum !== 'undefined') candidates.push(twoSum);",
                "    if (typeof reverseString !== 'undefined') candidates.push(reverseString);",
                "    const userFunc = candidates[0];",
                "    if (!userFunc) {",
                "        throw new Error('No supported solution function found');",
                "    }",
                "",
                "    const results = [];",
                "    for (const tc of testCases) {",
                "        const inputs = tc.input;",
                "        const expected = tc.output;",
                "",
                "        const start = performance.now();",
                "        try {",
                "            let result;",
                "            if (inputs && typeof inputs === 'object' && !Array.isArray(inputs)) {",
                "                result = userFunc(...Object.values(inputs));",
                "            } else {",
                "                result = userFunc(inputs);",
                "            }",
                "            const duration = Math.round(performance.now() - start);",
                "            const passed = JSON.stringify(result) === JSON.stringify(expected);",
                "            results.push({ passed, input: inputs, output: result, expected, runTimeMs: duration });",
                "        } catch (e) {",
                "            results.push({ passed: false, input: inputs, error: e.toString(), expected, runTimeMs: 0 });",
                "        }",
                "    }",
                "    console.log(JSON.stringify(results));",
                "} catch (e) {",
                "    console.error('COMPILE_ERROR: ' + e.message);",
                "    process.exit(1);",
                "}",
                "");

        ExecutionResult result = dockerProcessRunner.executeInDocker("node:20-alpine", "node -", wrapper);
        if ("RUNTIME_ERROR".equals(result.getStatus()) && result.getErrorMessage() != null && result.getErrorMessage().contains("COMPILE_ERROR")) {
            result.setStatus("COMPILE_ERROR");
        }
        return result;
    }
}
