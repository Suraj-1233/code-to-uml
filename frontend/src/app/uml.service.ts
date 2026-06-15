import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

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

  /** Backend base URL. Override here if you run Spring Boot on a different port. */
  private readonly baseUrl = 'http://localhost:8080';

  generate(code: string): Observable<GenerateResponse> {
    return this.http.post<GenerateResponse>(`${this.baseUrl}/api/uml/generate`, { code });
  }
}
