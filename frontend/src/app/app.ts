import { AfterViewInit, Component, ElementRef, effect, inject, signal, viewChild } from '@angular/core';
import { DatePipe } from '@angular/common';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { Observable } from 'rxjs';
import { UmlService, DetectedPattern, GenerateResponse, DiagramSummary } from './uml.service';
import { AuthService } from './auth.service';
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
  imports: [CodeEditorComponent, DiagramViewerComponent, DatePipe],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App implements AfterViewInit {
  private readonly uml = inject(UmlService);
  private readonly sanitizer = inject(DomSanitizer);
  readonly auth = inject(AuthService);

  private readonly googleBtn = viewChild<ElementRef<HTMLElement>>('googleBtn');

  readonly code = signal<string>(SAMPLE_CODE);
  readonly svg = signal<SafeHtml | null>(null);
  readonly rawSvg = signal<string>('');
  readonly plantuml = signal<string>('');
  readonly warnings = signal<string[]>([]);
  readonly patterns = signal<DetectedPattern[]>([]);
  readonly error = signal<string | null>(null);
  readonly loading = signal<boolean>(false);
  readonly copied = signal<boolean>(false);
  readonly repoUrl = signal<string>('');

  // Saved-diagram history (Google sign-in)
  private currentSource: { type: 'code' | 'repo'; value: string } | null = null;
  readonly history = signal<DiagramSummary[]>([]);
  readonly showHistory = signal<boolean>(false);
  readonly saved = signal<boolean>(false);
  readonly signupCount = signal<number | null>(null);

  constructor() {
    // On sign-in (or a restored session) the list call upserts the user server-side,
    // which is how we count sign-ups; it also pre-loads history + refreshes the count.
    effect(() => {
      if (this.auth.user()) {
        this.refreshHistory();
      }
    });
  }

  ngAfterViewInit(): void {
    this.refreshStats();
    const el = this.googleBtn()?.nativeElement;
    if (el) {
      this.auth.renderButton(el).catch(() => { /* GIS unavailable — sign-in just won't show */ });
    }
  }

  private refreshStats(): void {
    this.uml.getStats().subscribe({
      next: (s) => this.signupCount.set(s.users),
      error: () => { /* non-critical — just don't show the badge */ },
    });
  }

  generate(): void {
    const code = this.code().trim();
    if (!code) {
      this.error.set('Please paste some Java code first.');
      return;
    }
    this.run(this.uml.generate(code), { type: 'code', value: code });
  }

  /** Builds the diagram from a public GitHub repo URL. */
  generateRepo(): void {
    const url = this.repoUrl().trim();
    if (!url) {
      this.error.set('Paste a public GitHub repo URL first.');
      return;
    }
    this.run(this.uml.generateRepo(url), { type: 'repo', value: url });
  }

  private run(request: Observable<GenerateResponse>, source: { type: 'code' | 'repo'; value: string }): void {
    this.loading.set(true);
    this.error.set(null);
    request.subscribe({
      next: (res) => {
        this.plantuml.set(res.plantuml);
        this.rawSvg.set(res.svg);
        this.svg.set(this.sanitizer.bypassSecurityTrustHtml(res.svg));
        this.warnings.set(res.warnings ?? []);
        this.patterns.set(res.patterns ?? []);
        this.currentSource = source;
        this.loading.set(false);
      },
      error: (err) => {
        this.svg.set(null);
        this.rawSvg.set('');
        this.plantuml.set('');
        this.patterns.set([]);
        this.currentSource = null;
        this.error.set(
          err?.error?.error ??
          'Could not reach the backend. Make sure it is running.'
        );
        this.loading.set(false);
      }
    });
  }

  // --- saved diagrams (Google sign-in) -----------------------------------

  /** Whether the current diagram can be saved (signed in + something rendered). */
  canSave(): boolean {
    return !!this.auth.user() && !!this.rawSvg() && !!this.currentSource;
  }

  save(): void {
    const src = this.currentSource;
    if (!this.auth.user() || !src) {
      return;
    }
    const title = src.type === 'repo' ? this.repoName(src.value) : (this.firstTypeName(src.value) ?? 'Java snippet');
    this.uml.saveDiagram(title, src.type, src.value).subscribe({
      next: () => {
        this.saved.set(true);
        setTimeout(() => this.saved.set(false), 1500);
        if (this.showHistory()) {
          this.refreshHistory();
        }
      },
      error: (err) => this.handleAuthError(err, 'Could not save the diagram.'),
    });
  }

  toggleHistory(): void {
    const open = !this.showHistory();
    this.showHistory.set(open);
    if (open) {
      this.refreshHistory();
    }
  }

  private refreshHistory(): void {
    this.uml.listDiagrams().subscribe({
      next: (rows) => {
        this.history.set(rows);
        this.refreshStats();   // the list call just recorded this user — reflect it in the count
      },
      error: (err) => this.handleAuthError(err, 'Could not load your saved diagrams.'),
    });
  }

  openSaved(d: DiagramSummary): void {
    this.uml.getDiagram(d.id).subscribe({
      next: (full) => {
        this.showHistory.set(false);
        if (full.sourceType === 'repo') {
          this.repoUrl.set(full.source);
          this.generateRepo();
        } else {
          this.repoUrl.set('');
          this.code.set(full.source);
          this.generate();
        }
      },
      error: (err) => this.handleAuthError(err, 'Could not open that diagram.'),
    });
  }

  deleteSaved(d: DiagramSummary, event: Event): void {
    event.stopPropagation();
    this.uml.deleteDiagram(d.id).subscribe({
      next: () => this.refreshHistory(),
      error: (err) => this.handleAuthError(err, 'Could not delete that diagram.'),
    });
  }

  signOut(): void {
    this.auth.signOut();
    this.history.set([]);
    this.showHistory.set(false);
  }

  private handleAuthError(err: any, fallback: string): void {
    if (err?.status === 401) {
      this.auth.signOut();
      this.history.set([]);
      this.error.set('Your sign-in expired — please sign in again.');
    } else {
      this.error.set(err?.error?.error ?? fallback);
    }
  }

  private repoName(url: string): string {
    const m = url.match(/github\.com[/:]([\w.-]+\/[\w.-]+)/i);
    return m ? m[1].replace(/\.git$/, '') : 'GitHub repo';
  }

  private firstTypeName(code: string): string | null {
    const m = code.match(/\b(?:class|interface|enum|record)\s+(\w+)/);
    return m ? m[1] : null;
  }

  /** Clears the editor and the current diagram. */
  clear(): void {
    this.code.set('');
    this.repoUrl.set('');
    this.svg.set(null);
    this.rawSvg.set('');
    this.plantuml.set('');
    this.patterns.set([]);
    this.warnings.set([]);
    this.error.set(null);
    this.currentSource = null;
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
