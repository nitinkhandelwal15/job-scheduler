package com.nitinkhandelwal.job_scheduler.worker;

import com.nitinkhandelwal.job_scheduler.entity.Job;
import com.nitinkhandelwal.job_scheduler.repository.JobRepository;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class JobWorkerConcurrencyTest {

    @Autowired
    private JobWorker jobWorker;

    @Autowired
    private JobRepository jobRepository;

    @RepeatedTest(10)
    void onlyOneThreadClaimsTheJob() throws InterruptedException {
        Job job = new Job();
        job.setIdempotencyKey("concurrency-test-" + System.currentTimeMillis());
        job.setTaskType("TEST_TASK");
        job.setPayload("{}");
        job.setScheduledAt(LocalDateTime.now().minusMinutes(1));
        job.setStatus("PENDING");
        Job saved = jobRepository.save(job);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    Long claimedId = jobWorker.claimAndProcess();
                    if (saved.getId().equals(claimedId)) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();       // wait until all threads are up and waiting
        startLatch.countDown();   // release them all at once
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly one thread should have claimed the job");
    }
}
