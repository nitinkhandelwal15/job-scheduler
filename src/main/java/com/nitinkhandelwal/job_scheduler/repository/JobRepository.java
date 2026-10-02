package com.nitinkhandelwal.job_scheduler.repository;

import com.nitinkhandelwal.job_scheduler.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long> {

    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    @Query(value = """
            SELECT id FROM jobs
            WHERE status = 'PENDING' AND scheduled_at <= :now
            ORDER BY priority DESC, scheduled_at ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Long findNextClaimableJobId(@Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE jobs
            SET status = 'RUNNING', locked_by = :workerId, last_heartbeat = NOW(), attempts = attempts + 1
            WHERE id = :jobId
            """, nativeQuery = true)
    int markJobAsRunning(@Param("jobId") Long jobId, @Param("workerId") String workerId);
}
