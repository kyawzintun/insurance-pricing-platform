import { Injectable } from '@angular/core';

/** Synchronous fan-out: cancel requests before clearing protected state. No domain state lives here. */
@Injectable({ providedIn: 'root' })
export class SessionResetService {
  private readonly resets = new Set<() => void>();
  register(reset: () => void): () => void {
    this.resets.add(reset);
    return () => this.resets.delete(reset);
  }
  reset(): void {
    for (const reset of this.resets) reset();
  }
}
