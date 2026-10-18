package com.hospital.patientreg.identity.security;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Changes whenever users, roles or hospitals change. Every signed-in session remembers the version its
 * access was loaded with; {@link TenantFilter} reloads the session's hospitals and roles (or signs the user
 * out) on the next request when the version differs, so taking access away works at once, not at the next
 * sign-in. Starts from a time-based value so that sessions restored after a restart are reloaded too.
 * (One application instance; several instances would keep this stamp in the database.)
 */
@Component
public class AccessVersion {

    private final AtomicLong version = new AtomicLong(System.currentTimeMillis() * 1000);

    public long current() {
        return version.get();
    }

    /**
     * Inside a transaction the version moves on only after the commit: a request reloading the user before that
     * would otherwise store the OLD access under the NEW version and keep it (review finding). Also counted now,
     * so a request in between reloads again after the commit.
     */
    public void changed() {
        version.incrementAndGet();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            version.incrementAndGet();
                        }
                    });
        }
    }
}
