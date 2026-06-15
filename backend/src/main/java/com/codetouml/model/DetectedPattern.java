package com.codetouml.model;

import java.util.List;

/**
 * A design pattern inferred from the code's structure (best-effort heuristic).
 *
 * @param name         pattern name, e.g. "Singleton"
 * @param participants the types involved (the first is the primary participant)
 * @param note         a short, human-readable reason it was flagged
 */
public record DetectedPattern(String name, List<String> participants, String note) {
}
