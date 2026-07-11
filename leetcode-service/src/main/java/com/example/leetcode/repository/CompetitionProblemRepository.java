package com.example.leetcode.repository;

import com.example.leetcode.model.CompetitionProblem;
import com.example.leetcode.model.CompetitionProblemId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CompetitionProblemRepository extends JpaRepository<CompetitionProblem, CompetitionProblemId> {
    List<CompetitionProblem> findByCompetitionIdOrderByProblemOrderAsc(String competitionId);
    boolean existsByCompetitionIdAndProblemId(String competitionId, String problemId);
}
