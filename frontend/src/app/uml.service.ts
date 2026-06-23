import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../environments/environment';
import { AuthService } from './auth.service';

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

/** A row in the signed-in user's saved-diagram history. */
export interface DiagramSummary {
  id: number;
  title: string;
  sourceType: 'code' | 'repo';
  createdAt: string;
}

/** A saved diagram with its source (code or repo URL). */
export interface DiagramDetail {
  id: number;
  title: string;
  sourceType: 'code' | 'repo';
  source: string;
}

@Injectable({ providedIn: 'root' })
export class UmlService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  /** Backend base URL — set via environment.ts (dev) or environment.prod.ts (production). */
  private readonly baseUrl = environment.apiUrl;

  generate(code: string): Observable<GenerateResponse> {
    return this.http.post<GenerateResponse>(`${this.baseUrl}/api/uml/generate`, { code });
  }

  /** Builds a diagram from a whole public GitHub repo (github.com/owner/repo[/tree/branch/path]). */
  generateRepo(repoUrl: string): Observable<GenerateResponse> {
    return this.http.post<GenerateResponse>(`${this.baseUrl}/api/uml/generate-repo`, { repoUrl });
  }

  // --- saved diagrams (require Google sign-in) ----------------------------

  saveDiagram(title: string, sourceType: 'code' | 'repo', source: string): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.baseUrl}/api/diagrams`,
      { title, sourceType, source }, { headers: this.auth.authHeader() });
  }

  listDiagrams(): Observable<DiagramSummary[]> {
    return this.http.get<DiagramSummary[]>(`${this.baseUrl}/api/diagrams`, { headers: this.auth.authHeader() });
  }

  getDiagram(id: number): Observable<DiagramDetail> {
    return this.http.get<DiagramDetail>(`${this.baseUrl}/api/diagrams/${id}`, { headers: this.auth.authHeader() });
  }

  deleteDiagram(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/api/diagrams/${id}`, { headers: this.auth.authHeader() });
  }

  /** Public sign-up count (no auth) for the "N developers" badge. */
  getStats(): Observable<{ users: number }> {
    return this.http.get<{ users: number }>(`${this.baseUrl}/api/stats`);
  }
}
