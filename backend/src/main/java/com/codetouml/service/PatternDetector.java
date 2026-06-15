package com.codetouml.service;

import com.codetouml.model.DetectedPattern;
import com.codetouml.model.UmlClass;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Infers common GoF design patterns from the structure of the code.
 *
 * <p>This is deliberately heuristic — it looks for the tell-tale shape of each pattern
 * (e.g. a private constructor + static self-instance for Singleton). It favours precision
 * over recall, so it would rather miss a pattern than report a wrong one, but false
 * positives/negatives are still possible.
 */
@Service
public class PatternDetector {

    private static final Set<String> COLLECTION_TYPES = Set.of(
            "List", "ArrayList", "LinkedList", "Set", "HashSet", "TreeSet", "LinkedHashSet",
            "Collection", "Queue", "Deque", "Iterable", "Map", "HashMap", "TreeMap", "LinkedHashMap"
    );

    public List<DetectedPattern> detect(CompilationUnit cu, List<UmlClass> classes) {
        List<ClassOrInterfaceDeclaration> types = cu.findAll(ClassOrInterfaceDeclaration.class);

        Set<String> known = new HashSet<>();
        for (ClassOrInterfaceDeclaration t : types) {
            known.add(t.getNameAsString());
        }

        // supertype -> direct subtypes, and the set of abstract/interface types
        Map<String, Set<String>> subtypes = new HashMap<>();
        Set<String> abstractOrInterface = new HashSet<>();
        for (ClassOrInterfaceDeclaration t : types) {
            String name = t.getNameAsString();
            if (t.isInterface() || hasModifier(t.getModifiers(), Modifier.Keyword.ABSTRACT)) {
                abstractOrInterface.add(name);
            }
            t.getExtendedTypes().forEach(s -> registerSubtype(subtypes, s.getNameAsString(), name, known));
            t.getImplementedTypes().forEach(s -> registerSubtype(subtypes, s.getNameAsString(), name, known));
        }

        List<DetectedPattern> found = new ArrayList<>();
        for (ClassOrInterfaceDeclaration t : types) {
            addIfPresent(found, singleton(t));
            addIfPresent(found, builder(t));
            addIfPresent(found, factory(t, subtypes, abstractOrInterface));
            addIfPresent(found, observer(t, abstractOrInterface));
        }
        found.addAll(strategy(types, subtypes, abstractOrInterface));
        return found;
    }

    // --- individual detectors ------------------------------------------------

    private DetectedPattern singleton(ClassOrInterfaceDeclaration t) {
        if (t.isInterface()) {
            return null;
        }
        String name = t.getNameAsString();
        List<ConstructorDeclaration> ctors = t.getConstructors();
        boolean privateCtor = !ctors.isEmpty()
                && ctors.stream().allMatch(c -> hasModifier(c.getModifiers(), Modifier.Keyword.PRIVATE));
        boolean staticSelfField = t.getFields().stream().anyMatch(f ->
                hasModifier(f.getModifiers(), Modifier.Keyword.STATIC)
                        && f.getVariables().stream().anyMatch(v -> refersTo(v.getType().asString(), name)));
        boolean staticAccessor = t.getMethods().stream().anyMatch(m ->
                hasModifier(m.getModifiers(), Modifier.Keyword.STATIC) && refersTo(m.getType().asString(), name));

        if (privateCtor && staticSelfField && staticAccessor) {
            return new DetectedPattern("Singleton", List.of(name),
                    "Private constructor, a static self-instance and a static accessor method.");
        }
        return null;
    }

    private DetectedPattern builder(ClassOrInterfaceDeclaration t) {
        if (t.isInterface()) {
            return null;
        }
        String name = t.getNameAsString();
        boolean fluent = t.getMethods().stream().anyMatch(m -> refersTo(m.getType().asString(), name));
        MethodDeclaration build = t.getMethods().stream()
                .filter(m -> m.getNameAsString().toLowerCase().startsWith("build"))
                .findFirst().orElse(null);
        boolean namedBuilder = name.toLowerCase().endsWith("builder");

        if (fluent && (build != null || namedBuilder)) {
            List<String> participants = new ArrayList<>();
            participants.add(name);
            String note;
            if (build != null && !"void".equals(build.getType().asString())) {
                participants.add(simpleType(build.getType().asString()));
                note = "Fluent methods return " + name + "; build() produces " + build.getType().asString() + ".";
            } else {
                note = "Fluent methods returning " + name + " for step-by-step construction.";
            }
            return new DetectedPattern("Builder", distinct(participants), note);
        }
        return null;
    }

    private DetectedPattern factory(ClassOrInterfaceDeclaration t, Map<String, Set<String>> subtypes,
                                    Set<String> abstractOrInterface) {
        String name = t.getNameAsString();
        for (MethodDeclaration m : t.getMethods()) {
            String returns = simpleType(m.getType().asString());
            if (!abstractOrInterface.contains(returns)) {
                continue;
            }
            Set<String> subs = subtypes.getOrDefault(returns, Set.of());
            boolean createsSubtype = m.findAll(ObjectCreationExpr.class).stream()
                    .map(o -> o.getType().getNameAsString())
                    .anyMatch(subs::contains);
            if (createsSubtype) {
                return new DetectedPattern("Factory", List.of(name, returns),
                        m.getNameAsString() + "() returns " + returns + " and instantiates concrete subtypes.");
            }
        }
        return null;
    }

    private DetectedPattern observer(ClassOrInterfaceDeclaration t, Set<String> abstractOrInterface) {
        if (t.isInterface()) {
            return null;
        }
        String observerType = null;
        for (FieldDeclaration f : t.getFields()) {
            for (VariableDeclarator v : f.getVariables()) {
                String type = v.getType().asString();
                if (isCollection(type)) {
                    for (String token : tokens(type)) {
                        if (abstractOrInterface.contains(token)) {
                            observerType = token;
                        }
                    }
                }
            }
        }
        if (observerType == null) {
            return null;
        }
        boolean notifies = t.getMethods().stream()
                .anyMatch(m -> startsWithAny(m.getNameAsString(), "notify", "update", "publish", "fire", "emit"));
        boolean registers = t.getMethods().stream()
                .anyMatch(m -> startsWithAny(m.getNameAsString(), "add", "register", "attach", "subscribe", "observe"));
        if (notifies && registers) {
            return new DetectedPattern("Observer", List.of(t.getNameAsString(), observerType),
                    "Keeps a collection of " + observerType + " and exposes register + notify methods.");
        }
        return null;
    }

    private List<DetectedPattern> strategy(List<ClassOrInterfaceDeclaration> types,
                                           Map<String, Set<String>> subtypes, Set<String> abstractOrInterface) {
        List<DetectedPattern> result = new ArrayList<>();
        for (String iface : abstractOrInterface) {
            Set<String> impls = subtypes.getOrDefault(iface, Set.of());
            if (impls.size() < 2) {
                continue;
            }
            String context = null;
            for (ClassOrInterfaceDeclaration t : types) {
                String name = t.getNameAsString();
                if (name.equals(iface) || impls.contains(name)) {
                    continue;
                }
                boolean holdsStrategy = t.getFields().stream()
                        .anyMatch(f -> f.getVariables().stream().anyMatch(v -> refersTo(v.getType().asString(), iface)));
                if (holdsStrategy) {
                    context = name;
                    break;
                }
            }
            if (context != null) {
                List<String> participants = new ArrayList<>();
                participants.add(iface);
                participants.addAll(impls);
                result.add(new DetectedPattern("Strategy", participants,
                        context + " holds a " + iface + " and swaps between " + impls.size() + " implementations."));
            }
        }
        return result;
    }

    // --- helpers -------------------------------------------------------------

    private void registerSubtype(Map<String, Set<String>> subtypes, String supertype, String subtype, Set<String> known) {
        if (known.contains(supertype)) {
            subtypes.computeIfAbsent(supertype, k -> new HashSet<>()).add(subtype);
        }
    }

    private void addIfPresent(List<DetectedPattern> list, DetectedPattern pattern) {
        if (pattern != null) {
            list.add(pattern);
        }
    }

    private boolean hasModifier(NodeList<Modifier> modifiers, Modifier.Keyword keyword) {
        return modifiers.stream().anyMatch(m -> m.getKeyword() == keyword);
    }

    private boolean refersTo(String typeStr, String name) {
        return tokens(typeStr).contains(name);
    }

    private List<String> tokens(String typeStr) {
        List<String> result = new ArrayList<>();
        if (typeStr == null) {
            return result;
        }
        for (String token : typeStr.split("[^A-Za-z0-9_$]+")) {
            if (!token.isEmpty()) {
                result.add(token);
            }
        }
        return result;
    }

    private String simpleType(String typeStr) {
        String head = typeStr.replaceAll("<.*", "").replace("[]", "").trim();
        return head.contains(".") ? head.substring(head.lastIndexOf('.') + 1) : head;
    }

    private boolean isCollection(String typeStr) {
        if (typeStr.contains("[]")) {
            return true;
        }
        String head = typeStr.replaceAll("<.*", "").trim();
        String simple = head.contains(".") ? head.substring(head.lastIndexOf('.') + 1) : head;
        return COLLECTION_TYPES.contains(simple);
    }

    private boolean startsWithAny(String name, String... prefixes) {
        String lower = name.toLowerCase();
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private List<String> distinct(List<String> input) {
        List<String> out = new ArrayList<>();
        for (String s : input) {
            if (!out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }
}
