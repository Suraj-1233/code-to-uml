package com.codetouml.model;

import java.util.List;

/**
 * A single method/operation of a type.
 *
 * @param visibility UML symbol: + public, - private, # protected, ~ package-private
 * @param name       method name
 * @param returnType declared return type
 * @param params     formatted parameters, e.g. "String name"
 * @param isStatic   whether the method is static
 * @param isAbstract whether the method is abstract
 */
public record UmlMethod(String visibility, String name, String returnType,
                        List<String> params, boolean isStatic, boolean isAbstract) {
}
