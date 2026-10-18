package com.hospital.patientreg.identity.service;

import java.util.HashMap;
import java.util.Map;

/** User details entered on the administration screen. {@code roles}: hospital id -> role name ("" = none). */
public class UserForm {

    private String username;
    private String fullName;
    private String password;
    private boolean active = true;
    private boolean superAdmin;
    private Map<Integer, String> roles = new HashMap<>();

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isSuperAdmin() { return superAdmin; }
    public void setSuperAdmin(boolean superAdmin) { this.superAdmin = superAdmin; }
    public Map<Integer, String> getRoles() { return roles; }
    public void setRoles(Map<Integer, String> roles) { this.roles = roles; }
}
