package com.codetouml.model;

import java.util.List;

/**
 * A type extracted from the source code.
 *
 * @param name            type name
 * @param kind            one of: class, abstract, interface, enum, annotation
 * @param fields          declared fields (and enum constants)
 * @param methods         declared methods
 * @param extendsTypes    names of super types from an {@code extends} clause
 * @param implementsTypes names of interfaces from an {@code implements} clause
 */
public record UmlClass(String name, String kind, List<UmlField> fields, List<UmlMethod> methods,
                       List<String> extendsTypes, List<String> implementsTypes) {
}
