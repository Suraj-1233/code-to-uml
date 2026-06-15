package com.codetouml.service;

import com.codetouml.dto.GenerateResponse;
import com.codetouml.model.ParseResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** Orchestrates parse -> PlantUML -> SVG. */
@Service
public class UmlService {

    private final JavaCodeParser parser;
    private final PlantUmlGenerator generator;
    private final PlantUmlRenderer renderer;

    public UmlService(JavaCodeParser parser, PlantUmlGenerator generator, PlantUmlRenderer renderer) {
        this.parser = parser;
        this.generator = generator;
        this.renderer = renderer;
    }

    public GenerateResponse generate(String code) {
        ParseResult result = parser.parse(code);

        List<String> warnings = new ArrayList<>();
        if (result.classes().isEmpty()) {
            warnings.add("No classes, interfaces or enums were found in the provided code.");
        }

        String plantuml = generator.generate(result);
        try {
            String svg = renderer.renderSvg(plantuml);
            return new GenerateResponse(plantuml, svg, warnings, result.patterns());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render the diagram", e);
        }
    }
}
