package com.codetouml.controller;

import com.codetouml.dto.DiagramDetail;
import com.codetouml.dto.DiagramSummary;
import com.codetouml.dto.SaveDiagramRequest;
import com.codetouml.service.DiagramStore;
import com.codetouml.service.GoogleAuthService;
import com.codetouml.service.UserStore;
import com.codetouml.service.GoogleAuthService.GoogleUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Per-user saved diagrams. Every endpoint requires a valid Google Sign-In ID token. */
@RestController
@RequestMapping("/api/diagrams")
@CrossOrigin(origins = "*")
public class DiagramController {

    private static final Set<String> SOURCE_TYPES = Set.of("code", "repo");

    private final GoogleAuthService auth;
    private final DiagramStore store;
    private final UserStore users;

    public DiagramController(GoogleAuthService auth, DiagramStore store, UserStore users) {
        this.auth = auth;
        this.store = store;
        this.users = users;
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestHeader(value = "Authorization", required = false) String authz,
                                  @RequestBody SaveDiagramRequest req) {
        GoogleUser user = auth.requireUser(authz);
        users.record(user.sub(), user.email(), user.name());
        if (req == null || req.source() == null || req.source().isBlank()
                || req.sourceType() == null || !SOURCE_TYPES.contains(req.sourceType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nothing valid to save."));
        }
        String title = (req.title() == null || req.title().isBlank()) ? "Untitled" : req.title().trim();
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }
        store.save(user.sub(), user.email(), title, req.sourceType(), req.source());
        return ResponseEntity.ok(Map.of("saved", true));
    }

    @GetMapping
    public List<DiagramSummary> list(@RequestHeader(value = "Authorization", required = false) String authz) {
        GoogleUser user = auth.requireUser(authz);
        // The frontend lists diagrams right after sign-in, so this is where we capture a sign-up.
        users.record(user.sub(), user.email(), user.name());
        return store.listFor(user.sub());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@RequestHeader(value = "Authorization", required = false) String authz,
                                 @PathVariable long id) {
        DiagramDetail detail = store.get(id, auth.requireUser(authz).sub());
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@RequestHeader(value = "Authorization", required = false) String authz,
                                    @PathVariable long id) {
        store.delete(id, auth.requireUser(authz).sub());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(GoogleAuthService.AuthException.class)
    public ResponseEntity<?> onAuthFailure(GoogleAuthService.AuthException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }
}
