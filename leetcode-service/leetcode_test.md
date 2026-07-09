# Testing Strategy

## 1. Unit Testing Code Runners
To test `PythonRunner` and `JavascriptRunner` without relying on actual Docker binaries, you should mock the underlying `DockerProcessRunner`.

```java
@Test
void testPythonRunnerCompileError() {
    DockerProcessRunner mockDocker = Mockito.mock(DockerProcessRunner.class);
    Mockito.when(mockDocker.executeInDocker(any(), any(), any()))
           .thenReturn(new ExecutionResult("RUNTIME_ERROR", 0, 0, 0, "COMPILE_ERROR: Syntax Error"));

    PythonRunner runner = new PythonRunner(mockDocker);
    ExecutionResult result = runner.runCode("invalid python", "[]");
    
    assertEquals("COMPILE_ERROR", result.getStatus());
}
```

## 2. Integration Testing with Real Containers
For verifying the actual execution harness behavior, integration tests should run the real `DockerProcessRunner`. These tests require Docker to be running on the host machine. Testcontainers can optionally be used, but since our code natively invokes Docker, we can just run real `twoSum` submissions and assert that `result.getStatus()` is `ACCEPTED`.

## 3. Kong Integration Testing
Our ultimate truth lies in the `plug/smoke.sh` end-to-end test. It tests the system against the real Kong Gateway.

**Smoke script validation scenarios:**
- **Unauthorized:** Submit code without JWT `-> 401 Unauthorized`.
- **Successful Run:** Submit correct Python code `-> ACCEPTED`.
- **Leaderboard Accuracy:** Submit solutions across multiple users and assert `rank 1` goes to the user with the fastest aggregate completion time.
