package com.example.leetcode.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.leetcode.model.Problem;
import com.example.leetcode.repository.ProblemRepository;
import com.example.leetcode.security.JwtHelper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class ProblemAdminControllerTest {
    private final ProblemRepository problems = mock(ProblemRepository.class);
    private final JwtHelper jwt = mock(JwtHelper.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProblemAdminController controller = new ProblemAdminController(problems, jwt, mapper);

    @Test
    void adminCreatesStructuredPublicAndHiddenTestCases() {
        when(problems.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var request = new ProblemAdminController.ProblemWriteRequest(
                "sum-two", "Sum Two", "Return a + b", "easy", List.of("math"),
                Map.of("javascript", "function sum(a, b) { return a + b; }"),
                List.of(
                        new ProblemAdminController.TestCaseInput(mapper.readTree("{\"a\":2,\"b\":3}"), mapper.readTree("5"), false),
                        new ProblemAdminController.TestCaseInput(mapper.readTree("{\"a\":-2,\"b\":2}"), mapper.readTree("0"), true)));

        var response = controller.create("Bearer admin", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().difficulty()).isEqualTo("EASY");
        assertThat(response.getBody().testCases().get(1).path("hidden").asBoolean()).isTrue();
        verify(jwt).requireAdmin("Bearer admin");
    }

    @Test
    void normalUserCannotReachProblemPersistence() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator role required"))
                .when(jwt).requireAdmin("Bearer user");
        var request = new ProblemAdminController.ProblemWriteRequest(
                "sum-two", "Sum Two", "Return a + b", "EASY", List.of(),
                Map.of("javascript", "function sum(a, b) { return a + b; }"),
                List.of(new ProblemAdminController.TestCaseInput(mapper.readTree("{}"), mapper.readTree("0"), true)));

        assertThatThrownBy(() -> controller.create("Bearer user", request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
