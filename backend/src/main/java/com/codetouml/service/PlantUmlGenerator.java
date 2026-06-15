package com.codetouml.service;

import com.codetouml.model.DetectedPattern;
import com.codetouml.model.ParseResult;
import com.codetouml.model.UmlClass;
import com.codetouml.model.UmlField;
import com.codetouml.model.UmlMethod;
import com.codetouml.model.UmlRelation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Renders a {@link ParseResult} into PlantUML source text. */
@Service
public class PlantUmlGenerator {

    public String generate(ParseResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        // Smetana = pure-Java layout, so no Graphviz/dot binary is required.
        sb.append("!pragma layout smetana\n");
        sb.append("skinparam dpi 96\n");
        sb.append("skinparam classAttributeIconSize 0\n");
        sb.append("skinparam shadowing false\n");
        sb.append("skinparam roundCorner 8\n");
        sb.append("hide empty members\n\n");

        Map<String, List<String>> stereotypes = stereotypesByType(result.patterns());

        for (UmlClass c : result.classes()) {
            sb.append(keyword(c.kind())).append(' ').append(c.name());
            for (String stereotype : stereotypes.getOrDefault(c.name(), List.of())) {
                sb.append(" <<").append(stereotype).append(">>");
            }
            sb.append(" {\n");
            for (UmlField f : c.fields()) {
                sb.append("  ").append(f.visibility()).append(' ');
                if (f.isStatic()) {
                    sb.append("{static} ");
                }
                sb.append(f.name());
                if (f.type() != null && !f.type().isEmpty()) {
                    sb.append(" : ").append(f.type());
                }
                sb.append('\n');
            }
            for (UmlMethod m : c.methods()) {
                sb.append("  ").append(m.visibility()).append(' ');
                if (m.isStatic()) {
                    sb.append("{static} ");
                }
                if (m.isAbstract()) {
                    sb.append("{abstract} ");
                }
                sb.append(m.name()).append('(').append(String.join(", ", m.params())).append(')');
                if (m.returnType() != null && !m.returnType().isEmpty()) {
                    sb.append(" : ").append(m.returnType());
                }
                sb.append('\n');
            }
            sb.append("}\n\n");
        }

        for (UmlRelation r : result.relations()) {
            String arrow = arrowFor(r.type());
            if (arrow == null) {
                continue;
            }
            sb.append(r.from()).append(' ').append(arrow).append(' ');
            if (r.multiplicity() != null && !r.multiplicity().isEmpty()) {
                sb.append('"').append(r.multiplicity()).append("\" ");
            }
            sb.append(r.to());
            if (r.label() != null && !r.label().isEmpty()) {
                sb.append(" : ").append(r.label());
            }
            sb.append('\n');
        }

        sb.append("@enduml\n");
        return sb.toString();
    }

    private String keyword(String kind) {
        return switch (kind) {
            case "interface" -> "interface";
            case "enum" -> "enum";
            case "abstract" -> "abstract class";
            case "annotation" -> "annotation";
            default -> "class";
        };
    }

    /** PlantUML arrow for each relationship type (diamond ends sit on the owning class). */
    private String arrowFor(String type) {
        return switch (type) {
            case "extends" -> "--|>";      // generalization (inheritance)
            case "implements" -> "..|>";   // realization
            case "composition" -> "*--";   // filled diamond
            case "aggregation" -> "o--";   // hollow diamond
            case "association" -> "-->";
            case "dependency" -> "..>";    // dashed
            default -> null;
        };
    }

    /** Maps each pattern's primary participant to a list of stereotype labels. */
    private Map<String, List<String>> stereotypesByType(List<DetectedPattern> patterns) {
        Map<String, List<String>> result = new HashMap<>();
        if (patterns == null) {
            return result;
        }
        for (DetectedPattern p : patterns) {
            if (!p.participants().isEmpty()) {
                result.computeIfAbsent(p.participants().get(0), k -> new ArrayList<>()).add(p.name());
            }
        }
        return result;
    }
}
