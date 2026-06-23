package com.codetouml.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Tracks signed-in users (one row each) so we can show how many have signed up. */
@Repository
public class UserStore {

    private final JdbcTemplate jdbc;

    public UserStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Upsert a user on sign-in — first_seen is set once, on the initial insert. */
    public void record(String userSub, String email, String name) {
        jdbc.update("MERGE INTO users(user_sub, email, name) KEY(user_sub) VALUES (?,?,?)",
                userSub, email, name);
    }

    public long count() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return c == null ? 0L : c;
    }
}
