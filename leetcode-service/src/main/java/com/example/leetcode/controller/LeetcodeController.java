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

@RestController @RequestMapping("/leetcode")
public class LeetcodeController {
    private final ProblemRepository problems; private final SubmissionRepository submissions; private final SubmissionService service; private final JwtHelper jwt;
    public LeetcodeController(ProblemRepository p,SubmissionRepository s,SubmissionService service,JwtHelper jwt){problems=p;submissions=s;this.service=service;this.jwt=jwt;}
    @GetMapping("/problems")
    public Map<String,Object> list(@RequestParam(defaultValue="1") @Min(1) int page,@RequestParam(defaultValue="100") @Min(1) @Max(100) int limit){
        var r=problems.findAll(PageRequest.of(page-1,limit)); var items=r.getContent().stream().map(p->new ProblemSummary(p.getId(),p.getTitle(),p.getDifficulty(),p.getTags())).toList();
        return Map.of("items",items,"total",r.getTotalElements(),"page",page);
    }
    @GetMapping("/problems/{id}") public ProblemDetail detail(@PathVariable String id){Problem p=problems.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Problem not found"));return new ProblemDetail(p.getId(),p.getTitle(),p.getDescription(),p.getDifficulty(),p.getTags(),p.getCodeStubs());}
    @PostMapping("/problems/{id}/submit") public ResponseEntity<SubmissionView> submit(@PathVariable String id,@RequestHeader("Authorization") String auth,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestParam(required=false) String competitionId,@Valid @RequestBody SubmitRequest request){
        if(key!=null&&key.length()>255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Idempotency-Key is too long");
        var identity=jwt.extractIdentity(auth);
        Submission s=service.submit(identity.userId(),identity.username(),id,competitionId,request.language(),request.code(),key);
        return ResponseEntity.accepted().location(URI.create("/leetcode/submissions/"+s.getId())).body(SubmissionView.of(s));
    }
    @GetMapping("/submissions/{id}") public SubmissionView submission(@PathVariable Long id,@RequestHeader("Authorization") String auth){String userId=jwt.extractIdentity(auth).userId();Submission s=submissions.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Submission not found"));if(!userId.equals(s.getUserId()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Submission not found");return SubmissionView.of(s);}
    public record SubmitRequest(@NotBlank @Size(max=65536) String code,@NotBlank String language){}
    public record ProblemSummary(String id,String title,String difficulty,String tags){}
    public record ProblemDetail(String id,String title,String description,String difficulty,String tags,String codeStubs){}
    public record SubmissionView(Long id,String problemId,String language,String status,Integer passedCount,Integer totalCount,Integer executionTimeMs,String errorMessage,String competitionId,java.time.Instant submittedAt,java.time.Instant completedAt){static SubmissionView of(Submission s){return new SubmissionView(s.getId(),s.getProblemId(),s.getLanguage(),s.getStatus(),s.getPassedCount(),s.getTotalCount(),s.getExecutionTimeMs(),s.getErrorMessage(),s.getCompetitionId(),s.getSubmittedAt(),s.getCompletedAt());}}
}
