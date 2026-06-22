import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  effect,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { loadMonaco } from './monaco-loader';

/**
 * A Monaco-backed code editor. Emits {@link valueChange} on every edit.
 * Falls back to a plain <textarea> if Monaco fails to load.
 */
@Component({
  selector: 'app-code-editor',
  standalone: true,
  template: `
    <div #host class="monaco-host" [class.hidden]="useFallback()"></div>

    @if (loading()) {
      <div class="status">Loading editor…</div>
    }

    @if (useFallback()) {
      <textarea
        class="fallback"
        spellcheck="false"
        [value]="value()"
        (input)="onFallbackInput($event)"></textarea>
    }
  `,
  styleUrl: './code-editor.component.css',
})
export class CodeEditorComponent implements AfterViewInit, OnDestroy {
  private readonly host = viewChild.required<ElementRef<HTMLDivElement>>('host');

  readonly value = input<string>('');
  readonly valueChange = output<string>();

  readonly loading = signal(true);
  readonly useFallback = signal(false);

  private editor: any;

  constructor() {
    // Sync the editor when `value` changes from outside (Clear, or loading a saved diagram).
    // The guard makes typing a no-op here (editor already holds the value), avoiding a loop.
    effect(() => {
      const next = this.value();
      if (this.editor && this.editor.getValue() !== next) {
        this.editor.setValue(next);
      }
    });
  }

  ngAfterViewInit(): void {
    loadMonaco()
      .then((monaco) => {
        this.editor = monaco.editor.create(this.host().nativeElement, {
          value: this.value(),
          language: 'java',
          theme: 'vs-dark',
          automaticLayout: true,
          minimap: { enabled: false },
          fontSize: 13.5,
          lineHeight: 22,
          scrollBeyondLastLine: false,
          tabSize: 4,
          renderLineHighlight: 'all',
          padding: { top: 12, bottom: 12 },
          scrollbar: { verticalScrollbarSize: 10, horizontalScrollbarSize: 10 },
        });
        this.editor.onDidChangeModelContent(() => {
          this.valueChange.emit(this.editor.getValue());
        });
        this.loading.set(false);
      })
      .catch(() => {
        this.loading.set(false);
        this.useFallback.set(true);
      });
  }

  onFallbackInput(event: Event): void {
    this.valueChange.emit((event.target as HTMLTextAreaElement).value);
  }

  ngOnDestroy(): void {
    this.editor?.dispose();
  }
}
