import { Injectable, NgZone, inject, signal } from '@angular/core';
import { environment } from '../environments/environment';

declare const google: any;

export interface GoogleUser {
  name: string;
  email: string;
  picture: string;
}

const TOKEN_KEY = 'code-to-uml.id_token';

/** Google Sign-In (Identity Services). Holds the ID token used to authenticate API calls. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly zone = inject(NgZone);

  readonly token = signal<string | null>(null);
  readonly user = signal<GoogleUser | null>(null);

  private gisReady?: Promise<void>;

  constructor() {
    const saved = localStorage.getItem(TOKEN_KEY);
    if (saved && !this.isExpired(saved)) {
      this.token.set(saved);
      this.user.set(this.decode(saved));
    } else if (saved) {
      localStorage.removeItem(TOKEN_KEY);
    }
  }

  /** Loads the GIS script (once), then renders the official Google button into `el`. */
  async renderButton(el: HTMLElement): Promise<void> {
    await this.loadGis();
    google.accounts.id.initialize({
      client_id: environment.googleClientId,
      callback: (resp: { credential: string }) => this.onCredential(resp.credential),
    });
    google.accounts.id.renderButton(el, {
      theme: 'filled_blue',
      size: 'medium',
      type: 'standard',
      shape: 'pill',
      text: 'signin_with',
    });
  }

  signOut(): void {
    this.token.set(null);
    this.user.set(null);
    localStorage.removeItem(TOKEN_KEY);
    try {
      google.accounts.id.disableAutoSelect();
    } catch {
      /* GIS may not be loaded yet — nothing to do */
    }
  }

  /** Authorization header for API calls (empty when signed out). */
  authHeader(): Record<string, string> {
    const t = this.token();
    return t ? { Authorization: `Bearer ${t}` } : {};
  }

  private onCredential(credential: string): void {
    // GIS invokes this outside Angular's zone — run inside so the UI updates.
    this.zone.run(() => {
      this.token.set(credential);
      this.user.set(this.decode(credential));
      localStorage.setItem(TOKEN_KEY, credential);
    });
  }

  private loadGis(): Promise<void> {
    if (this.gisReady) {
      return this.gisReady;
    }
    this.gisReady = new Promise<void>((resolve, reject) => {
      if (typeof google !== 'undefined' && google.accounts?.id) {
        resolve();
        return;
      }
      const s = document.createElement('script');
      s.src = 'https://accounts.google.com/gsi/client';
      s.async = true;
      s.defer = true;
      s.onload = () => resolve();
      s.onerror = () => reject(new Error('Failed to load Google sign-in'));
      document.head.appendChild(s);
    });
    return this.gisReady;
  }

  private payload(token: string): any {
    return JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
  }

  private decode(token: string): GoogleUser {
    try {
      const p = this.payload(token);
      return { name: p.name || p.email || 'You', email: p.email || '', picture: p.picture || '' };
    } catch {
      return { name: 'You', email: '', picture: '' };
    }
  }

  private isExpired(token: string): boolean {
    try {
      const p = this.payload(token);
      return !p.exp || p.exp * 1000 < Date.now();
    } catch {
      return true;
    }
  }
}
