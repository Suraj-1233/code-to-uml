package com.codetouml.controller;

import com.codetouml.dto.GenerateRequest;
import com.codetouml.service.UmlService;
import com.github.javaparser.ParseProblemException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/uml")
@CrossOrigin(origins = "*")
public class UmlController {

    private final UmlService service;

    public UmlController(UmlService service) {
        this.service = service;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody GenerateRequest request) {
        if (request == null || request.code() == null || request.code().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No code was provided."));
        }
        try {
            return ResponseEntity.ok(service.generate(request.code()));
        } catch (ParseProblemException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Could not parse the Java code. " + firstLine(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to generate diagram: " + firstLine(e.getMessage())));
        }
    }

    private String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }
}
