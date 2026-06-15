import { Component, signal, inject } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { UmlService, DetectedPattern } from './uml.service';
import { CodeEditorComponent } from './code-editor.component';
import { DiagramViewerComponent } from './diagram-viewer.component';

const SAMPLE_CODE = `import java.util.List;

public interface Shape {
    double area();
}

public abstract class AbstractShape implements Shape {
    protected String name;
    public String getName() { return name; }
    public abstract double area();
}

public class Circle extends AbstractShape {
    private double radius;
    public double area() { return Math.PI * radius * radius; }
}

public class Canvas {
    private List<Shape> shapes;
    private Color background;
    public void add(Shape s) { shapes.add(s); }
}

public enum Color { RED, GREEN, BLUE }
`;

@Component({
  selector: 'app-root',
  imports: [CodeEditorComponent, DiagramViewerComponent],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App {
  private readonly uml = inject(UmlService);
  private readonly sanitizer = inject(DomSanitizer);

  readonly code = signal<string>(SAMPLE_CODE);
  readonly svg = signal<SafeHtml | null>(null);
  readonly rawSvg = signal<string>('');
  readonly plantuml = signal<string>('');
  readonly warnings = signal<string[]>([]);
  readonly patterns = signal<DetectedPattern[]>([]);
  readonly error = signal<string | null>(null);
  readonly loading = signal<boolean>(false);
  readonly copied = signal<boolean>(false);

  generate(): void {
    const code = this.code().trim();
    if (!code) {
      this.error.set('Please paste some Java code first.');
      return;
    }
    this.loading.set(true);
    this.error.set(null);

    this.uml.generate(code).subscribe({
      next: (res) => {
        this.plantuml.set(res.plantuml);
        this.rawSvg.set(res.svg);
        this.svg.set(this.sanitizer.bypassSecurityTrustHtml(res.svg));
        this.warnings.set(res.warnings ?? []);
        this.patterns.set(res.patterns ?? []);
        this.loading.set(false);
      },
      error: (err) => {
        this.svg.set(null);
        this.rawSvg.set('');
        this.plantuml.set('');
        this.patterns.set([]);
        this.error.set(
          err?.error?.error ??
          'Could not reach the backend at http://localhost:8080. Make sure the Spring Boot app is running.'
        );
        this.loading.set(false);
      }
    });
  }

  downloadSvg(): void {
    this.download(this.rawSvg(), 'diagram.svg', 'image/svg+xml');
  }

  downloadPuml(): void {
    this.download(this.plantuml(), 'diagram.puml', 'text/plain');
  }

  copyPuml(): void {
    const text = this.plantuml();
    if (!text) {
      return;
    }
    const flash = () => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 1500);
    };
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text).then(flash).catch(() => this.legacyCopy(text, flash));
    } else {
      this.legacyCopy(text, flash);
    }
  }

  /** Clipboard fallback for non-secure contexts where the async API is blocked. */
  private legacyCopy(text: string, onDone: () => void): void {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try {
      document.execCommand('copy');
      onDone();
    } catch {
      /* nothing else we can do */
    }
    document.body.removeChild(ta);
  }

  private download(content: string, filename: string, type: string): void {
    if (!content) {
      return;
    }
    const blob = new Blob([content], { type });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  }
}
