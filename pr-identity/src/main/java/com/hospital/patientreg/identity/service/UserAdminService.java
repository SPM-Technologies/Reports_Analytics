package com.hospital.patientreg.identity.service;

import com.hospital.patientreg.identity.dao.IdentityDao;
import com.hospital.patientreg.identity.model.Hospital;
import com.hospital.patientreg.identity.model.RoleDef;
import com.hospital.patientreg.identity.model.UserSummary;
import com.hospital.patientreg.identity.security.AccessVersion;
import com.hospital.patientreg.identity.security.LoginThrottle;
import com.hospital.patientreg.shared.tenant.AuditTrail;
import com.hospital.patientreg.shared.tenant.Permission;
import com.hospital.patientreg.shared.tenant.Tenant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * User administration. A hospital administrator manages the roles of users in the hospitals they
 * administer; a super administrator manages every user and hospital. Rules:
 * <ul>
 *   <li>Roles can only be given or taken away in hospitals the administrator manages.</li>
 *   <li>Name, password and active flag of a user who also works in other hospitals can only be changed by an
 *       administrator of all of them (or a super administrator), so one hospital cannot take over another's user.</li>
 *   <li>Nobody can change their own roles, deactivate themselves or remove their own super-administrator flag.</li>
 * </ul>
 */
@Service
public class UserAdminService {

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,39}");

    private final IdentityDao dao;
    private final PasswordEncoder encoder;
    private final AuditTrail audit;
    private final LoginThrottle throttle;
    private final AccessVersion accessVersion;

    public UserAdminService(IdentityDao dao, PasswordEncoder encoder, AuditTrail audit, LoginThrottle throttle,
                            AccessVersion accessVersion) {
        this.throttle = throttle;
        this.accessVersion = accessVersion;
        this.dao = dao;
        this.encoder = encoder;
        this.audit = audit;
    }

    /** Hospitals whose users this administrator may manage (403 if none). */
    public List<Hospital> manageableHospitals(Tenant t) {
        if (t.superAdmin()) {
            return dao.hospitals();
        }
        Set<Integer> ids = t.hospitalsWith(Permission.MANAGE_USERS);
        if (ids.isEmpty()) {
            throw Tenant.forbidden("Only administrators can manage users");
        }
        return dao.hospitals().stream().filter(h -> ids.contains(h.hospitalId())).toList();
    }

    /**
     * Roles the administrator can give: built-in roles and the own roles of the hospitals they manage, but only
     * roles whose permissions they hold themselves in one of those hospitals (no escalation through assignment).
     */
    public List<RoleDef> assignableRoles(Tenant t) {
        Set<Integer> mine = manageableIds(t);
        return dao.roles(mine).stream()
                .filter(r -> t.superAdmin() || mine.stream().anyMatch(h -> r.usableIn(h) && grantable(t, r, h)))
                .toList();
    }

    public List<UserSummary> listUsers(Tenant t) {
        return dao.users(t.superAdmin() ? null : manageableIds(t));
    }

    /** The users list as shown: only the assignments in hospitals this administrator manages. */
    public List<UserSummary> listUsersForDisplay(Tenant t) {
        if (t.superAdmin()) {
            return listUsers(t);
        }
        Set<Integer> mine = manageableIds(t);
        return listUsers(t).stream().map(u -> new UserSummary(u.userId(), u.username(), u.fullName(), u.superAdmin(), u.active(),
                u.lockedUntil(), u.lastLoginAt(), u.assignments().stream().filter(a -> mine.contains(a.hospitalId())).toList()))
                .toList();
    }

    /** Whether the administrator holds every permission of the role in that hospital. */
    private static boolean grantable(Tenant t, RoleDef r, int hospitalId) {
        return t.superAdmin() || r.permissions().stream().allMatch(p -> t.can(p, hospitalId));
    }

    public UserSummary getUser(Tenant t, long userId) {
        return listUsers(t).stream().filter(u -> u.userId() == userId).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    /** Whether the administrator may change the user's name, password and active flag. */
    public boolean canEditAccount(Tenant t, UserSummary user) {
        if (t.superAdmin()) {
            return true;
        }
        Set<Integer> mine = manageableIds(t);
        return !user.superAdmin() && user.assignments().stream().allMatch(a -> mine.contains(a.hospitalId()));
    }

    @Transactional
    public long create(Tenant t, UserForm f) {
        Set<Integer> mine = manageableIds(t);
        String username = AppUserDetailsService.normalise(f.getUsername());
        if (!USERNAME.matcher(username).matches()) {
            throw new InvalidInputException("Username: 3-40 characters, lower-case letters, digits, '.', '_' or '-'");
        }
        if (dao.usernameExists(username)) {
            throw new InvalidInputException("The username '" + username + "' is already taken");
        }
        String name = fullName(f);
        checkPassword(f.getPassword());
        List<String> changes = new ArrayList<>();
        boolean anyRole = f.getRoles().entrySet().stream().anyMatch(e -> mine.contains(e.getKey()) && role(e.getValue(), e.getKey()) != null);
        if (!anyRole) {   // also super administrators: signing in needs a hospital to work in
            throw new InvalidInputException("Give the user a role in at least one hospital");
        }
        for (int h : mine) {
            requireGrantable(t, role(f.getRoles().get(h), h), h);
        }
        long id = dao.insertUser(username, name, encoder.encode(f.getPassword()), t.superAdmin() && f.isSuperAdmin());
        for (int h : mine) {
            RoleDef r = role(f.getRoles().get(h), h);
            if (r != null) {
                dao.assign(id, h, r.key());
                changes.add(code(h) + "=" + r.key());
            }
        }
        audit.record("USER_CREATED", firstAssigned(f, mine), username + " (" + name + ") roles " + changes
                + (t.superAdmin() && f.isSuperAdmin() ? ", super administrator" : ""));
        return id;
    }

    @Transactional
    public void update(Tenant t, long userId, UserForm f) {
        UserSummary user = getUser(t, userId);
        boolean self = user.userId() == t.userId();
        List<String> changes = new ArrayList<>();

        if (canEditAccount(t, user)) {
            String name = fullName(f);
            boolean active = self || f.isActive();                         // cannot deactivate yourself
            boolean superAdmin = t.superAdmin() ? (self || f.isSuperAdmin()) : user.superAdmin();
            if (!name.equals(user.fullName())) changes.add("name");
            if (active != user.active()) changes.add(active ? "activated" : "deactivated");
            if (superAdmin != user.superAdmin()) changes.add(superAdmin ? "made super administrator" : "super administrator removed");
            dao.updateUser(userId, name, active, superAdmin);
            if (f.getPassword() != null && !f.getPassword().isEmpty()) {
                checkPassword(f.getPassword());
                dao.updatePassword(userId, encoder.encode(f.getPassword()));
                changes.add("password reset");
            }
        }
        // only a super administrator changes a super administrator's hospitals (review finding)
        if (!self && (t.superAdmin() || !user.superAdmin())) {
            java.util.Map<Integer, String> result = new java.util.HashMap<>();
            user.assignments().forEach(a -> result.put(a.hospitalId(), a.role().key()));
            for (int h : manageableIds(t)) {
                if (!f.getRoles().containsKey(h)) {
                    continue;   // hospital not on the submitted form (e.g. added meanwhile): leave it as it is
                }
                RoleDef beforeRole = user.roleIn(h);
                String before = beforeRole == null ? null : beforeRole.key();
                RoleDef afterRole = role(f.getRoles().get(h), h);
                String after = afterRole == null ? null : afterRole.key();
                if (!java.util.Objects.equals(before, after)) {
                    // both the old and the new role must lie within the administrator's own permissions there
                    requireGrantable(t, beforeRole, h);
                    requireGrantable(t, afterRole, h);
                    if (after == null) {
                        result.remove(h);
                    } else {
                        result.put(h, after);
                    }
                    changes.add(code(h) + ": " + (before == null ? "-" : before) + " -> " + (after == null ? "-" : after));
                }
            }
            if (result.isEmpty() && !user.assignments().isEmpty()) {
                throw new InvalidInputException("A user needs a role in at least one hospital; deactivate the account instead");
            }
            for (int h : manageableIds(t)) {
                String now = user.roleIn(h) == null ? null : user.roleIn(h).key();
                if (!java.util.Objects.equals(now, result.get(h))) {
                    dao.assign(userId, h, result.get(h));
                }
            }
        }
        if (!changes.isEmpty()) {
            Integer hospital = user.assignments().stream().map(UserSummary.Assignment::hospitalId)
                    .filter(manageableIds(t)::contains).findFirst().orElse(firstAssigned(f, manageableIds(t)));
            audit.record("USER_UPDATED", hospital, user.username() + ": " + String.join(", ", changes));
            accessVersion.changed();   // the user's open sessions pick the change up on their next request
        }
    }

    @Transactional
    public void unlock(Tenant t, long userId) {
        UserSummary user = getUser(t, userId);
        dao.unlock(userId);
        throttle.unlock(user.username());
        audit.record("USER_UNLOCKED", null, user.username());
    }

    /** Signed-in users changing their own password. */
    @Transactional
    public void changeOwnPassword(Tenant t, String current, String next) {
        String hash = dao.loadUser(t.username()).map(u -> u.getPassword()).orElse("");
        if (current == null || !encoder.matches(current, hash)) {
            throw new InvalidInputException("The current password is not correct");
        }
        checkPassword(next);
        dao.updatePassword(t.userId(), encoder.encode(next));   // ends the user's other sessions (session epoch)
        audit.record("PASSWORD_CHANGED", null, t.username());
        accessVersion.changed();
    }

    // ---------------------------------------------------------------- helpers

    private static Integer firstAssigned(UserForm f, Set<Integer> mine) {
        return f.getRoles().entrySet().stream().filter(e -> mine.contains(e.getKey()) && e.getValue() != null && !e.getValue().isBlank())
                .map(java.util.Map.Entry::getKey).sorted().findFirst().orElse(null);
    }

    /** Usernames currently blocked by the sign-in throttle (shown as "locked" on the Users screen). */
    public Set<String> blockedUsernames() {
        return throttle.blockedUsernames();
    }

    private Set<Integer> manageableIds(Tenant t) {
        return manageableHospitals(t).stream().map(Hospital::hospitalId).collect(Collectors.toSet());
    }

    private String code(int hospitalId) {
        return dao.hospital(hospitalId).map(Hospital::code).orElse(String.valueOf(hospitalId));
    }

    private static void requireGrantable(Tenant t, RoleDef r, int hospitalId) {
        if (r != null && !grantable(t, r, hospitalId)) {
            throw Tenant.forbidden("You cannot give or change the role " + r.label()
                    + " because it has permissions you do not have in this hospital");
        }
    }

    /** The role chosen for a hospital: a built-in role or one of that hospital's own roles. */
    private RoleDef role(String value, int hospitalId) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return dao.role(value).filter(r -> r.usableIn(hospitalId))
                .orElseThrow(() -> new InvalidInputException("Unknown role " + value + " for this hospital"));
    }

    private static String fullName(UserForm f) {
        String name = f.getFullName() == null ? "" : f.getFullName().trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new InvalidInputException("Full name: 1-100 characters");
        }
        return name;
    }

    /** At least 8 characters with a letter and a digit; at most 72 (the BCrypt limit). */
    static void checkPassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 72
                || !password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*")) {
            throw new InvalidInputException("Password: 8-72 characters with at least one letter and one digit");
        }
    }
}
