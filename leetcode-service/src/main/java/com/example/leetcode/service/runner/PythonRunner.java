package com.example.leetcode.service.runner;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class PythonRunner implements CodeRunner {

    private final DockerProcessRunner dockerProcessRunner;

    public PythonRunner(DockerProcessRunner dockerProcessRunner) {
        this.dockerProcessRunner = dockerProcessRunner;
    }

    @Override
    public boolean supports(String language) {
        return "python".equalsIgnoreCase(language);
    }

    @Override
    public ExecutionResult runCode(String code, String testCasesJson) {
        String encodedTestCases = Base64.getEncoder()
                .encodeToString(testCasesJson.getBytes(StandardCharsets.UTF_8));
        String wrapper = String.join("\n",
                "import base64",
                "import json",
                "import sys",
                "import time",
                "",
                "# --- User Code ---")
                + "\n" + code + "\n"
                + String.join("\n",
                "# -----------------",
                "",
                "test_cases = json.loads(base64.b64decode('" + encodedTestCases + "').decode('utf-8'))",
                "results = []",
                "",
                "try:",
                "    solution = Solution()",
                "    method_name = [m for m in dir(solution) if not m.startswith('__')][0]",
                "    func = getattr(solution, method_name)",
                "",
                "    for tc in test_cases:",
                "        inputs = tc['input']",
                "        expected = tc['output']",
                "",
                "        start_time = time.perf_counter()",
                "        try:",
                "            if isinstance(inputs, dict):",
                "                result = func(**inputs)",
                "            else:",
                "                result = func(*inputs)",
                "            duration = int((time.perf_counter() - start_time) * 1000)",
                "            passed = (result == expected)",
                "            results.append({'passed': passed, 'input': inputs, 'output': result, 'expected': expected, 'runTimeMs': duration})",
                "        except Exception as e:",
                "            results.append({'passed': False, 'input': inputs, 'error': str(e), 'expected': expected, 'runTimeMs': 0})",
                "except Exception as e:",
                "    print('COMPILE_ERROR: ' + str(e), file=sys.stderr)",
                "    sys.exit(1)",
                "",
                "print(json.dumps(results))",
                "");

        ExecutionResult result = dockerProcessRunner.executeInDocker("python:3.11-alpine", "python -", wrapper);
        if ("RUNTIME_ERROR".equals(result.getStatus()) && result.getErrorMessage() != null && result.getErrorMessage().contains("COMPILE_ERROR")) {
            result.setStatus("COMPILE_ERROR");
        }
        return result;
    }
}
