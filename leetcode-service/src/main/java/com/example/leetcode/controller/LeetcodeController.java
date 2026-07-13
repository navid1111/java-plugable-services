package com.example.leetcode.controller;

import com.example.leetcode.model.*;
import com.example.leetcode.repository.*;
import com.example.leetcode.security.JwtHelper;
import com.example.leetcode.service.SubmissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.util.Map;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController @RequestMapping("/leetcode")
public class LeetcodeController {
    private final ProblemRepository problems; private final SubmissionRepository submissions; private final SubmissionService service; private final JwtHelper jwt; private final ObjectMapper mapper;
    public LeetcodeController(ProblemRepository p,SubmissionRepository s,SubmissionService service,JwtHelper jwt,ObjectMapper mapper){problems=p;submissions=s;this.service=service;this.jwt=jwt;this.mapper=mapper;}
    @GetMapping("/problems")
    public Map<String,Object> list(@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="100") @Min(1) @Max(100) int limit){
        var r=problems.findAll(PageRequest.of(page-1,limit)); var items=r.getContent().stream().map(p->new ProblemSummary(p.getId(),p.getTitle(),p.getDifficulty(),json(p.getTags()))).toList();
        return Map.of("items",items,"total",r.getTotalElements(),"page",page);
    }
    @GetMapping("/problems/{id}") public ProblemDetail detail(@PathVariable String id){Problem p=problems.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Problem not found"));return new ProblemDetail(p.getId(),p.getTitle(),p.getDescription(),p.getDifficulty(),json(p.getTags()),json(p.getCodeStubs()),publicExamples(p.getTestCases()));}
    @PostMapping("/problems/{id}/submit") public ResponseEntity<SubmissionView> submit(@PathVariable String id,@RequestHeader("Authorization") String auth,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestParam(required=false) String competitionId,@Valid @RequestBody SubmitRequest request){
        if(key!=null&&key.length()>255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Idempotency-Key is too long");
        var identity=jwt.extractIdentity(auth);
        Submission s=service.submit(identity.userId(),identity.username(),id,competitionId,request.language(),request.code(),key);
        return ResponseEntity.accepted().location(URI.create("/leetcode/submissions/"+s.getId())).body(SubmissionView.of(s));
    }
    @GetMapping("/submissions/{id}") public SubmissionView submission(@PathVariable Long id,@RequestHeader("Authorization") String auth){String userId=jwt.extractIdentity(auth).userId();Submission s=submissions.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Submission not found"));if(!userId.equals(s.getUserId()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Submission not found");return SubmissionView.of(s);}
    public record SubmitRequest(@NotBlank @Size(max=65536) String code,@NotBlank String language){}
    private JsonNode json(String value){return mapper.readTree(value);}
    private List<TestCaseExample> publicExamples(String value){
        JsonNode cases=json(value);if(!cases.isArray())return List.of();
        java.util.ArrayList<TestCaseExample> result=new java.util.ArrayList<>();
        for(JsonNode testCase:cases)if(!testCase.path("hidden").asBoolean(false))result.add(new TestCaseExample(testCase.get("input"),testCase.get("output")));
        return List.copyOf(result);
    }
    public record ProblemSummary(String id,String title,String difficulty,JsonNode tags){}
    public record TestCaseExample(JsonNode input,JsonNode output){}
    public record ProblemDetail(String id,String title,String description,String difficulty,JsonNode tags,JsonNode codeStubs,List<TestCaseExample> examples){}
    public record SubmissionView(Long id,String problemId,String language,String status,Integer passedCount,Integer totalCount,Integer executionTimeMs,String errorMessage,String competitionId,java.time.Instant submittedAt,java.time.Instant completedAt){static SubmissionView of(Submission s){return new SubmissionView(s.getId(),s.getProblemId(),s.getLanguage(),s.getStatus(),s.getPassedCount(),s.getTotalCount(),s.getExecutionTimeMs(),s.getErrorMessage(),s.getCompetitionId(),s.getSubmittedAt(),s.getCompletedAt());}}
}
