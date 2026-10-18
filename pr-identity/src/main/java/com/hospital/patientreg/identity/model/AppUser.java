package com.hospital.patientreg.identity.model;

import com.hospital.patientreg.shared.tenant.HospitalAccess;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The signed-in user kept in the session: identity plus the hospitals (active ones only) and roles
 * loaded at login. Changes made by an administrator apply at the user's next login; changes to your
 * own account apply at once (the session principal is refreshed).
 */
public final class AppUser implements UserDetails, CredentialsContainer, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long userId;
    private final String username;
    private final String fullName;
    private String passwordHash;
    private final boolean superAdmin;
    private final boolean active;
    private final int sessionEpoch;
    private final List<HospitalAccess> hospitals;

    public AppUser(long userId, String username, String fullName, String passwordHash, boolean superAdmin,
                   boolean active, int sessionEpoch, List<HospitalAccess> hospitals) {
        this.sessionEpoch = sessionEpoch;
        this.userId = userId;
        this.username = username;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.superAdmin = superAdmin;
        this.active = active;
        this.hospitals = List.copyOf(hospitals);
    }

    public long userId() { return userId; }
    public String fullName() { return fullName; }
    public boolean superAdmin() { return superAdmin; }
    public List<HospitalAccess> hospitals() { return hospitals; }
    /** raised on password reset or deactivation: older sessions end */
    public int sessionEpoch() { return sessionEpoch; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return superAdmin ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    /** Deactivated users, and users without any active hospital, cannot sign in. */
    @Override
    public boolean isEnabled() {
        return active && !hospitals.isEmpty();
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
