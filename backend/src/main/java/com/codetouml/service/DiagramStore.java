package com.codetouml.service;

import com.codetouml.dto.DiagramDetail;
import com.codetouml.dto.DiagramSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Stores and retrieves saved diagrams, scoped to the owning user. */
@Repository
public class DiagramStore {

    private final JdbcTemplate jdbc;

    public DiagramStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String userSub, String email, String title, String sourceType, String source) {
        // Plain insert — no generated-key fetch (H2's getGeneratedKeys returns the whole row, which
        // makes KeyHolder.getKey() throw even though the row inserts fine). The client doesn't need the id.
        jdbc.update(
                "INSERT INTO diagrams(user_sub, user_email, title, source_type, source) VALUES (?,?,?,?,?)",
                userSub, email, title, sourceType, source);
    }

    public List<DiagramSummary> listFor(String userSub) {
        return jdbc.query(
                "SELECT id, title, source_type, created_at FROM diagrams WHERE user_sub = ? ORDER BY created_at DESC",
                (rs, i) -> new DiagramSummary(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("source_type"),
                        rs.getTimestamp("created_at").toInstant().toString()),
                userSub);
    }

    /** The diagram if it belongs to the user, otherwise {@code null}. */
    public DiagramDetail get(long id, String userSub) {
        return jdbc.query(
                "SELECT id, title, source_type, source FROM diagrams WHERE id = ? AND user_sub = ?",
                rs -> rs.next()
                        ? new DiagramDetail(rs.getLong("id"), rs.getString("title"),
                                rs.getString("source_type"), rs.getString("source"))
                        : null,
                id, userSub);
    }

    /** Returns the number of rows deleted (0 if it wasn't the user's diagram). */
    public int delete(long id, String userSub) {
        return jdbc.update("DELETE FROM diagrams WHERE id = ? AND user_sub = ?", id, userSub);
    }
}
