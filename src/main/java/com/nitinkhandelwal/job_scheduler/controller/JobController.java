package com.nitinkhandelwal.job_scheduler.controller;

import com.nitinkhandelwal.job_scheduler.dto.CreateJobRequest;
import com.nitinkhandelwal.job_scheduler.entity.Job;
import com.nitinkhandelwal.job_scheduler.repository.JobRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobRepository jobRepository;

    public JobController(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @PostMapping
    public ResponseEntity<Job> createJob(@Valid @RequestBody CreateJobRequest request) {
        Optional<Job> existing = jobRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            return ResponseEntity.ok(existing.get());
        }

        Job job = new Job();
        job.setIdempotencyKey(request.getIdempotencyKey());
        job.setTaskType(request.getTaskType());
        job.setPayload(request.getPayload());
        job.setScheduledAt(request.getScheduledAt());
        job.setPriority(request.getPriority());
        job.setMaxAttempts(request.getMaxAttempts());
        job.setStatus("PENDING");

        Job saved = jobRepository.save(job);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Job> getJob(@PathVariable Long id) {
        return jobRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}