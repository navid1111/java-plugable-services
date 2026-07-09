package com.example.leetcode.service.runner;

import org.springframework.stereotype.Component;

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
        String wrapper = "import json\\n" +
                "import sys\\n" +
                "import time\\n" +
                "\\n" +
                "# --- User Code ---\\n" +
                code + "\\n" +
                "# -----------------\\n" +
                "\\n" +
                "test_cases = json.loads('''" + testCasesJson + "''')\\n" +
                "results = []\\n" +
                "\\n" +
                "try:\\n" +
                "    solution = Solution()\\n" +
                "    # Get method name\\n" +
                "    method_name = [m for m in dir(solution) if not m.startswith('__')][0]\\n" +
                "    func = getattr(solution, method_name)\\n" +
                "    \\n" +
                "    for tc in test_cases:\\n" +
                "        inputs = tc['input']\\n" +
                "        expected = tc['output']\\n" +
                "        \\n" +
                "        start_time = time.perf_counter()\\n" +
                "        try:\\n" +
                "            if isinstance(inputs, dict):\\n" +
                "                result = func(**inputs)\\n" +
                "            else:\\n" +
                "                result = func(*inputs)\\n" +
                "            duration = int((time.perf_counter() - start_time) * 1000)\\n" +
                "            passed = (result == expected)\\n" +
                "            results.append({'passed': passed, 'input': inputs, 'output': result, 'expected': expected, 'runTimeMs': duration})\\n" +
                "        except Exception as e:\\n" +
                "            results.append({'passed': False, 'input': inputs, 'error': str(e), 'expected': expected, 'runTimeMs': 0})\\n" +
                "except Exception as e:\\n" +
                "    print('COMPILE_ERROR: ' + str(e), file=sys.stderr)\\n" +
                "    sys.exit(1)\\n" +
                "\\n" +
                "print(json.dumps(results))\\n";

        ExecutionResult result = dockerProcessRunner.executeInDocker("python:3.11-alpine", "python -", wrapper);
        if ("RUNTIME_ERROR".equals(result.getStatus()) && result.getErrorMessage() != null && result.getErrorMessage().contains("COMPILE_ERROR")) {
            result.setStatus("COMPILE_ERROR");
        }
        return result;
    }
}
