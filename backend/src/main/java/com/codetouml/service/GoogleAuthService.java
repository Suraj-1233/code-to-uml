package com.codetouml.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.GeneralSecurityException;
import java.io.IOException;
import java.util.Collections;

/** Verifies Google Sign-In ID tokens and extracts the signed-in user. */
@Service
public class GoogleAuthService {

    private final GoogleIdTokenVerifier verifier;

    public GoogleAuthService(@Value("${google.client-id}") String clientId) {
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new GsonFactory())
                .setAudience(Collections.singletonList(clientId))
                .build();
    }

    public record GoogleUser(String sub, String email, String name) {}

    /** Thrown when the request isn't from a validly signed-in user (maps to HTTP 401). */
    public static class AuthException extends RuntimeException {
        public AuthException(String message) {
            super(message);
        }
    }

    /** Verifies the ID token from an "Authorization: Bearer &lt;id_token&gt;" header value. */
    public GoogleUser requireUser(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new AuthException("Please sign in with Google.");
        }
        String idToken = authorizationHeader.substring("Bearer ".length()).trim();
        try {
            GoogleIdToken token = verifier.verify(idToken);
            if (token == null) {
                throw new AuthException("Your sign-in has expired — please sign in again.");
            }
            GoogleIdToken.Payload p = token.getPayload();
            return new GoogleUser(p.getSubject(), p.getEmail(), (String) p.get("name"));
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            // IllegalArgumentException covers a malformed / non-JWT token string.
            throw new AuthException("Your sign-in could not be verified — please sign in again.");
        }
    }
}
