package com.example.fraud.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 演示环境的可指派账号目录。只暴露用户名与角色，不暴露密码或认证内部字段。
 */
@Service
public class AssigneeDirectory {
    private static final List<Account> ACCOUNTS = Collections.unmodifiableList(Arrays.asList(
            new Account("demo_reviewer", "REVIEWER"),
            new Account("demo_admin", "ADMIN")));

    public Optional<String> roleOf(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return ACCOUNTS.stream().filter(account -> account.username.equals(username))
                .map(account -> account.role).findFirst();
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Account account : ACCOUNTS) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("username", account.username);
            item.put("role", account.role);
            result.add(item);
        }
        return result;
    }

    private static class Account {
        private final String username;
        private final String role;

        private Account(String username, String role) {
            this.username = username;
            this.role = role;
        }
    }
}
