package com.hospital.patientreg.identity.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Slows down password guessing: after {@code identity.login.maxFailures} wrong passwords for one username from
 * one address, that address may not try that username for a while. The block starts at one minute and doubles
 * with every further block, up to {@code identity.login.lockMinutes}, so a guesser is slowed down a lot while
 * someone deliberately locking out a colleague (behind Docker's NAT every browser has the same address) only
 * causes short pauses. Unknown and known usernames behave the same, so the sign-in page does not reveal which
 * usernames exist. Kept in memory (one instance); an administrator can lift a block on the Users screen.
 */
@Component
public class LoginThrottle {

    private static final int MAX_ENTRIES = 10_000;

    private record Key(String username, String address) {
    }

    private static final class Attempts {
        int failures;
        int blocks;
        Instant blockedUntil;
        Instant last = Instant.now();
    }

    private final ConcurrentHashMap<Key, Attempts> attempts = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final int lockMinutes;

    public LoginThrottle(@Value("${identity.login.maxFailures}") int maxFailures,
                         @Value("${identity.login.lockMinutes}") int lockMinutes) {
        this.maxFailures = maxFailures;
        this.lockMinutes = lockMinutes;
    }

    public boolean blocked(String username, String address) {
        Attempts a = attempts.get(key(username, address));
        return a != null && a.blockedUntil != null && a.blockedUntil.isAfter(Instant.now());
    }

    public void failed(String username, String address) {
        cleanUp();
        if (attempts.size() >= MAX_ENTRIES && !attempts.containsKey(key(username, address))) {
            return;   // full of active blocks: do not grow further (existing blocks stay)
        }
        attempts.compute(key(username, address), (k, a) -> {
            Attempts x = a == null ? new Attempts() : a;
            Instant now = Instant.now();
            if (x.blockedUntil != null && !x.blockedUntil.isAfter(now)) {
                x.blockedUntil = null;
                x.failures = 0;
            }
            x.last = now;
            if (++x.failures >= maxFailures) {
                long minutes = Math.min(Math.max(1, lockMinutes), 1L << Math.min(x.blocks, 10));
                x.blockedUntil = now.plus(minutes, ChronoUnit.MINUTES);
                x.blocks++;
                x.failures = 0;
            }
            return x;
        });
    }

    public void succeeded(String username, String address) {
        attempts.remove(key(username, address));
    }

    /** Usernames currently blocked from at least one address (for the Users screen). */
    public Set<String> blockedUsernames() {
        Instant now = Instant.now();
        return attempts.entrySet().stream()
                .filter(e -> e.getValue().blockedUntil != null && e.getValue().blockedUntil.isAfter(now))
                .map(e -> e.getKey().username()).collect(Collectors.toSet());
    }

    /** An administrator lifts every block of this username. */
    public void unlock(String username) {
        String u = normalise(username);
        attempts.keySet().removeIf(k -> k.username().equals(u));
    }

    /**
     * Forgets old entries. Above a fixed number, the oldest entries that are not blocked go first; active blocks
     * are never dropped (a flood of names must not lift them).
     */
    private void cleanUp() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(Math.max(lockMinutes, 15) * 4L, ChronoUnit.MINUTES);
        attempts.entrySet().removeIf(e -> e.getValue().last.isBefore(cutoff)
                && (e.getValue().blockedUntil == null || e.getValue().blockedUntil.isBefore(now)));
        int excess = attempts.size() - MAX_ENTRIES + 1;
        if (excess > 0) {
            attempts.entrySet().stream()
                    .filter(e -> e.getValue().blockedUntil == null || e.getValue().blockedUntil.isBefore(now))
                    .sorted(Comparator.comparing((Map.Entry<Key, Attempts> e) -> e.getValue().last))
                    .limit(excess).map(Map.Entry::getKey).toList()
                    .forEach(attempts::remove);
        }
    }

    private static Key key(String username, String address) {
        return new Key(normalise(username), address == null ? "" : address);
    }

    private static String normalise(String username) {
        String u = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return u.length() > 40 ? u.substring(0, 40) : u;
    }
}
