package com.hospital.patientreg.identity.service;

import com.hospital.patientreg.identity.dao.IdentityDao;
import com.hospital.patientreg.identity.model.Hospital;
import com.hospital.patientreg.identity.model.Role;
import com.hospital.patientreg.shared.tenant.HospitalCreatedEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Demo set-up for a shared POC (only with {@code identity.demo.enabled=true}, in Docker {@code DEMO_DATA=true}):
 * hospitals H002 City Hospital (Pune) and H003 Sunrise Hospital (Nagpur) and the demo users of the project guide,
 * all with password {@code identity.demo.password}. Idempotent: existing hospitals and users are never changed.
 * Patients are added separately (load-data.bat / the seed profile). Never enable this for real use.
 */
@Component
public class DemoDataBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DemoDataBootstrap.class);

    private record DemoUser(String username, String name, Role role, List<String> hospitals) {
    }

    private static final List<DemoUser> USERS = List.of(
            new DemoUser("h001.admin", "H001 Administrator", Role.ADMIN, List.of("H001")),
            new DemoUser("h001.reg", "H001 Registration Desk", Role.REGISTRATION, List.of("H001")),
            new DemoUser("h001.reports", "H001 Reports", Role.REPORTS, List.of("H001")),
            new DemoUser("h002.admin", "H002 Administrator", Role.ADMIN, List.of("H002")),
            new DemoUser("h002.reg", "H002 Registration Desk", Role.REGISTRATION, List.of("H002")),
            new DemoUser("h002.reports", "H002 Reports", Role.REPORTS, List.of("H002")),
            new DemoUser("h003.reg", "H003 Registration Desk", Role.REGISTRATION, List.of("H003")),
            new DemoUser("group.reports", "Group Reports", Role.REPORTS, List.of("H001", "H002", "H003")));

    private final IdentityDao dao;
    private final PasswordEncoder encoder;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final String password;
    private final String adminUsername;
    private final AtomicBoolean done = new AtomicBoolean();

    public DemoDataBootstrap(IdentityDao dao, PasswordEncoder encoder, ApplicationEventPublisher events,
                             org.springframework.transaction.PlatformTransactionManager txManager,
                             @Value("${identity.demo.enabled:false}") boolean enabled,
                             @Value("${identity.demo.password:Demo@1234}") String password,
                             @Value("${identity.bootstrap.username}") String adminUsername) {
        this.dao = dao;
        this.encoder = encoder;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.enabled = enabled;
        this.password = password;
        this.adminUsername = adminUsername;
    }

    /** After the whole application has started, so the listeners for new hospitals (departments) are active. */
    @EventListener(ContextRefreshedEvent.class)
    public void onStarted() {
        if (!enabled || !done.compareAndSet(false, true)) {
            return;
        }
        tx.executeWithoutResult(status -> {
            hospital("H002", "City Hospital", "Pune");
            hospital("H003", "Sunrise Hospital", "Nagpur");
            String hash = encoder.encode(password);
            for (DemoUser u : USERS) {
                if (dao.usernameExists(u.username())) {
                    continue;
                }
                long id = dao.insertUser(u.username(), u.name(), hash, false);
                for (String code : u.hospitals()) {
                    dao.hospitals().stream().filter(h -> h.code().equals(code)).findFirst()
                            .ifPresent(h -> dao.assign(id, h.hospitalId(), u.role().name()));
                }
                log.info("Demo user {} created", u.username());
            }
        });
    }

    private void hospital(String code, String name, String city) {
        if (dao.hospitalCodeExists(code)) {
            return;
        }
        int id = dao.insertHospital(code, name, city);
        events.publishEvent(new HospitalCreatedEvent(id));   // registration seeds its departments
        // the super administrator works in every demo hospital
        dao.loadUser(adminUsername).ifPresent(admin -> dao.hospitals().stream().filter(h -> h.hospitalId() == id)
                .map(Hospital::hospitalId).forEach(h -> dao.assign(admin.userId(), h, Role.ADMIN.name())));
        log.info("Demo hospital {} {} created", code, name);
    }
}
