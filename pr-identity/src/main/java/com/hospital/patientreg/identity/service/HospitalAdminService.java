package com.hospital.patientreg.identity.service;

import com.hospital.patientreg.identity.dao.IdentityDao;
import com.hospital.patientreg.identity.model.Hospital;
import com.hospital.patientreg.identity.model.Role;
import com.hospital.patientreg.identity.security.AccessVersion;
import com.hospital.patientreg.shared.tenant.AuditTrail;
import com.hospital.patientreg.shared.tenant.HospitalCreatedEvent;
import com.hospital.patientreg.shared.tenant.Tenant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Hospitals: only super administrators may add or change them. Codes never change (they prefix registration numbers). */
@Service
public class HospitalAdminService {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9]{1,5}");

    private final IdentityDao dao;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final AccessVersion accessVersion;

    public HospitalAdminService(IdentityDao dao, AuditTrail audit, ApplicationEventPublisher events, AccessVersion accessVersion) {
        this.accessVersion = accessVersion;
        this.dao = dao;
        this.audit = audit;
        this.events = events;
    }

    public List<Hospital> list(Tenant t) {
        requireSuperAdmin(t);
        return dao.hospitals();
    }

    public Hospital get(Tenant t, int hospitalId) {
        requireSuperAdmin(t);
        return dao.hospital(hospitalId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hospital not found"));
    }

    /** Adds a hospital; its creator becomes its administrator, and other modules set up their data (event). */
    @Transactional
    public int create(Tenant t, String code, String name, String city) {
        requireSuperAdmin(t);
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(c).matches()) {
            throw new InvalidInputException("Code: 2-6 capital letters or digits, starting with a letter (e.g. CITY, H002)");
        }
        if (dao.hospitalCodeExists(c)) {
            throw new InvalidInputException("The code " + c + " is already used");
        }
        int id = dao.insertHospital(c, name(name), city(city));
        dao.assign(t.userId(), id, Role.ADMIN.name());
        events.publishEvent(new HospitalCreatedEvent(id));
        audit.record("HOSPITAL_CREATED", id, c + " " + name.trim());
        return id;
    }

    @Transactional
    public void update(Tenant t, int hospitalId, String name, String city, boolean active) {
        Hospital h = get(t, hospitalId);
        if (!active && hospitalId == t.activeHospitalId()) {
            throw new InvalidInputException("You cannot deactivate the hospital you are working in");
        }
        if (!active) {
            List<String> stranded = dao.superAdminsOnlyIn(hospitalId);
            if (!stranded.isEmpty()) {
                throw new InvalidInputException("Give super administrator(s) " + stranded + " another hospital first: "
                        + "without an active hospital they could no longer sign in");
            }
        }
        dao.updateHospital(hospitalId, name(name), city(city), active);
        if (!name(name).equals(h.name())) {
            events.publishEvent(new com.hospital.patientreg.shared.tenant.HospitalRenamedEvent(hospitalId));
        }
        accessVersion.changed();   // users of a deactivated hospital lose it on their next request
        audit.record("HOSPITAL_UPDATED", hospitalId, h.code() + (h.active() != active ? (active ? " activated" : " deactivated") : ""));
    }

    private static void requireSuperAdmin(Tenant t) {
        if (!t.superAdmin()) {
            throw Tenant.forbidden("Only super administrators can manage hospitals");
        }
    }

    private static String name(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() < 2 || n.length() > 120) {
            throw new InvalidInputException("Name: 2-120 characters");
        }
        return n;
    }

    private static String city(String city) {
        String c = city == null ? "" : city.trim();
        if (c.length() > 60) {
            throw new InvalidInputException("City: at most 60 characters");
        }
        return c.isEmpty() ? null : c;
    }
}
