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
        String wrapper = "const fs = require('fs');\\n" +
                "\\n" +
                "try {\\n" +
                "    const testCases = JSON.parse(`" + testCasesJson + "`);\\n" +
                "\\n" +
                "    // --- User Code ---\\n" +
                code + "\\n" +
                "    // -----------------\\n" +
                "\\n" +
                "    let funcName = null;\\n" +
                "    for (const key in global) {\\n" +
                "       if (typeof global[key] === 'function' && key !== 'global' && key !== 'clearInterval' && key !== 'clearTimeout' && key !== 'setInterval' && key !== 'setTimeout' && key !== 'queueMicrotask' && key !== 'structuredClone') {\\n" +
                "           funcName = key;\\n" +
                "           break;\\n" +
                "       }\\n" +
                "    }\\n" +
                "    \\n" +
                "    // If it's declared with var/let/const it might not be on global object directly\\n" +
                "    // In that case, we can try to extract the first variable declared\\n" +
                "    const userFunc = eval(Object.keys(testCases[0].input)[0] ? Object.keys({ " + code.split("=")[0].replace("var", "").replace("let", "").replace("const", "").trim() + " })[0] : null) || eval('twoSum') || eval('reverseString');\\n" +
                "\\n" +
                "    const results = [];\\n" +
                "    for (const tc of testCases) {\\n" +
                "        const inputs = tc.input;\\n" +
                "        const expected = tc.output;\\n" +
                "        \\n" +
                "        const start = performance.now();\\n" +
                "        try {\\n" +
                "            let result;\\n" +
                "            if (typeof inputs === 'object' && !Array.isArray(inputs)) {\\n" +
                "                result = userFunc(...Object.values(inputs));\\n" +
                "            } else {\\n" +
                "                result = userFunc(inputs);\\n" +
                "            }\\n" +
                "            const duration = Math.round(performance.now() - start);\\n" +
                "            const passed = JSON.stringify(result) === JSON.stringify(expected);\\n" +
                "            results.push({ passed, input: inputs, output: result, expected, runTimeMs: duration });\\n" +
                "        } catch (e) {\\n" +
                "            results.push({ passed: false, input: inputs, error: e.toString(), expected, runTimeMs: 0 });\\n" +
                "        }\\n" +
                "    }\\n" +
                "    console.log(JSON.stringify(results));\\n" +
                "} catch (e) {\\n" +
                "    console.error('COMPILE_ERROR: ' + e.message);\\n" +
                "    process.exit(1);\\n" +
                "}\\n";

        ExecutionResult result = dockerProcessRunner.executeInDocker("node:20-alpine", "node -", wrapper);
        if ("RUNTIME_ERROR".equals(result.getStatus()) && result.getErrorMessage() != null && result.getErrorMessage().contains("COMPILE_ERROR")) {
            result.setStatus("COMPILE_ERROR");
        }
        return result;
    }
}
