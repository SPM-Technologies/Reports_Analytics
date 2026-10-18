package com.hospital.patientreg.shared.tenant;

import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The current request's {@link Tenant}. Set by the identity module's filter after authentication and
 * cleared when the request ends. Code without a tenant (no signed-in user) sees no patient data: the
 * database session gets an empty hospital list and row-level security returns nothing (fails closed).
 */
public final class TenantContext {

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Tenant tenant) {
        CURRENT.set(tenant);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<Tenant> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The signed-in user, or 401 when there is none. */
    public static Tenant require() {
        Tenant t = CURRENT.get();
        if (t == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in");
        }
        return t;
    }
}
