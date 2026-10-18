package com.hospital.patientreg.identity.service;

import com.hospital.patientreg.identity.dao.IdentityDao;
import java.util.Locale;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Loads local users (identity.app_user) for Spring Security's username/password login. */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final IdentityDao dao;

    public AppUserDetailsService(IdentityDao dao) {
        this.dao = dao;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        String key = normalise(username);
        return dao.loadUser(key).orElseThrow(() -> new UsernameNotFoundException("No such user"));
    }

    public static String normalise(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
