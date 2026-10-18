package com.hospital.patientreg.shared.export;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Caps how many report downloads run at once. Each download holds a database connection for
 * its whole duration (a 1 lakh row PDF takes ~10 s), so without a cap a burst of downloads
 * takes every pooled connection and ordinary screens fail.
 */
@Component
public class ExportLimiter {

    private static final long WAIT_SECONDS = 20;

    private final Semaphore permits;

    public ExportLimiter(@Value("${export.maxConcurrent}") int maxConcurrent) {
        this.permits = new Semaphore(maxConcurrent, true);
    }

    public interface Export {
        void run() throws IOException;
    }

    /** Runs the export when a slot is free; waits briefly, then reports "busy" instead of piling up. */
    public void run(Export export) throws IOException {
        // one download per user at a time: one person cannot take every slot (review finding)
        Long user = com.hospital.patientreg.shared.tenant.TenantContext.current()
                .map(com.hospital.patientreg.shared.tenant.Tenant::userId).orElse(null);
        if (user != null && !running.add(user)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Your previous download is still being prepared. Please wait until it has finished.");
        }
        try {
            runWithSlot(export);
        } finally {
            if (user != null) {
                running.remove(user);
            }
        }
    }

    private final java.util.Set<Long> running = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void runWithSlot(Export export) throws IOException {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The server is busy preparing other downloads. Please try again in a minute.");
        }
        try {
            export.run();
        } finally {
            permits.release();
        }
    }
}
