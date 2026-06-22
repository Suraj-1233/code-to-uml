package com.codetouml.service;

import com.codetouml.model.DetectedPattern;
import com.codetouml.model.ParseResult;
import com.codetouml.model.UmlClass;
import com.codetouml.model.UmlField;
import com.codetouml.model.UmlMethod;
import com.codetouml.model.UmlRelation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Renders a {@link ParseResult} into PlantUML source text. */
@Service
public class PlantUmlGenerator {

    /**
     * Layout engine: "smetana" (pure-Java, no Graphviz — fine for small diagrams) or "dot"
     * (Graphviz, far more robust for large repo diagrams; the prod image ships Graphviz).
     */
    private final String layout;

    public PlantUmlGenerator(@Value("${uml.layout:smetana}") String layout) {
        this.layout = layout;
    }

    public String generate(ParseResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        if (!"dot".equalsIgnoreCase(layout)) {
            // Pure-Java layout (no Graphviz needed). With uml.layout=dot, PlantUML uses Graphviz instead.
            sb.append("!pragma layout smetana\n");
        }
        sb.append("skinparam dpi 96\n");
        sb.append("skinparam backgroundColor #FAFBFC\n");
        sb.append("skinparam shadowing false\n");
        sb.append("skinparam roundCorner 12\n");
        sb.append("skinparam classAttributeIconSize 0\n");
        sb.append("skinparam defaultFontName SansSerif\n");
        sb.append("skinparam defaultFontSize 13\n");
        sb.append("skinparam linetype ortho\n");
        sb.append("skinparam nodesep 45\n");
        sb.append("skinparam ranksep 70\n");
        sb.append("skinparam class {\n");
        sb.append("  FontStyle bold\n");
        sb.append("  FontColor #0F172A\n");
        sb.append("  AttributeFontColor #334155\n");
        sb.append("  AttributeFontSize 12\n");
        sb.append("  StereotypeFontColor #6D28D9\n");
        sb.append("}\n");
        sb.append("skinparam ArrowThickness 1.3\n");
        sb.append("skinparam ArrowFontColor #475569\n");
        sb.append("skinparam ArrowFontSize 12\n");
        sb.append("hide empty members\n\n");

        Map<String, List<String>> stereotypes = stereotypesByType(result.patterns());

        for (UmlClass c : result.classes()) {
            sb.append(keyword(c.kind())).append(' ').append(c.name());
            if ("record".equals(c.kind())) {
                sb.append(" <<record>>");
            }
            for (String stereotype : stereotypes.getOrDefault(c.name(), List.of())) {
                sb.append(" <<").append(stereotype).append(">>");
            }
            sb.append(' ').append(colorFor(c.kind()));
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

    /** A distinct background + border colour per element kind, so the diagram reads at a glance. */
    private String colorFor(String kind) {
        return switch (kind) {
            case "interface" -> "#DBEAFE ##2563EB";   // blue
            case "enum" -> "#FFEDD5 ##EA580C";        // amber
            case "abstract" -> "#EDE9FE ##7C3AED";    // violet
            case "annotation" -> "#FEF9C3 ##CA8A04";  // yellow
            case "record" -> "#CCFBF1 ##0D9488";      // teal
            default -> "#ECFDF5 ##10B981";            // green (concrete class)
        };
    }

    /** PlantUML arrow for each relationship type, colour-coded (diamond ends sit on the owning class). */
    private String arrowFor(String type) {
        return switch (type) {
            case "extends" -> "-[#1E88E5]-|>";          // generalization — blue
            case "implements" -> "-[#1E88E5,dashed]-|>"; // realization — dashed blue
            case "composition" -> "*-[#E53935]-";        // filled diamond — red
            case "aggregation" -> "o-[#FB8C00]-";        // hollow diamond — orange
            case "association" -> "-[#546E7A]->";        // slate
            case "dependency" -> "-[#90A4AE,dashed]->";  // dashed grey
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
