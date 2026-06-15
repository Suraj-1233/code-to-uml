import { Component, computed, effect, input, signal } from '@angular/core';
import { SafeHtml } from '@angular/platform-browser';

const MIN_SCALE = 0.2;
const MAX_SCALE = 6;

/** Displays an injected SVG diagram with mouse-wheel zoom and drag-to-pan. */
@Component({
  selector: 'app-diagram-viewer',
  standalone: true,
  template: `
    <div
      class="viewport"
      [class.grabbing]="dragging()"
      (wheel)="onWheel($event)"
      (mousedown)="onDown($event)"
      (mousemove)="onMove($event)"
      (mouseup)="onUp()"
      (mouseleave)="onUp()">
      <div class="canvas" [style.transform]="transform()" [innerHTML]="svg()"></div>
    </div>

    <div class="controls">
      <button type="button" (click)="zoomBy(1.2)" title="Zoom in">+</button>
      <button type="button" (click)="zoomBy(0.8333)" title="Zoom out">&minus;</button>
      <span class="zoom-label">{{ zoomPercent() }}</span>
      <button type="button" (click)="reset()" title="Reset view">Reset</button>
    </div>
  `,
  styleUrl: './diagram-viewer.component.css',
})
export class DiagramViewerComponent {
  readonly svg = input<SafeHtml | null>(null);

  readonly scale = signal(1);
  readonly panX = signal(0);
  readonly panY = signal(0);
  readonly dragging = signal(false);

  private lastX = 0;
  private lastY = 0;

  readonly transform = computed(
    () => `translate(${this.panX()}px, ${this.panY()}px) scale(${this.scale()})`
  );
  readonly zoomPercent = computed(() => `${Math.round(this.scale() * 100)}%`);

  constructor() {
    // Reset the view whenever a new diagram is loaded.
    effect(() => {
      this.svg();
      this.reset();
    });
  }

  onWheel(event: WheelEvent): void {
    event.preventDefault();
    const factor = event.deltaY < 0 ? 1.1 : 1 / 1.1;
    this.zoomAt(event.offsetX, event.offsetY, factor);
  }

  /** Zoom keeping the point under (cx, cy) fixed on screen. */
  private zoomAt(cx: number, cy: number, factor: number): void {
    const current = this.scale();
    const next = this.clamp(current * factor);
    const ratio = next / current;
    this.panX.set(cx - ratio * (cx - this.panX()));
    this.panY.set(cy - ratio * (cy - this.panY()));
    this.scale.set(next);
  }

  zoomBy(factor: number): void {
    this.scale.set(this.clamp(this.scale() * factor));
  }

  onDown(event: MouseEvent): void {
    this.dragging.set(true);
    this.lastX = event.clientX;
    this.lastY = event.clientY;
  }

  onMove(event: MouseEvent): void {
    if (!this.dragging()) {
      return;
    }
    this.panX.update((x) => x + (event.clientX - this.lastX));
    this.panY.update((y) => y + (event.clientY - this.lastY));
    this.lastX = event.clientX;
    this.lastY = event.clientY;
  }

  onUp(): void {
    this.dragging.set(false);
  }

  reset(): void {
    this.scale.set(1);
    this.panX.set(0);
    this.panY.set(0);
  }

  private clamp(value: number): number {
    return Math.min(MAX_SCALE, Math.max(MIN_SCALE, value));
  }
}
