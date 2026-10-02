package com.nitinkhandelwal.job_scheduler.worker;

import com.nitinkhandelwal.job_scheduler.entity.Job;
import com.nitinkhandelwal.job_scheduler.repository.JobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
public class JobWorker {

    private final JobRepository jobRepository;
    private final String workerId = "worker-" + System.currentTimeMillis();

    public JobWorker(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public Long claimAndProcess() {
        Long jobId = jobRepository.findNextClaimableJobId(LocalDateTime.now());
        if (jobId == null) {
            return null;
        }

        jobRepository.markJobAsRunning(jobId, workerId);
        log.info("Worker {} claimed job {}", workerId, jobId);

        // Placeholder for real task execution — added in a later phase.
        Job job = jobRepository.findById(jobId).orElseThrow();
        job.setStatus("COMPLETED");
        jobRepository.save(job);
        log.info("Worker {} completed job {}", workerId, jobId);

        return jobId;
    }
}
