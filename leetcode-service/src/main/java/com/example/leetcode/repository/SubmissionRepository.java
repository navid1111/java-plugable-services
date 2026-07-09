package com.example.leetcode.repository;

import com.example.leetcode.model.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubmissionRepository extends JpaRepository<Submission, Long> {
    List<Submission> findByCompetitionIdAndStatus(String competitionId, String status);

    @org.springframework.data.jpa.repository.Query(
        "SELECT s.username AS username, COUNT(DISTINCT s.problemId) AS solvedCount, MAX(s.submittedAt) AS lastSolveTime " +
        "FROM Submission s " +
        "WHERE s.competitionId = :competitionId AND s.status = 'ACCEPTED' " +
        "GROUP BY s.username " +
        "ORDER BY solvedCount DESC, lastSolveTime ASC"
    )
    List<LeaderboardRow> getLeaderboard(@org.springframework.data.repository.query.Param("competitionId") String competitionId);

    interface LeaderboardRow {
        String getUsername();
        Integer getSolvedCount();
        java.time.Instant getLastSolveTime();
    }
}
