package com.example.demo.service;

import com.example.demo.model.User;
import com.example.demo.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the one configured bootstrap administrator before public traffic is served. */
@Component
public class AdminAccountBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final String username;
    private final String password;

    public AdminAccountBootstrap(UserRepository users, PasswordEncoder passwords,
            @Value("${auth.bootstrap-admin.username:}") String username,
            @Value("${auth.bootstrap-admin.password:}") String password) {
        this.users = users;
        this.passwords = passwords;
        this.username = username.trim();
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (username.isBlank() || password.isBlank()) return;
        User admin = users.findByUsername(username)
                .orElseGet(() -> new User(username, passwords.encode(password)));
        admin.promoteToAdmin();
        users.save(admin);
    }
}
