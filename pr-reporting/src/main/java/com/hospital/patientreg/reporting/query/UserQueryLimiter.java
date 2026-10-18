package com.hospital.patientreg.reporting.query;

import com.hospital.patientreg.shared.tenant.TenantContext;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Guard rail (architecture section 14): one user can run only a few reports at the same time, so a user
 * who clicks Run repeatedly, or opens many tabs, cannot take the analytics store from everybody else.
 * Extra requests are refused at once with 429 instead of queueing.
 */
@Component
public class UserQueryLimiter {

    static final int MAX_PER_USER = 3;

    private final ConcurrentMap<Long, Semaphore> running = new ConcurrentHashMap<>();

    public <T> T run(Supplier<T> query) {
        Semaphore slots = running.computeIfAbsent(TenantContext.require().userId(), id -> new Semaphore(MAX_PER_USER));
        if (!slots.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You already have " + MAX_PER_USER + " reports running. Wait for them to finish.");
        }
        try {
            return query.get();
        } finally {
            slots.release();
        }
    }
}
