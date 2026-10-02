
package com.nitinkhandelwal.job_scheduler.worker;

import com.nitinkhandelwal.job_scheduler.entity.Job;
import com.nitinkhandelwal.job_scheduler.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class JobClaimService {

    private final JobRepository jobRepository;

    public JobClaimService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional
    public Long claimNextJob(String workerId) {
        Long jobId = jobRepository.findNextClaimableJobId(LocalDateTime.now());
        if (jobId == null) {
            return null;
        }
        jobRepository.markJobAsRunning(jobId, workerId);
        return jobId;
    }

    @Transactional
    public void updateJobHeartbeat(Long jobId) {
        jobRepository.updateHeartbeat(jobId);
    }

    @Transactional
    public void markCompleted(Long jobId) {
        Job job = jobRepository.findById(jobId).orElseThrow();
        job.setStatus("COMPLETED");
        jobRepository.save(job);
    }
}