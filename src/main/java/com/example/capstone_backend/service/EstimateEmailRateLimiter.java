package com.example.capstone_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

/** Single-instance abuse guard. Use a shared limiter if the service is scaled out. */
@Component
public class EstimateEmailRateLimiter {
    private final ConcurrentHashMap<String, ArrayDeque<Long>> perIp = new ConcurrentHashMap<>();
    private final ArrayDeque<Long> global = new ArrayDeque<>();
    private final int maxPerIp;
    private final int maxGlobal;

    public EstimateEmailRateLimiter(
            @Value("${capstone.mail.rate-limit.per-ip:3}") int maxPerIp,
            @Value("${capstone.mail.rate-limit.global:60}") int maxGlobal) {
        this.maxPerIp = Math.max(1, maxPerIp);
        this.maxGlobal = Math.max(1, maxGlobal);
    }

    public synchronized void acquire(String remoteIp) {
        long now = Instant.now().toEpochMilli();
        long perIpCutoff = now - 15 * 60 * 1000L;
        long globalCutoff = now - 60 * 60 * 1000L;
        removeExpired(global, globalCutoff);
        // Prune old keys to avoid accumulating data from abandoned clients.
        perIp.values().forEach(times -> removeExpired(times, perIpCutoff));
        perIp.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        String ip = remoteIp == null ? "unknown" : remoteIp;
        ArrayDeque<Long> times = perIp.computeIfAbsent(ip, ignored -> new ArrayDeque<>());
        if (times.size() >= maxPerIp || global.size() >= maxGlobal) {
            throw new EstimateEmailException(HttpStatus.TOO_MANY_REQUESTS, "MAIL_RATE_LIMIT",
                    "이메일 발송 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        times.addLast(now);
        global.addLast(now);
    }

    private static void removeExpired(ArrayDeque<Long> values, long cutoff) {
        while (!values.isEmpty() && values.peekFirst() < cutoff) values.removeFirst();
    }
}
