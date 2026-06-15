package com.codetouml.model;

/**
 * A single field/attribute of a type.
 *
 * @param visibility UML symbol: + public, - private, # protected, ~ package-private
 * @param name       field name
 * @param type       declared type (may be empty for enum constants)
 * @param isStatic   whether the field is static
 */
public record UmlField(String visibility, String name, String type, boolean isStatic) {
}
