package com.codetouml.service;

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Renders PlantUML source to an inline SVG string. */
@Service
public class PlantUmlRenderer {

    public String renderSvg(String source) throws IOException {
        SourceStringReader reader = new SourceStringReader(source);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            reader.outputImage(out, new FileFormatOption(FileFormat.SVG));
            String svg = out.toString(StandardCharsets.UTF_8);
            // PlantUML renders internal failures (e.g. Smetana crashing on a huge graph) as an
            // error image embedding a stack trace. Detect that and surface a clean message instead.
            if (svg.contains("An error has occured") || svg.contains("Smetana is not finished")) {
                throw new DiagramRenderException(
                        "The diagram is too large or complex for the layout engine to render. "
                                + "Try a smaller repository, or scope it to a subfolder "
                                + "(e.g. github.com/owner/repo/tree/main/src/main/java/...).");
            }
            return svg;
        }
    }
}
