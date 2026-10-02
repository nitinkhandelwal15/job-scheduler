package com.nitinkhandelwal.job_scheduler.heartbeat;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class HeartbeatService {

    private static final String KEY_PREFIX = "worker:heartbeat:";
    private static final Duration TTL = Duration.ofSeconds(6);

    private final StringRedisTemplate redisTemplate;

    public HeartbeatService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void sendHeartbeat(String workerId) {
        redisTemplate.opsForValue().set(KEY_PREFIX + workerId, LocalDateTime.now().toString(), TTL);
    }

    public boolean isWorkerAlive(String workerId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + workerId));
    }
}
