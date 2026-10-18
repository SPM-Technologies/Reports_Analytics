package com.hospital.patientreg.identity.service;

import com.hospital.patientreg.identity.dao.IdentityDao;
import com.hospital.patientreg.identity.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * At every start writes the built-in roles into identity.role; on the very first start (no users at all)
 * also creates the super administrator, assigned to hospital 1.
 */
@Component
public class IdentityBootstrap implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(IdentityBootstrap.class);

    private final IdentityDao dao;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    public IdentityBootstrap(IdentityDao dao, PasswordEncoder encoder,
                             @Value("${identity.bootstrap.username}") String username,
                             @Value("${identity.bootstrap.password}") String password) {
        this.dao = dao;
        this.encoder = encoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // built-in roles: their permissions come from the code (identity.model.Role)
        for (Role r : Role.values()) {
            dao.saveRole(r.name(), null, r.getLabel(), r.permissions(), true);
        }
        if (dao.countUsers() > 0) {
            return;
        }
        long id = dao.insertUser(username, "Administrator", encoder.encode(password), true);
        dao.assign(id, 1, Role.ADMIN.name());
        log.warn("Created the first user '{}' (super administrator). Change its password after signing in.", username);
    }
}
