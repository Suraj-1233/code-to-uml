package com.codetouml.dto;

import com.codetouml.model.DetectedPattern;

import java.util.List;

/**
 * Result returned to the client.
 *
 * @param plantuml the generated PlantUML source (so the user can copy/version it)
 * @param svg      the rendered class diagram as inline SVG
 * @param warnings non-fatal notes (e.g. "no types found")
 * @param patterns design patterns inferred from the code (best-effort)
 */
public record GenerateResponse(String plantuml, String svg, List<String> warnings, List<DetectedPattern> patterns) {
}
