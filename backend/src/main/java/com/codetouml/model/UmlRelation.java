package com.codetouml.model;

/**
 * A relationship between two types.
 *
 * @param from         the owning type
 * @param to           the referenced type
 * @param type         one of: extends, implements, composition, aggregation, association, dependency
 * @param label        optional edge label (e.g. the field name)
 * @param multiplicity optional multiplicity at the target end (e.g. "*"), or null
 */
public record UmlRelation(String from, String to, String type, String label, String multiplicity) {
}
