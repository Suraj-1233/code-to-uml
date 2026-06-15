import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../environments/environment';

export interface DetectedPattern {
  name: string;
  participants: string[];
  note: string;
}

export interface GenerateResponse {
  plantuml: string;
  svg: string;
  warnings: string[];
  patterns: DetectedPattern[];
}

@Injectable({ providedIn: 'root' })
export class UmlService {
  private readonly http = inject(HttpClient);

  /** Backend base URL — set via environment.ts (dev) or environment.prod.ts (production). */
  private readonly baseUrl = environment.apiUrl;

  generate(code: string): Observable<GenerateResponse> {
    return this.http.post<GenerateResponse>(`${this.baseUrl}/api/uml/generate`, { code });
  }
}
