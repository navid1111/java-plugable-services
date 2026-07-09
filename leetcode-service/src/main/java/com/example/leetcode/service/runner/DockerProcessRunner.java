package com.example.leetcode.service.runner;

import org.springframework.stereotype.Component;
import java.io.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

@Component
public class DockerProcessRunner {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final int TIMEOUT_SECONDS = 5;

    public ExecutionResult executeInDocker(String image, String commandArgs, String stdinContent) {
        String[] cmd = {
            "sh", "-c", 
            "docker run --rm -i --network none --cpus=0.5 -m 128m " + image + " " + commandArgs
        };

        ProcessBuilder pb = new ProcessBuilder(cmd);
        try {
            Process process = pb.start();

            // Write stdin
            try (OutputStream os = process.getOutputStream();
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os))) {
                writer.write(stdinContent);
                writer.flush();
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ExecutionResult("TIME_LIMIT_EXCEEDED", 0, 0, TIMEOUT_SECONDS * 1000, "Process timed out after " + TIMEOUT_SECONDS + " seconds.");
            }

            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());

            if (process.exitValue() != 0) {
                return new ExecutionResult("RUNTIME_ERROR", 0, 0, 0, stderr.isEmpty() ? "Non-zero exit code: " + process.exitValue() : stderr);
            }

            return parseOutput(stdout, stderr);
        } catch (Exception e) {
            return new ExecutionResult("RUNTIME_ERROR", 0, 0, 0, "Execution failed: " + e.getMessage());
        }
    }

    private String readStream(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\\n");
            }
        }
        return sb.toString().trim();
    }

    private ExecutionResult parseOutput(String stdout, String stderr) {
        try {
            // Find JSON array in stdout (runner outputs it as a JSON array of results)
            int jsonStart = stdout.indexOf('[');
            if (jsonStart == -1) {
                return new ExecutionResult("RUNTIME_ERROR", 0, 0, 0, "No JSON result found. Output: " + stdout + "\\n" + stderr);
            }
            String jsonOutput = stdout.substring(jsonStart);
            JsonNode results = objectMapper.readTree(jsonOutput);
            
            if (!results.isArray()) {
                return new ExecutionResult("RUNTIME_ERROR", 0, 0, 0, "Output is not a valid result array.");
            }

            int passed = 0;
            int total = results.size();
            int maxTime = 0;
            String errorMsg = "";

            for (JsonNode res : results) {
                if (res.has("passed") && res.get("passed").asBoolean()) {
                    passed++;
                } else if (res.has("error")) {
                    errorMsg = res.get("error").asText();
                } else if (res.has("output") && res.has("expected")) {
                    if (errorMsg.isEmpty()) {
                        errorMsg = "Expected " + res.get("expected") + " but got " + res.get("output");
                    }
                }
                
                if (res.has("runTimeMs")) {
                    maxTime = Math.max(maxTime, res.get("runTimeMs").asInt());
                }
            }

            if (passed == total && total > 0) {
                return new ExecutionResult("ACCEPTED", passed, total, maxTime, "");
            } else {
                return new ExecutionResult("WRONG_ANSWER", passed, total, maxTime, errorMsg);
            }
        } catch (Exception e) {
            return new ExecutionResult("RUNTIME_ERROR", 0, 0, 0, "Failed to parse runner output: " + e.getMessage() + "\\nRaw Output: " + stdout);
        }
    }
}
