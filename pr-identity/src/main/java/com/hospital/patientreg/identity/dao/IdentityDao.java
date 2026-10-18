package com.hospital.patientreg.identity.dao;

import com.hospital.patientreg.identity.model.AppUser;
import com.hospital.patientreg.identity.model.AuditEntry;
import com.hospital.patientreg.identity.model.Hospital;
import com.hospital.patientreg.identity.model.RoleDef;
import com.hospital.patientreg.shared.tenant.Permission;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;
import com.hospital.patientreg.identity.model.UserSummary;
import com.hospital.patientreg.identity.model.UserSummary.Assignment;
import com.hospital.patientreg.shared.tenant.HospitalAccess;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Hospitals, users, hospital roles and the audit log (schema {@code identity}), on the application pool. */
@Repository
public class IdentityDao {

    private final NamedParameterJdbcTemplate jdbc;

    public IdentityDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- login

    /** The user with their hospitals; two queries one after the other, so only one pool connection is held. */
    public Optional<AppUser> loadUser(String username) {
        List<Object[]> rows = jdbc.query("""
                SELECT user_id, username, full_name, password_hash, super_admin, active, session_epoch
                FROM identity.app_user WHERE username = :u""", Map.of("u", username),
                (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), rs.getBoolean(6), rs.getInt(7)});
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = rows.getFirst();
        long id = (Long) r[0];
        return Optional.of(new AppUser(id, (String) r[1], (String) r[2], (String) r[3], (Boolean) r[4], (Boolean) r[5],
                (Integer) r[6], access(id)));
    }

    /** Active hospitals of the user with the permissions of their role there, by hospital code. */
    public List<HospitalAccess> access(long userId) {
        return jdbc.query("""
                SELECT h.hospital_id, h.hospital_code, h.name, r.role_key, r.permissions
                FROM identity.app_user_hospital uh
                JOIN identity.hospital h ON h.hospital_id = uh.hospital_id
                JOIN identity.role r ON r.role_key = uh.role
                WHERE uh.user_id = :id AND h.active ORDER BY h.hospital_code""", Map.of("id", userId),
                (rs, i) -> new HospitalAccess(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        permissions(rs.getArray(5))));
    }

    // ---------------------------------------------------------------- roles

    private static final String ROLE_COLUMNS = "role_key, hospital_id, label, permissions, builtin";

    /** Built-in roles and the own roles of {@code hospitalIds} (null = every hospital's roles). */
    public List<RoleDef> roles(Collection<Integer> hospitalIds) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = " WHERE hospital_id IS NULL";
        if (hospitalIds == null) {
            where = "";
        } else if (!hospitalIds.isEmpty()) {
            where += " OR hospital_id IN (:h)";
            p.addValue("h", hospitalIds);
        }
        return jdbc.query("SELECT " + ROLE_COLUMNS + " FROM identity.role" + where + " ORDER BY NOT builtin, hospital_id, label", p,
                (rs, i) -> role(rs));
    }

    public Optional<RoleDef> role(String key) {
        return jdbc.query("SELECT " + ROLE_COLUMNS + " FROM identity.role WHERE role_key = :k", Map.of("k", key),
                (rs, i) -> role(rs)).stream().findFirst();
    }

    /** Creates or updates a role (key, hospital, label, permissions). */
    public void saveRole(String key, Integer hospitalId, String label, Collection<Permission> permissions, boolean builtin) {
        jdbc.update("""
                INSERT INTO identity.role (role_key, hospital_id, label, permissions, builtin, updated_at)
                VALUES (:k, :h, :l, string_to_array(:p, ',')::VARCHAR(30)[], :b, now())
                ON CONFLICT (role_key) DO UPDATE SET label = EXCLUDED.label, permissions = EXCLUDED.permissions,
                    builtin = EXCLUDED.builtin, updated_at = now()""",
                new MapSqlParameterSource("k", key).addValue("h", hospitalId).addValue("l", label).addValue("b", builtin)
                        .addValue("p", String.join(",", permissions.stream().map(Enum::name).sorted().toList())));
    }

    /** A new hospital role; false if the key was taken meanwhile (never overwrites). */
    public boolean insertRole(String key, int hospitalId, String label, Collection<Permission> permissions) {
        return jdbc.update("""
                INSERT INTO identity.role (role_key, hospital_id, label, permissions, builtin, updated_at)
                VALUES (:k, :h, :l, string_to_array(:p, ',')::VARCHAR(30)[], FALSE, now())
                ON CONFLICT (role_key) DO NOTHING""",
                new MapSqlParameterSource("k", key).addValue("h", hospitalId).addValue("l", label)
                        .addValue("p", String.join(",", permissions.stream().map(Enum::name).sorted().toList()))) == 1;
    }

    public long roleUsage(String key) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM identity.app_user_hospital WHERE role = :k", Map.of("k", key), Long.class);
        return n == null ? 0 : n;
    }

    public void deleteRole(String key) {
        jdbc.update("DELETE FROM identity.role WHERE role_key = :k AND NOT builtin", Map.of("k", key));
    }

    private static RoleDef role(ResultSet rs) throws SQLException {
        int h = rs.getInt(2);
        Integer hospital = rs.wasNull() ? null : h;   // wasNull refers to the last column read: check it at once
        return new RoleDef(rs.getString(1), hospital, rs.getString(3), permissions(rs.getArray(4)), rs.getBoolean(5));
    }

    /** Permission names from the database; names no longer known to the code are ignored. */
    private static Set<Permission> permissions(Array array) throws SQLException {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        if (array != null) {
            for (Object o : (Object[]) array.getArray()) {
                try {
                    out.add(Permission.valueOf(String.valueOf(o)));
                } catch (IllegalArgumentException ignored) {
                    // a permission removed from the code: grants nothing
                }
            }
        }
        return out;
    }

    public void loginSucceeded(long userId) {
        jdbc.update("UPDATE identity.app_user SET failed_logins = 0, locked_until = NULL, last_login_at = now() WHERE user_id = :id",
                Map.of("id", userId));
    }

    // ---------------------------------------------------------------- users

    public long countUsers() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM identity.app_user", Map.of(), Long.class);
        return n == null ? 0 : n;
    }

    public boolean usernameExists(String username) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM identity.app_user WHERE username = :u)", Map.of("u", username), Boolean.class));
    }

    public long insertUser(String username, String fullName, String passwordHash, boolean superAdmin) {
        Long id = jdbc.queryForObject("""
                INSERT INTO identity.app_user (username, full_name, password_hash, super_admin)
                VALUES (:u, :n, :p, :s) RETURNING user_id""",
                new MapSqlParameterSource("u", username).addValue("n", fullName).addValue("p", passwordHash)
                        .addValue("s", superAdmin), Long.class);
        return id == null ? 0 : id;
    }

    public void updateUser(long userId, String fullName, boolean active, boolean superAdmin) {
        jdbc.update("""
                UPDATE identity.app_user SET full_name = :n, active = :a, super_admin = :s, updated_at = now(),
                    session_epoch = session_epoch + CASE WHEN active AND NOT :a THEN 1 ELSE 0 END
                WHERE user_id = :id""",
                new MapSqlParameterSource("id", userId).addValue("n", fullName).addValue("a", active).addValue("s", superAdmin));
    }

    public void updatePassword(long userId, String passwordHash) {
        jdbc.update("""
                UPDATE identity.app_user SET password_hash = :p, failed_logins = 0, locked_until = NULL, updated_at = now(),
                    session_epoch = session_epoch + 1
                WHERE user_id = :id""", new MapSqlParameterSource("id", userId).addValue("p", passwordHash));
    }

    public void unlock(long userId) {
        jdbc.update("UPDATE identity.app_user SET failed_logins = 0, locked_until = NULL WHERE user_id = :id", Map.of("id", userId));
    }

    /** Sets the user's role (role key) in one hospital; {@code role == null} removes the assignment. */
    public void assign(long userId, int hospitalId, String role) {
        MapSqlParameterSource p = new MapSqlParameterSource("u", userId).addValue("h", hospitalId);
        if (role == null) {
            jdbc.update("DELETE FROM identity.app_user_hospital WHERE user_id = :u AND hospital_id = :h", p);
        } else {
            jdbc.update("""
                    INSERT INTO identity.app_user_hospital (user_id, hospital_id, role) VALUES (:u, :h, :r)
                    ON CONFLICT (user_id, hospital_id) DO UPDATE SET role = EXCLUDED.role""", p.addValue("r", role));
        }
    }

    /** All users, or only those with a role in one of {@code hospitalIds} (null = all). */
    public List<UserSummary> users(Collection<Integer> hospitalIds) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = "";
        if (hospitalIds != null) {
            if (hospitalIds.isEmpty()) {
                return List.of();
            }
            where = " WHERE u.user_id IN (SELECT user_id FROM identity.app_user_hospital WHERE hospital_id IN (:h))";
            p.addValue("h", hospitalIds);
        }
        Map<Long, UserSummary> byId = new LinkedHashMap<>();
        jdbc.query("""
                SELECT u.user_id, u.username, u.full_name, u.super_admin, u.active, u.locked_until, u.last_login_at
                FROM identity.app_user u""" + where + " ORDER BY u.username", p, rs -> {
            byId.put(rs.getLong(1), new UserSummary(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                    rs.getBoolean(5), time(rs.getTimestamp(6)), time(rs.getTimestamp(7)), new ArrayList<>()));
        });
        if (!byId.isEmpty()) {
            jdbc.query("""
                    SELECT uh.user_id, h.hospital_id, h.hospital_code, h.name, r.role_key, r.hospital_id, r.label, r.permissions, r.builtin
                    FROM identity.app_user_hospital uh JOIN identity.hospital h ON h.hospital_id = uh.hospital_id
                    JOIN identity.role r ON r.role_key = uh.role
                    WHERE uh.user_id IN (:ids) ORDER BY h.hospital_code""", Map.of("ids", byId.keySet()), rs -> {
                byId.get(rs.getLong(1)).assignments().add(
                        new Assignment(rs.getInt(2), rs.getString(3), rs.getString(4), new RoleDef(rs.getString(5),
                                rs.getObject(6) == null ? null : rs.getInt(6), rs.getString(7), permissions(rs.getArray(8)), rs.getBoolean(9))));
            });
        }
        return List.copyOf(byId.values());
    }

    public Optional<UserSummary> user(long userId) {
        return users(null).stream().filter(u -> u.userId() == userId).findFirst();
    }

    // ---------------------------------------------------------------- hospitals

    public List<Hospital> hospitals() {
        return jdbc.query("SELECT hospital_id, hospital_code, name, city, active, created_at FROM identity.hospital ORDER BY hospital_code",
                Map.of(), (rs, i) -> new Hospital(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), time(rs.getTimestamp(6))));
    }

    public Optional<Hospital> hospital(int hospitalId) {
        return hospitals().stream().filter(h -> h.hospitalId() == hospitalId).findFirst();
    }

    /** Active super administrators who would have no active hospital left without this one. */
    public List<String> superAdminsOnlyIn(int hospitalId) {
        return jdbc.queryForList("""
                SELECT u.username FROM identity.app_user u
                WHERE u.super_admin AND u.active AND NOT EXISTS (
                    SELECT 1 FROM identity.app_user_hospital uh JOIN identity.hospital h ON h.hospital_id = uh.hospital_id
                    WHERE uh.user_id = u.user_id AND h.active AND h.hospital_id <> :id)""", Map.of("id", hospitalId), String.class);
    }

    public boolean hospitalCodeExists(String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM identity.hospital WHERE hospital_code = :c)", Map.of("c", code), Boolean.class));
    }

    public int insertHospital(String code, String name, String city) {
        Integer id = jdbc.queryForObject("""
                INSERT INTO identity.hospital (hospital_code, name, city) VALUES (:c, :n, :city) RETURNING hospital_id""",
                new MapSqlParameterSource("c", code).addValue("n", name).addValue("city", city), Integer.class);
        return id == null ? 0 : id;
    }

    public void updateHospital(int hospitalId, String name, String city, boolean active) {
        jdbc.update("""
                UPDATE identity.hospital SET name = :n, city = :city, active = :a, updated_at = now() WHERE hospital_id = :id""",
                new MapSqlParameterSource("id", hospitalId).addValue("n", name).addValue("city", city).addValue("a", active));
    }

    // ---------------------------------------------------------------- audit

    public void audit(Long userId, String username, Integer hospitalId, String action, String detail, String clientAddress) {
        jdbc.update("""
                INSERT INTO identity.audit_log (user_id, username, hospital_id, action, detail, client_address)
                VALUES (:u, :name, :h, :a, :d, :ip)""",
                new MapSqlParameterSource("u", userId).addValue("name", username).addValue("h", hospitalId)
                        .addValue("a", action).addValue("d", detail == null ? null : truncate(detail, 500))
                        .addValue("ip", clientAddress));
    }

    /** Latest entries, newest first; {@code hospitalIds == null} means every entry (super administrator). */
    public List<AuditEntry> auditLog(Collection<Integer> hospitalIds, String action, String username, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("limit", limit);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (hospitalIds != null) {
            if (hospitalIds.isEmpty()) {
                return List.of();
            }
            where.append(" AND a.hospital_id IN (:h)");
            p.addValue("h", hospitalIds);
        }
        if (action != null && !action.isBlank()) {
            where.append(" AND a.action = :action");
            p.addValue("action", action);
        }
        if (username != null && !username.isBlank()) {
            where.append(" AND a.username = :uname");
            p.addValue("uname", username.trim().toLowerCase());
        }
        return jdbc.query("""
                SELECT a.audit_id, a.at, a.username, h.hospital_code, a.action, a.detail, a.client_address
                FROM identity.audit_log a LEFT JOIN identity.hospital h ON h.hospital_id = a.hospital_id""" + where
                + " ORDER BY a.at DESC, a.audit_id DESC LIMIT :limit", p,
                (rs, i) -> new AuditEntry(rs.getLong(1), time(rs.getTimestamp(2)), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)));
    }

    private static LocalDateTime time(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
