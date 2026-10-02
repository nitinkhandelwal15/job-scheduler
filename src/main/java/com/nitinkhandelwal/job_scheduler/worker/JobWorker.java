package com.nitinkhandelwal.job_scheduler.worker;

import com.nitinkhandelwal.job_scheduler.heartbeat.HeartbeatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JobWorker {

    private final JobClaimService jobClaimService;
    private final HeartbeatService heartbeatService;
    private final String workerId = "worker-" + System.currentTimeMillis();

    public JobWorker(JobClaimService jobClaimService, HeartbeatService heartbeatService) {
        this.jobClaimService = jobClaimService;
        this.heartbeatService = heartbeatService;
    }

    @Scheduled(fixedDelay = 2000)
    public void pollAndProcess() {
        Long jobId = jobClaimService.claimNextJob(workerId);
        if (jobId == null) {
            return;
        }
        log.info("Worker {} claimed job {}", workerId, jobId);

        for (int i = 0; i < 3; i++) {
            heartbeatService.sendHeartbeat(workerId);
            jobClaimService.updateJobHeartbeat(jobId);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        jobClaimService.markCompleted(jobId);
        log.info("Worker {} completed job {}", workerId, jobId);
    }
}
