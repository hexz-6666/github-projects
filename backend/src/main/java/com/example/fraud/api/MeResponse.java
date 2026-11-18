package com.example.fraud.api;

import java.util.List;

public class MeResponse {
    private final String username;
    private final List<String> roles;
    private final boolean authenticated;

    public MeResponse(String username, List<String> roles, boolean authenticated) {
        this.username = username;
        this.roles = roles;
        this.authenticated = authenticated;
    }

    public String getUsername() { return username; }
    public List<String> getRoles() { return roles; }
    public boolean isAuthenticated() { return authenticated; }
}
