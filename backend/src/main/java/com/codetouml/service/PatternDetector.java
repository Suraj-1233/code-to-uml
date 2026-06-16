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
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Infers Gang-of-Four design patterns from the structure of the code.
 *
 * <p>This is deliberately heuristic — it looks for the tell-tale shape of each pattern
 * (e.g. a private constructor + static self-instance for Singleton). It favours precision
 * over recall, so it would rather miss a pattern than report a wrong one.
 *
 * <p>Several patterns share an identical structure (Strategy / State / Command / Bridge are all
 * "an abstraction + implementations + a holder"). Those can only be told apart by intent, so we
 * lean on method/class naming and resolve them by precedence, claiming each abstraction once so it
 * isn't reported under two names.
 */
@Service
public class PatternDetector {

    private static final Set<String> COLLECTION_TYPES = Set.of(
            "List", "ArrayList", "LinkedList", "Set", "HashSet", "TreeSet", "LinkedHashSet",
            "Collection", "Queue", "Deque", "Iterable", "Map", "HashMap", "TreeMap", "LinkedHashMap"
    );
    private static final Set<String> MAP_TYPES = Set.of(
            "Map", "HashMap", "TreeMap", "LinkedHashMap", "ConcurrentHashMap", "Hashtable", "WeakHashMap"
    );

    public List<DetectedPattern> detect(CompilationUnit cu, List<UmlClass> classes) {
        List<ClassOrInterfaceDeclaration> types = cu.findAll(ClassOrInterfaceDeclaration.class);

        Set<String> known = new HashSet<>();
        Map<String, ClassOrInterfaceDeclaration> byName = new HashMap<>();
        for (ClassOrInterfaceDeclaration t : types) {
            known.add(t.getNameAsString());
            byName.putIfAbsent(t.getNameAsString(), t);
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

        // Abstract factories are detected first so we don't also tag their concrete
        // implementations as plain Factory Methods.
        Set<String> abstractFactoryTypes = new HashSet<>();
        for (ClassOrInterfaceDeclaration t : types) {
            if (abstractFactory(t, abstractOrInterface) != null) {
                abstractFactoryTypes.add(t.getNameAsString());
            }
        }

        List<DetectedPattern> found = new ArrayList<>();

        // 1) Per-class detectors that don't share structure with the behavioural family.
        for (ClassOrInterfaceDeclaration t : types) {
            addIfPresent(found, singleton(t));
            addIfPresent(found, builder(t));
            addIfPresent(found, prototype(t));
            addIfPresent(found, adapter(t, known, abstractOrInterface));
            addIfPresent(found, templateMethod(t));
            addIfPresent(found, iterator(t));
            addIfPresent(found, facade(t, known));
            addIfPresent(found, flyweight(t, known));
            addIfPresent(found, memento(t, known));
            addIfPresent(found, mediator(t));
            addIfPresent(found, observer(t, abstractOrInterface));
            addIfPresent(found, bridge(t, subtypes, abstractOrInterface));

            DetectedPattern af = abstractFactory(t, abstractOrInterface);
            if (af != null) {
                found.add(af);
            } else if (!implementsAny(t, abstractFactoryTypes)) {
                addIfPresent(found, factoryMethod(t, subtypes, abstractOrInterface));
            }
        }

        // 2) Abstractions already explained above must not be re-reported as Strategy/State/etc.
        Set<String> claimed = new HashSet<>();
        for (DetectedPattern p : found) {
            switch (p.name()) {
                case "Observer", "Factory Method", "Bridge" -> {
                    if (p.participants().size() > 1) {
                        claimed.add(p.participants().get(1));
                    }
                }
                case "Abstract Factory" -> {
                    if (p.participants().size() > 1) {
                        claimed.addAll(p.participants().subList(1, p.participants().size()));
                    }
                }
                default -> { }
            }
        }

        // 3) The behavioural "abstraction + implementations" family — one label per abstraction.
        Set<String> behavioural = new HashSet<>();
        found.addAll(behaviouralHierarchy(types, byName, subtypes, abstractOrInterface, claimed, behavioural));

        // 4) The structural "implements X and wraps an X" family (Composite/Decorator/Proxy/Chain).
        Set<String> off = new HashSet<>(behavioural);
        off.addAll(claimed);
        for (ClassOrInterfaceDeclaration t : types) {
            addIfPresent(found, wrapping(t, off));
        }

        return found;
    }

    // --- creational ----------------------------------------------------------

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

    private DetectedPattern prototype(ClassOrInterfaceDeclaration t) {
        if (t.isInterface()) {
            return null;
        }
        String name = t.getNameAsString();
        boolean cloneable = t.getImplementedTypes().stream()
                .anyMatch(i -> i.getNameAsString().equals("Cloneable"));
        MethodDeclaration copy = t.getMethods().stream().filter(m -> {
            String n = m.getNameAsString().toLowerCase();
            return n.equals("clone") || n.equals("copy") || n.equals("deepcopy");
        }).findFirst().orElse(null);
        boolean copyReturnsSelf = copy != null
                && (refersTo(copy.getType().asString(), name) || copy.getType().asString().equals("Object"));

        if (cloneable || copyReturnsSelf) {
            return new DetectedPattern("Prototype", List.of(name),
                    "Implements Cloneable / exposes clone()/copy() that returns a copy of itself.");
        }
        return null;
    }

    private DetectedPattern abstractFactory(ClassOrInterfaceDeclaration t, Set<String> abstractOrInterface) {
        boolean isAbstraction = t.isInterface() || hasModifier(t.getModifiers(), Modifier.Keyword.ABSTRACT);
        if (!isAbstraction) {
            return null;
        }
        String name = t.getNameAsString();
        Set<String> products = new LinkedHashSet<>();
        for (MethodDeclaration m : t.getMethods()) {
            String ret = simpleType(m.getType().asString());
            if (abstractOrInterface.contains(ret) && !ret.equals(name)) {
                products.add(ret);
            }
        }
        if (products.size() >= 2) {
            List<String> participants = new ArrayList<>();
            participants.add(name);
            participants.addAll(products);
            return new DetectedPattern("Abstract Factory", participants,
                    name + " declares factory methods for a family of products: " + String.join(", ", products) + ".");
        }
        return null;
    }

    private DetectedPattern factoryMethod(ClassOrInterfaceDeclaration t, Map<String, Set<String>> subtypes,
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
                return new DetectedPattern("Factory Method", List.of(name, returns),
                        m.getNameAsString() + "() returns " + returns + " and instantiates concrete subtypes.");
            }
        }
        return null;
    }

    // --- structural ----------------------------------------------------------

    private DetectedPattern adapter(ClassOrInterfaceDeclaration t, Set<String> known,
                                    Set<String> abstractOrInterface) {
        if (t.isInterface() || hasModifier(t.getModifiers(), Modifier.Keyword.ABSTRACT)) {
            return null;
        }
        Set<String> targets = new HashSet<>();
        t.getImplementedTypes().forEach(i -> targets.add(i.getNameAsString()));
        if (targets.isEmpty()) {
            return null;
        }
        String adaptee = null;
        String adapteeField = null;
        for (FieldDeclaration f : t.getFields()) {
            for (VariableDeclarator v : f.getVariables()) {
                String simple = simpleType(v.getType().asString());
                if (known.contains(simple) && !abstractOrInterface.contains(simple)
                        && !targets.contains(simple) && !isCollection(v.getType().asString())) {
                    adaptee = simple;
                    adapteeField = v.getNameAsString();
                }
            }
        }
        if (adaptee == null) {
            return null;
        }
        String name = t.getNameAsString();
        boolean named = name.toLowerCase().endsWith("adapter");
        final String fieldName = adapteeField;
        boolean delegates = t.findAll(MethodCallExpr.class).stream()
                .anyMatch(c -> c.getScope()
                        .map(s -> s.toString().equals(fieldName) || s.toString().equals("this." + fieldName))
                        .orElse(false));
        if (named || delegates) {
            return new DetectedPattern("Adapter", List.of(name, adaptee),
                    name + " implements " + String.join("/", targets) + " and delegates to " + adaptee + ".");
        }
        return null;
    }

    private DetectedPattern bridge(ClassOrInterfaceDeclaration t, Map<String, Set<String>> subtypes,
                                   Set<String> abstractOrInterface) {
        boolean isAbstraction = t.isInterface() || hasModifier(t.getModifiers(), Modifier.Keyword.ABSTRACT);
        if (!isAbstraction) {
            return null;
        }
        String name = t.getNameAsString();
        if (subtypes.getOrDefault(name, Set.of()).isEmpty()) {
            return null; // the abstraction must itself be refined by subclasses
        }
        Set<String> ownSupers = supertypes(t);
        String implementor = null;
        for (FieldDeclaration f : t.getFields()) {
            for (VariableDeclarator v : f.getVariables()) {
                for (String tok : tokens(v.getType().asString())) {
                    if (abstractOrInterface.contains(tok) && !tok.equals(name) && !ownSupers.contains(tok)
                            && !subtypes.getOrDefault(tok, Set.of()).isEmpty()) {
                        implementor = tok;
                    }
                }
            }
        }
        if (implementor != null) {
            return new DetectedPattern("Bridge", List.of(name, implementor),
                    name + " (with subclasses) delegates to a separate " + implementor + " implementor hierarchy.");
        }
        return null;
    }

    private DetectedPattern facade(ClassOrInterfaceDeclaration t, Set<String> known) {
        if (t.isInterface() || hasModifier(t.getModifiers(), Modifier.Keyword.ABSTRACT)) {
            return null;
        }
        String name = t.getNameAsString();
        Set<String> subsystems = new LinkedHashSet<>();
        for (FieldDeclaration f : t.getFields()) {
            for (VariableDeclarator v : f.getVariables()) {
                String s = simpleType(v.getType().asString());
                if (known.contains(s) && !s.equals(name) && !isCollection(v.getType().asString())) {
                    subsystems.add(s);
                }
            }
        }
        boolean named = name.toLowerCase().endsWith("facade");
        boolean plain = t.getImplementedTypes().isEmpty() && t.getExtendedTypes().isEmpty();
        if ((named && subsystems.size() >= 2) || (plain && subsystems.size() >= 3)) {
            List<String> participants = new ArrayList<>();
            participants.add(name);
            participants.addAll(subsystems);
            return new DetectedPattern("Facade", participants,
                    name + " wraps " + subsystems.size() + " subsystems behind a simpler API.");
        }
        return null;
    }

    private DetectedPattern flyweight(ClassOrInterfaceDeclaration t, Set<String> known) {
        if (t.isInterface()) {
            return null;
        }
        String name = t.getNameAsString();
        boolean named = name.toLowerCase().contains("flyweight");
        String cached = null;
        boolean hasMap = false;
        for (FieldDeclaration f : t.getFields()) {
            for (VariableDeclarator v : f.getVariables()) {
                String type = v.getType().asString();
                if (MAP_TYPES.contains(simpleType(type))) {
                    hasMap = true;
                    for (String tok : tokens(type)) {
                        if (known.contains(tok)) {
                            cached = tok;
                        }
                    }
                }
            }
        }
        if (!hasMap) {
            return null;
        }
        boolean reuses = t.findAll(MethodCallExpr.class).stream()
                .map(MethodCallExpr::getNameAsString)
                .anyMatch(n -> n.equals("containsKey") || n.equals("computeIfAbsent")
                        || n.equals("putIfAbsent") || n.equals("getOrDefault"));
        if (named || (cached != null && reuses)) {
            String pooled = cached != null ? cached : "instances";
            return new DetectedPattern("Flyweight", cached != null ? List.of(name, cached) : List.of(name),
                    name + " caches and shares " + pooled + " through a map instead of re-creating them.");
        }
        return null;
    }

    /** Composite / Decorator / Proxy / Chain of Responsibility — a class that wraps its own abstraction. */
    private DetectedPattern wrapping(ClassOrInterfaceDeclaration t, Set<String> excluded) {
        if (t.isInterface()) {
            return null;
        }
        String name = t.getNameAsString();
        Set<String> own = supertypes(t);
        own.add(name);
        if (own.stream().anyMatch(excluded::contains)) {
            return null; // it's a participant in a behavioural pattern, not a wrapper
        }
        boolean collection = false;
        boolean single = false;
        String held = null;
        for (FieldDeclaration f : t.getFields()) {
            if (hasModifier(f.getModifiers(), Modifier.Keyword.STATIC)) {
                continue; // a static self-instance is a Singleton/registry, not a wrapped instance
            }
            for (VariableDeclarator v : f.getVariables()) {
                String fieldType = v.getType().asString();
                List<String> toks = tokens(fieldType);
                String match = own.stream().filter(toks::contains).findFirst().orElse(null);
                if (match == null) {
                    continue;
                }
                held = match;
                if (isCollection(fieldType)) {
                    collection = true;
                } else {
                    single = true;
                }
            }
        }
        if (collection) {
            return new DetectedPattern("Composite", List.of(name, held),
                    name + " holds a collection of " + held + " — a part-whole tree.");
        }
        if (single) {
            boolean chain = fieldNamed(t, "next", "successor", "nexthandler")
                    || methodPrefix(t, "setnext", "setsuccessor") || methodExact(t, "sethandler");
            if (chain || (methodPrefix(t, "handle") && name.toLowerCase().endsWith("handler"))) {
                return new DetectedPattern("Chain of Responsibility", List.of(name, held),
                        name + " passes the request to the next handler in a chain.");
            }
            if (name.toLowerCase().endsWith("proxy")) {
                return new DetectedPattern("Proxy", List.of(name, held),
                        name + " stands in for a single " + held + ", controlling access to it.");
            }
            return new DetectedPattern("Decorator", List.of(name, held),
                    name + " implements " + held + " and wraps one to add behaviour.");
        }
        return null;
    }

    // --- behavioural (per-class) --------------------------------------------

    private DetectedPattern templateMethod(ClassOrInterfaceDeclaration t) {
        if (t.isInterface() || !hasModifier(t.getModifiers(), Modifier.Keyword.ABSTRACT)) {
            return null;
        }
        Set<String> abstractSteps = new HashSet<>();
        for (MethodDeclaration m : t.getMethods()) {
            if (hasModifier(m.getModifiers(), Modifier.Keyword.ABSTRACT) || m.getBody().isEmpty()) {
                abstractSteps.add(m.getNameAsString());
            }
        }
        if (abstractSteps.isEmpty()) {
            return null;
        }
        for (MethodDeclaration m : t.getMethods()) {
            if (m.getBody().isEmpty()) {
                continue;
            }
            Set<String> calledSteps = new HashSet<>();
            for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
                boolean selfCall = call.getScope().isEmpty()
                        || call.getScope().map(s -> s.toString().equals("this")).orElse(false);
                if (selfCall && abstractSteps.contains(call.getNameAsString())) {
                    calledSteps.add(call.getNameAsString());
                }
            }
            if (calledSteps.size() >= 2) {
                return new DetectedPattern("Template Method", List.of(t.getNameAsString()),
                        m.getNameAsString() + "() fixes the algorithm skeleton and calls "
                                + calledSteps.size() + " abstract steps left to subclasses.");
            }
        }
        return null;
    }

    private DetectedPattern iterator(ClassOrInterfaceDeclaration t) {
        if (t.isInterface()) {
            return null;
        }
        String name = t.getNameAsString();
        boolean implementsStd = t.getImplementedTypes().stream()
                .anyMatch(i -> i.getNameAsString().equals("Iterator") || i.getNameAsString().equals("Iterable"));
        boolean hasNextNext = methodExact(t, "hasnext") && methodExact(t, "next");
        if (implementsStd || hasNextNext) {
            return new DetectedPattern("Iterator", List.of(name),
                    "Provides sequential access via hasNext()/next() (or implements Iterator/Iterable).");
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

    private DetectedPattern memento(ClassOrInterfaceDeclaration t, Set<String> known) {
        if (t.isInterface()) {
            return null;
        }
        String name = t.getNameAsString();
        MethodDeclaration saver = t.getMethods().stream().filter(m -> {
            String n = m.getNameAsString().toLowerCase();
            return n.startsWith("save") || n.startsWith("creatememento") || n.startsWith("tomemento")
                    || n.startsWith("snapshot");
        }).findFirst().orElse(null);
        MethodDeclaration restorer = t.getMethods().stream().filter(m -> {
            String n = m.getNameAsString().toLowerCase();
            return n.startsWith("restore") || n.startsWith("setmemento") || n.startsWith("frommemento");
        }).findFirst().orElse(null);
        if (saver != null && restorer != null) {
            String memento = simpleType(saver.getType().asString());
            List<String> participants = known.contains(memento) ? List.of(name, memento) : List.of(name);
            return new DetectedPattern("Memento", participants,
                    name + " captures and restores its state via " + saver.getNameAsString()
                            + "()/" + restorer.getNameAsString() + "().");
        }
        return null;
    }

    private DetectedPattern mediator(ClassOrInterfaceDeclaration t) {
        if (t.isInterface()) {
            return null;
        }
        String name = t.getNameAsString();
        if (!name.toLowerCase().endsWith("mediator")) {
            return null; // mediator has no reliable structural signal beyond naming
        }
        return new DetectedPattern("Mediator", List.of(name),
                name + " centralises communication so colleagues don't refer to each other directly.");
    }

    /**
     * The behavioural family that all share the shape "abstraction + 2 implementations + a holder":
     * Visitor, Command, Interpreter, State, Strategy. Resolved by precedence, one label per abstraction.
     */
    private List<DetectedPattern> behaviouralHierarchy(List<ClassOrInterfaceDeclaration> types,
                                                       Map<String, ClassOrInterfaceDeclaration> byName,
                                                       Map<String, Set<String>> subtypes,
                                                       Set<String> abstractOrInterface,
                                                       Set<String> claimed, Set<String> behaviouralOut) {
        List<DetectedPattern> result = new ArrayList<>();
        for (String iface : abstractOrInterface) {
            if (claimed.contains(iface)) {
                continue;
            }
            ClassOrInterfaceDeclaration decl = byName.get(iface);
            if (decl == null) {
                continue;
            }
            Set<String> impls = subtypes.getOrDefault(iface, Set.of());

            // Visitor — visit(...) operations + elements that accept(visitor). Naming is strong here.
            if (methodPrefix(decl, "visit") && anyAcceptsVisitor(types, iface)) {
                result.add(new DetectedPattern("Visitor", withImpls(iface, impls),
                        iface + " declares visit(...) operations; elements call accept(" + iface + ")."));
                behaviouralOut.add(iface);
                continue;
            }
            if (impls.size() < 2) {
                continue;
            }
            // Command — an action object with execute()/undo().
            if (methodExact(decl, "execute", "undo")) {
                result.add(new DetectedPattern("Command", withImpls(iface, impls),
                        iface + " wraps an action as an object (execute()/undo()) with "
                                + impls.size() + " commands."));
                behaviouralOut.add(iface);
                continue;
            }
            // Interpreter — interpret()/evaluate() expressions.
            if (methodPrefix(decl, "interpret") || methodExact(decl, "evaluate", "eval")) {
                result.add(new DetectedPattern("Interpreter", withImpls(iface, impls),
                        iface + " defines interpret()/evaluate() over terminal & non-terminal expressions."));
                behaviouralOut.add(iface);
                continue;
            }
            // State — same shape as Strategy, told apart by *State naming + a context that swaps it.
            DetectedPattern st = state(iface, impls, types);
            if (st != null) {
                result.add(st);
                behaviouralOut.add(iface);
                continue;
            }
            // Strategy — a context that holds one swappable implementation.
            String ctx = contextHolding(iface, impls, types);
            if (ctx != null) {
                result.add(new DetectedPattern("Strategy", withImpls(iface, impls),
                        ctx + " holds a " + iface + " and swaps between " + impls.size() + " implementations."));
                behaviouralOut.add(iface);
            }
        }
        return result;
    }

    private DetectedPattern state(String stateType, Set<String> impls, List<ClassOrInterfaceDeclaration> types) {
        boolean named = stateType.toLowerCase().endsWith("state")
                || impls.stream().filter(s -> s.toLowerCase().endsWith("state")).count() * 2 >= impls.size();
        if (!named) {
            return null;
        }
        String context = null;
        for (ClassOrInterfaceDeclaration t : types) {
            String name = t.getNameAsString();
            if (name.equals(stateType) || impls.contains(name)) {
                continue;
            }
            boolean holds = singleFieldOfType(t, stateType);
            boolean mutates = t.getMethods().stream().anyMatch(m ->
                    m.getNameAsString().toLowerCase().startsWith("set")
                            && m.getParameters().stream().anyMatch(p -> refersTo(p.getType().asString(), stateType)));
            if (holds && mutates) {
                context = name;
                break;
            }
        }
        if (context == null) {
            return null;
        }
        List<String> participants = new ArrayList<>();
        participants.add(context);
        participants.add(stateType);
        participants.addAll(impls);
        return new DetectedPattern("State", distinct(participants),
                context + " holds a " + stateType + " and switches between " + impls.size()
                        + " concrete states at runtime.");
    }

    private String contextHolding(String iface, Set<String> impls, List<ClassOrInterfaceDeclaration> types) {
        for (ClassOrInterfaceDeclaration t : types) {
            String name = t.getNameAsString();
            if (name.equals(iface) || impls.contains(name)) {
                continue;
            }
            if (singleFieldOfType(t, iface)) {
                return name;
            }
        }
        return null;
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

    private boolean methodPrefix(ClassOrInterfaceDeclaration t, String... prefixes) {
        return t.getMethods().stream().anyMatch(m -> startsWithAny(m.getNameAsString(), prefixes));
    }

    private boolean methodExact(ClassOrInterfaceDeclaration t, String... names) {
        for (MethodDeclaration m : t.getMethods()) {
            String lower = m.getNameAsString().toLowerCase();
            for (String n : names) {
                if (lower.equals(n.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean fieldNamed(ClassOrInterfaceDeclaration t, String... names) {
        for (FieldDeclaration f : t.getFields()) {
            for (VariableDeclarator v : f.getVariables()) {
                String lower = v.getNameAsString().toLowerCase();
                for (String n : names) {
                    if (lower.equals(n.toLowerCase())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean singleFieldOfType(ClassOrInterfaceDeclaration t, String type) {
        return t.getFields().stream().anyMatch(f -> f.getVariables().stream().anyMatch(v ->
                !isCollection(v.getType().asString()) && refersTo(v.getType().asString(), type)));
    }

    private boolean implementsAny(ClassOrInterfaceDeclaration t, Set<String> names) {
        return t.getImplementedTypes().stream().anyMatch(i -> names.contains(i.getNameAsString()));
    }

    private Set<String> supertypes(ClassOrInterfaceDeclaration t) {
        Set<String> result = new LinkedHashSet<>();
        t.getExtendedTypes().forEach(e -> result.add(e.getNameAsString()));
        t.getImplementedTypes().forEach(i -> result.add(i.getNameAsString()));
        return result;
    }

    private boolean anyAcceptsVisitor(List<ClassOrInterfaceDeclaration> types, String visitorType) {
        for (ClassOrInterfaceDeclaration t : types) {
            for (MethodDeclaration m : t.getMethods()) {
                if (m.getNameAsString().equals("accept")
                        && m.getParameters().stream().anyMatch(p -> refersTo(p.getType().asString(), visitorType))) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> withImpls(String head, Set<String> impls) {
        List<String> list = new ArrayList<>();
        list.add(head);
        list.addAll(impls);
        return list;
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
