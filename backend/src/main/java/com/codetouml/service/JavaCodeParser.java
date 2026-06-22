package com.codetouml.service;

import com.codetouml.model.DetectedPattern;
import com.codetouml.model.ParseResult;
import com.codetouml.model.UmlClass;
import com.codetouml.model.UmlField;
import com.codetouml.model.UmlMethod;
import com.codetouml.model.UmlRelation;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns Java source into a {@link ParseResult} (types + classified relationships) using JavaParser.
 */
@Service
public class JavaCodeParser {

    private static final Set<String> COLLECTION_TYPES = Set.of(
            "List", "ArrayList", "LinkedList", "Set", "HashSet", "TreeSet", "LinkedHashSet",
            "Collection", "Queue", "Deque", "Iterable", "Map", "HashMap", "TreeMap", "LinkedHashMap"
    );

    private final PatternDetector patternDetector;

    public JavaCodeParser(PatternDetector patternDetector) {
        this.patternDetector = patternDetector;
    }

    /** Parses a single pasted snippet (strict — surfaces parse errors to the user). */
    public ParseResult parse(String code) {
        com.github.javaparser.ParseResult<CompilationUnit> parsed = newParser().parse(code);
        CompilationUnit cu = parsed.getResult()
                .orElseThrow(() -> new ParseProblemException(parsed.getProblems()));
        return analyze(List.of(cu));
    }

    /** Parses many Java sources together (e.g. a whole repo), skipping files that don't parse. */
    public ParseResult parseRepo(List<String> sources) {
        JavaParser parser = newParser();
        List<CompilationUnit> cus = new ArrayList<>();
        for (String src : sources) {
            try {
                parser.parse(src).getResult().ifPresent(cus::add);
            } catch (Exception ignored) {
                // skip a file that fails to parse rather than failing the whole repo
            }
        }
        return analyze(cus);
    }

    private JavaParser newParser() {
        // Java 17 (LTS) language level so modern syntax (records, sealed types, text blocks, …) parses.
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
    }

    /** Builds the model + relations + patterns over one or more parsed compilation units. */
    private ParseResult analyze(List<CompilationUnit> cus) {
        List<UmlClass> classes = new ArrayList<>();

        for (CompilationUnit cu : cus) {
            // Classes, abstract classes and interfaces (includes nested types).
            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String kind = decl.isInterface()
                        ? "interface"
                        : (has(decl.getModifiers(), Modifier.Keyword.ABSTRACT) ? "abstract" : "class");
                boolean isInterface = decl.isInterface();
                classes.add(new UmlClass(
                        decl.getNameAsString(),
                        kind,
                        fieldsOf(decl.getFields(), isInterface),
                        methodsOf(decl.getMethods(), isInterface),
                        names(decl.getExtendedTypes()),
                        names(decl.getImplementedTypes())
                ));
            }

            // Enums.
            for (EnumDeclaration decl : cu.findAll(EnumDeclaration.class)) {
                List<UmlField> fields = new ArrayList<>();
                for (EnumConstantDeclaration constant : decl.getEntries()) {
                    fields.add(new UmlField("+", constant.getNameAsString(), "", true));
                }
                fields.addAll(fieldsOf(decl.getFields(), false));
                classes.add(new UmlClass(
                        decl.getNameAsString(),
                        "enum",
                        fields,
                        methodsOf(decl.getMethods(), false),
                        new ArrayList<>(),
                        names(decl.getImplementedTypes())
                ));
            }

            // Records (Java 16+). The header components are the canonical fields.
            for (RecordDeclaration decl : cu.findAll(RecordDeclaration.class)) {
                List<UmlField> fields = new ArrayList<>();
                for (Parameter component : decl.getParameters()) {
                    fields.add(new UmlField("+", component.getNameAsString(), component.getType().asString(), false));
                }
                fields.addAll(fieldsOf(decl.getFields(), false));
                classes.add(new UmlClass(
                        decl.getNameAsString(),
                        "record",
                        fields,
                        methodsOf(decl.getMethods(), false),
                        new ArrayList<>(),
                        names(decl.getImplementedTypes())
                ));
            }
        }

        Set<String> known = new HashSet<>();
        for (UmlClass c : classes) {
            known.add(c.name());
        }

        List<DetectedPattern> patterns = patternDetector.detect(cus, classes);
        return new ParseResult(classes, buildRelations(cus, known), patterns);
    }

    private List<UmlField> fieldsOf(List<FieldDeclaration> declarations, boolean ownerIsInterface) {
        List<UmlField> fields = new ArrayList<>();
        for (FieldDeclaration f : declarations) {
            String visibility = visibility(f.getModifiers(), ownerIsInterface);
            boolean isStatic = has(f.getModifiers(), Modifier.Keyword.STATIC);
            for (VariableDeclarator v : f.getVariables()) {
                fields.add(new UmlField(visibility, v.getNameAsString(), v.getType().asString(), isStatic));
            }
        }
        return fields;
    }

    private List<UmlMethod> methodsOf(List<MethodDeclaration> declarations, boolean ownerIsInterface) {
        List<UmlMethod> methods = new ArrayList<>();
        for (MethodDeclaration m : declarations) {
            List<String> params = new ArrayList<>();
            for (Parameter p : m.getParameters()) {
                params.add(p.getType().asString() + " " + p.getNameAsString());
            }
            methods.add(new UmlMethod(
                    visibility(m.getModifiers(), ownerIsInterface),
                    m.getNameAsString(),
                    m.getType().asString(),
                    params,
                    has(m.getModifiers(), Modifier.Keyword.STATIC),
                    has(m.getModifiers(), Modifier.Keyword.ABSTRACT)
            ));
        }
        return methods;
    }

    /**
     * Classifies relationships between the known types. For each ordered pair of types we keep the
     * single strongest relationship, ranked: inheritance/realization &gt; composition &gt;
     * aggregation &gt; association &gt; dependency.
     */
    private List<UmlRelation> buildRelations(List<CompilationUnit> cus, Set<String> known) {
        List<RelCandidate> candidates = new ArrayList<>();

        for (CompilationUnit cu : cus) {
        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String owner = decl.getNameAsString();

            for (ClassOrInterfaceType t : decl.getExtendedTypes()) {
                if (known.contains(t.getNameAsString())) {
                    candidates.add(new RelCandidate(owner, t.getNameAsString(), "extends", null, null, 5));
                }
            }
            for (ClassOrInterfaceType t : decl.getImplementedTypes()) {
                if (known.contains(t.getNameAsString())) {
                    candidates.add(new RelCandidate(owner, t.getNameAsString(), "implements", null, null, 5));
                }
            }

            AssignInfo assigns = scanAssignments(decl);
            Set<String> fieldTargets = new HashSet<>();

            // Field-based relationships: composition / aggregation / association.
            for (FieldDeclaration f : decl.getFields()) {
                for (VariableDeclarator v : f.getVariables()) {
                    String vType = v.getType().asString();
                    boolean collection = isCollection(vType);
                    boolean newInitialised = v.getInitializer().map(Expression::isObjectCreationExpr).orElse(false);
                    String fieldName = v.getNameAsString();

                    for (String target : referencedKnown(vType, known, owner)) {
                        fieldTargets.add(target);
                        String type = classifyField(collection, newInitialised, fieldName, assigns);
                        String multiplicity = collection ? "*" : null;
                        candidates.add(new RelCandidate(owner, target, type, fieldName, multiplicity, priorityOf(type)));
                    }
                }
            }

            // Dependencies: types used only in method/constructor signatures (not stored as fields).
            Set<String> used = new LinkedHashSet<>();
            for (MethodDeclaration m : decl.getMethods()) {
                used.addAll(referencedKnown(m.getType().asString(), known, owner));
                for (Parameter p : m.getParameters()) {
                    used.addAll(referencedKnown(p.getType().asString(), known, owner));
                }
            }
            for (ConstructorDeclaration ctor : decl.getConstructors()) {
                for (Parameter p : ctor.getParameters()) {
                    used.addAll(referencedKnown(p.getType().asString(), known, owner));
                }
            }
            for (String target : used) {
                if (!fieldTargets.contains(target)) {
                    candidates.add(new RelCandidate(owner, target, "dependency", null, null, 1));
                }
            }
        }

        // Records: components (and body fields) are references the record holds; methods give dependencies.
        for (RecordDeclaration decl : cu.findAll(RecordDeclaration.class)) {
            String owner = decl.getNameAsString();
            for (ClassOrInterfaceType t : decl.getImplementedTypes()) {
                if (known.contains(t.getNameAsString())) {
                    candidates.add(new RelCandidate(owner, t.getNameAsString(), "implements", null, null, 5));
                }
            }
            Set<String> fieldTargets = new HashSet<>();
            for (Parameter component : decl.getParameters()) {
                String vType = component.getType().asString();
                boolean collection = isCollection(vType);
                for (String target : referencedKnown(vType, known, owner)) {
                    fieldTargets.add(target);
                    String type = collection ? "aggregation" : "association";
                    candidates.add(new RelCandidate(owner, target, type, component.getNameAsString(),
                            collection ? "*" : null, priorityOf(type)));
                }
            }
            for (FieldDeclaration f : decl.getFields()) {
                for (VariableDeclarator v : f.getVariables()) {
                    String vType = v.getType().asString();
                    boolean collection = isCollection(vType);
                    for (String target : referencedKnown(vType, known, owner)) {
                        fieldTargets.add(target);
                        String type = collection ? "aggregation" : "association";
                        candidates.add(new RelCandidate(owner, target, type, v.getNameAsString(),
                                collection ? "*" : null, priorityOf(type)));
                    }
                }
            }
            Set<String> used = new LinkedHashSet<>();
            for (MethodDeclaration m : decl.getMethods()) {
                used.addAll(referencedKnown(m.getType().asString(), known, owner));
                for (Parameter p : m.getParameters()) {
                    used.addAll(referencedKnown(p.getType().asString(), known, owner));
                }
            }
            for (String target : used) {
                if (!fieldTargets.contains(target)) {
                    candidates.add(new RelCandidate(owner, target, "dependency", null, null, 1));
                }
            }
        }
        } // end for (cu : cus)

        // Keep the strongest candidate per ordered (from -> to) pair.
        Map<String, RelCandidate> best = new LinkedHashMap<>();
        for (RelCandidate rc : candidates) {
            String key = rc.from() + "->" + rc.to();
            RelCandidate current = best.get(key);
            if (current == null || rc.priority() > current.priority()) {
                best.put(key, rc);
            }
        }

        List<UmlRelation> relations = new ArrayList<>();
        for (RelCandidate rc : best.values()) {
            relations.add(new UmlRelation(rc.from(), rc.to(), rc.type(), rc.label(), rc.multiplicity()));
        }
        return relations;
    }

    private String classifyField(boolean collection, boolean newInitialised, String fieldName, AssignInfo assigns) {
        // A non-collection field the class instantiates itself => it owns it => composition.
        if (!collection && (newInitialised || assigns.assignedNew().contains(fieldName))) {
            return "composition";
        }
        // A collection of the type, or a field injected from outside => aggregation (shared lifecycle).
        if (collection || assigns.assignedFromVar().contains(fieldName)) {
            return "aggregation";
        }
        return "association";
    }

    private int priorityOf(String type) {
        return switch (type) {
            case "composition" -> 4;
            case "aggregation" -> 3;
            case "association" -> 2;
            default -> 1;
        };
    }

    /** Records which fields are assigned {@code new ...} vs assigned from a variable/parameter. */
    private AssignInfo scanAssignments(ClassOrInterfaceDeclaration decl) {
        Set<String> assignedNew = new HashSet<>();
        Set<String> assignedFromVar = new HashSet<>();
        for (AssignExpr assign : decl.findAll(AssignExpr.class)) {
            String target = assignTargetName(assign.getTarget());
            if (target == null) {
                continue;
            }
            if (assign.getValue().isObjectCreationExpr()) {
                assignedNew.add(target);
            } else if (assign.getValue().isNameExpr()) {
                assignedFromVar.add(target);
            }
        }
        return new AssignInfo(assignedNew, assignedFromVar);
    }

    private String assignTargetName(Expression target) {
        if (target.isFieldAccessExpr()) {
            return target.asFieldAccessExpr().getNameAsString();
        }
        if (target.isNameExpr()) {
            return target.asNameExpr().getNameAsString();
        }
        return null;
    }

    /** Known type names referenced anywhere in a type string (handles List&lt;Foo&gt;, Map&lt;K,Foo&gt;, Foo[]). */
    private Set<String> referencedKnown(String typeStr, Set<String> known, String owner) {
        Set<String> result = new LinkedHashSet<>();
        if (typeStr == null || typeStr.isEmpty()) {
            return result;
        }
        for (String token : typeStr.split("[^A-Za-z0-9_$]+")) {
            if (!token.isEmpty() && !token.equals(owner) && known.contains(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private boolean isCollection(String typeStr) {
        if (typeStr.contains("[]")) {
            return true;
        }
        String head = typeStr.replaceAll("<.*", "").trim();
        String simple = head.contains(".") ? head.substring(head.lastIndexOf('.') + 1) : head;
        return COLLECTION_TYPES.contains(simple);
    }

    private List<String> names(NodeList<ClassOrInterfaceType> types) {
        List<String> result = new ArrayList<>();
        types.forEach(t -> result.add(t.getNameAsString()));
        return result;
    }

    private String visibility(NodeList<Modifier> modifiers, boolean ownerIsInterface) {
        if (has(modifiers, Modifier.Keyword.PUBLIC)) {
            return "+";
        }
        if (has(modifiers, Modifier.Keyword.PRIVATE)) {
            return "-";
        }
        if (has(modifiers, Modifier.Keyword.PROTECTED)) {
            return "#";
        }
        // Interface members are implicitly public when no modifier is present.
        return ownerIsInterface ? "+" : "~";
    }

    private boolean has(NodeList<Modifier> modifiers, Modifier.Keyword keyword) {
        return modifiers.stream().anyMatch(m -> m.getKeyword() == keyword);
    }

    private record RelCandidate(String from, String to, String type, String label, String multiplicity, int priority) {
    }

    private record AssignInfo(Set<String> assignedNew, Set<String> assignedFromVar) {
    }
}
