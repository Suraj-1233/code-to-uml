# Code → UML

A small full-stack web app that turns **Java source code into a UML class diagram**.
Paste one or more Java classes, click **Generate**, and get a rendered class diagram you
can download as SVG or as PlantUML text.

- **Backend:** Spring Boot (Java 21) — parses Java with **JavaParser**, builds a class model,
  emits **PlantUML**, and renders it to SVG in-process.
- **Frontend:** Angular 21 (standalone components + signals) — a **Monaco** (VS Code) editor
  on the left with Java syntax highlighting, and a **pan/zoom** diagram viewer on the right.

The diagram is rendered with PlantUML's pure-Java **Smetana** layout engine, so **Graphviz
is *not* required** — everything runs inside the JVM.

---

## How it works

```
 Java source (textarea)
        │  POST /api/uml/generate  { code }
        ▼
 ┌──────────────────────────────────────────────┐
 │ Spring Boot backend                           │
 │                                               │
 │  JavaCodeParser   →  parse AST (JavaParser)   │
 │                      classes / fields /       │
 │                      methods / relations      │
 │  PlantUmlGenerator →  build PlantUML text     │
 │  PlantUmlRenderer  →  render to SVG (Smetana) │
 └──────────────────────────────────────────────┘
        │  { plantuml, svg, warnings }
        ▼
 Angular renders the SVG + download buttons
```

What it extracts:

- Classes, abstract classes, interfaces and enums (including nested types)
- Fields and methods with visibility (`+ - # ~`), `{static}` and `{abstract}` markers
- The full set of UML relationships, classified from the code:
  - `extends` → **inheritance**, `implements` → **realization**
  - a field created with `new T()` → **composition** (filled diamond)
  - a collection field, or one injected via the constructor → **aggregation** (hollow diamond, with `*` multiplicity)
  - a plain field reference → **association**
  - a type used only in a method/constructor signature → **dependency** (dashed)
- **Design patterns** — all **23 Gang-of-Four** patterns, detected with structural heuristics and
  shown as `«stereotype»` labels on the diagram plus a patterns panel in the UI:
  - *Creational:* Singleton, Factory Method, Abstract Factory, Builder, Prototype
  - *Structural:* Adapter, Bridge, Composite, Decorator, Facade, Flyweight, Proxy
  - *Behavioral:* Chain of Responsibility, Command, Interpreter, Iterator, Mediator, Memento,
    Observer, State, Strategy, Template Method, Visitor

  Several patterns are structurally identical and differ only by intent (Strategy / State / Command /
  Bridge are all "an abstraction + implementations + a holder"). Those are resolved by precedence and
  by method/class naming (e.g. `*State` ⇒ State, `execute()`/`undo()` ⇒ Command, `*Adapter`/`*Proxy`/
  `*Facade`/`*Mediator` naming). Each abstraction is *claimed* once, so it is never double-reported —
  e.g. an interface already explained as a Factory product, Observer subject or Bridge implementor is
  not also tagged Strategy. Detection favours precision over recall: it would rather miss a pattern
  than invent one, so the naming-driven ones (Mediator, Memento, Flyweight, Interpreter) need the
  conventional names to be recognised. Verified on a sample that exercises all 23 (23/23, no false
  positives).

---

## Prerequisites

- **JDK 21+** and **Maven 3.9+**
- **Node 20+** and **npm** (Angular CLI is bundled via the project dev dependencies)

## Run it

Open two terminals.

**1. Backend** (port `8080`):

```bash
cd backend
mvn spring-boot:run
```

**2. Frontend** (port `4200`):

```bash
cd frontend
npm install      # first time only
npm start        # = ng serve
```

Then open <http://localhost:4200>, paste Java code, and click **Generate UML**.

> The backend allows CORS from any origin, so the Angular dev server on `:4200` can call
> the API on `:8080` out of the box. If you change the backend port, update `baseUrl` in
> [`frontend/src/app/uml.service.ts`](frontend/src/app/uml.service.ts).

---

## API

`POST /api/uml/generate`

```jsonc
// request
{ "code": "public class Foo { private Bar bar; }" }

// response
{
  "plantuml": "@startuml ... @enduml",
  "svg": "<svg ...>...</svg>",
  "warnings": [],
  "patterns": [ { "name": "Singleton", "participants": ["Config"], "note": "..." } ]
}
```

Quick test with curl:

```bash
curl -s -X POST http://localhost:8080/api/uml/generate \
  -H "Content-Type: application/json" \
  -d '{"code":"public interface Greeter { String hello(String name); }"}'
```

---

## Project layout

```
code-to-uml/
├── backend/                     Spring Boot service
│   ├── pom.xml
│   └── src/main/java/com/codetouml/
│       ├── controller/UmlController.java     REST endpoint
│       ├── service/JavaCodeParser.java       Java source → model + relationships (JavaParser)
│       ├── service/PatternDetector.java      structural design-pattern detection
│       ├── service/PlantUmlGenerator.java     model → PlantUML text
│       ├── service/PlantUmlRenderer.java       PlantUML → SVG (Smetana)
│       ├── service/UmlService.java             orchestration
│       ├── model/                              UmlClass / UmlRelation / DetectedPattern / ...
│       └── dto/                                request & response records
└── frontend/                    Angular app
    └── src/app/
        ├── app.ts / app.html / app.css         main component (signals) + UI
        ├── code-editor.component.ts            Monaco (VS Code) editor wrapper
        ├── diagram-viewer.component.ts         pan/zoom SVG viewer
        ├── monaco-loader.ts                    loads Monaco from CDN
        └── uml.service.ts                      HTTP client + response types
```

---

## Ideas for v2

- Upload a `.java` file or a whole project as `.zip` and diagram everything together
- Sequence diagrams from method bodies
- Deploy it live (single Spring Boot JAR), or add an AI design review (Claude API)
- Other languages via different parsers (the model layer is language-agnostic)
