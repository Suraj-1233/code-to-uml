package com.codetouml.model;

import java.util.List;

/** The full model extracted from a chunk of Java source. */
public record ParseResult(List<UmlClass> classes, List<UmlRelation> relations, List<DetectedPattern> patterns) {
}
