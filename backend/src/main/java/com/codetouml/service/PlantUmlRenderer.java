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
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
