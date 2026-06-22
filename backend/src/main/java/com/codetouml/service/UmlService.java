package com.codetouml.service;

import com.codetouml.dto.GenerateResponse;
import com.codetouml.model.ParseResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** Orchestrates parse -> PlantUML -> SVG, for both pasted code and whole GitHub repos. */
@Service
public class UmlService {

    private final JavaCodeParser parser;
    private final PlantUmlGenerator generator;
    private final PlantUmlRenderer renderer;
    private final GitHubRepoService repoService;

    public UmlService(JavaCodeParser parser, PlantUmlGenerator generator, PlantUmlRenderer renderer,
                      GitHubRepoService repoService) {
        this.parser = parser;
        this.generator = generator;
        this.renderer = renderer;
        this.repoService = repoService;
    }

    public GenerateResponse generate(String code) {
        ParseResult result = parser.parse(code);
        List<String> warnings = new ArrayList<>();
        if (result.classes().isEmpty()) {
            warnings.add("No classes, interfaces or enums were found in the provided code.");
        }
        return render(result, warnings);
    }

    public GenerateResponse generateFromRepo(String repoUrl) {
        GitHubRepoService.RepoSources src = repoService.fetchJavaSources(repoUrl);
        ParseResult result = parser.parseRepo(src.javaSources());
        List<String> warnings = new ArrayList<>();
        warnings.add("Parsed " + src.javaSources().size() + " Java file(s) from "
                + src.owner() + "/" + src.repo()
                + (src.skipped() > 0 ? " — " + src.skipped() + " skipped to stay within limits" : "") + ".");
        if (result.classes().isEmpty()) {
            warnings.add("No types were found in the repository's Java files.");
        }
        return render(result, warnings);
    }

    private GenerateResponse render(ParseResult result, List<String> warnings) {
        String plantuml = generator.generate(result);
        try {
            String svg = renderer.renderSvg(plantuml);
            return new GenerateResponse(plantuml, svg, warnings, result.patterns());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render the diagram", e);
        }
    }
}
