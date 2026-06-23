package com.codetouml.controller;

import com.codetouml.service.UserStore;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public, unauthenticated stats — currently just the sign-up count shown in the UI. */
@RestController
@RequestMapping("/api/stats")
@CrossOrigin(origins = "*")
public class StatsController {

    private final UserStore users;

    public StatsController(UserStore users) {
        this.users = users;
    }

    @GetMapping
    public Map<String, Long> stats() {
        return Map.of("users", users.count());
    }
}
