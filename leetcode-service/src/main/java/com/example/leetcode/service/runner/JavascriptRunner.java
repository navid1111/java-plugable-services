package com.example.leetcode.service.runner;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JavascriptRunner implements CodeRunner {
    private static final Pattern FUNCTION_DECLARATION = Pattern.compile(
            "(?:function\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(|"
            + "(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*"
            + "(?:async\\s*)?(?:function\\s*)?\\()");

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
        Matcher function = FUNCTION_DECLARATION.matcher(code);
        if (!function.find()) {
            return new ExecutionResult("COMPILE_ERROR", 0, 0, 0,
                    "Define a named JavaScript function, for example: function solve(input) { ... }");
        }
        String functionName = function.group(1) != null ? function.group(1) : function.group(2);
        String encodedTestCases = Base64.getEncoder()
                .encodeToString(testCasesJson.getBytes(StandardCharsets.UTF_8));
        String wrapper = String.join("\n",
                "try {",
                "    const testCases = JSON.parse(Buffer.from('" + encodedTestCases + "', 'base64').toString('utf8'));",
                "",
                "    // --- User Code ---")
                + "\n" + code + "\n"
                + String.join("\n",
                "    // -----------------",
                "",
                "    const userFunc = typeof " + functionName + " === 'function' ? " + functionName + " : null;",
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
