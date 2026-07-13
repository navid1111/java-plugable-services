package com.example.leetcode.service.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DynamicRunnerSafetyTest {
    private final DockerProcessRunner docker = mock(DockerProcessRunner.class);

    @Test
    void javascriptSupportsAdminDefinedFunctionNamesAndDoesNotInterpolateTestJson() {
        when(docker.executeInDocker(anyString(), anyString(), anyString()))
                .thenReturn(new ExecutionResult("ACCEPTED", 1, 1, 1, null));
        var runner = new JavascriptRunner(docker);
        String hostileJson = "[{\"input\":{\"value\":\"`${process.exit(1)}`\"},\"output\":\"ok\"}]";

        runner.runCode("const customSolver = (value) => 'ok';", hostileJson);

        ArgumentCaptor<String> wrapper = ArgumentCaptor.forClass(String.class);
        verify(docker).executeInDocker(anyString(), anyString(), wrapper.capture());
        assertThat(wrapper.getValue()).contains("typeof customSolver === 'function'");
        assertThat(wrapper.getValue()).doesNotContain("`${process.exit(1)}`");
    }

    @Test
    void pythonDoesNotInterpolateTestJsonIntoGeneratedSource() {
        when(docker.executeInDocker(anyString(), anyString(), anyString()))
                .thenReturn(new ExecutionResult("ACCEPTED", 1, 1, 1, null));
        var runner = new PythonRunner(docker);
        String hostileJson = "[{\"input\":{\"value\":\"''' ; raise SystemExit()\"},\"output\":\"ok\"}]";

        runner.runCode("class Solution:\n    def solve(self, value): return 'ok'", hostileJson);

        ArgumentCaptor<String> wrapper = ArgumentCaptor.forClass(String.class);
        verify(docker).executeInDocker(anyString(), anyString(), wrapper.capture());
        assertThat(wrapper.getValue()).contains("base64.b64decode");
        assertThat(wrapper.getValue()).doesNotContain("raise SystemExit()");
    }
}
