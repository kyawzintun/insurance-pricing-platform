import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { GATEWAY_URL } from '@app/core/auth/gateway.config';

export interface OperationsService {
  id: string;
  name: string;
  status: 'UP' | 'DOWN' | 'DEGRADED' | 'UNKNOWN';
  readiness: 'READY' | 'NOT_READY' | 'UNKNOWN';
  responseTimeMs: number;
  checkedAt: string;
  errorCode: string | null;
  metrics: Record<string, number | string | null>;
}
export interface OperationsOverview {
  overallStatus: 'UP' | 'DEGRADED' | 'DOWN';
  checkedAt: string;
  services: OperationsService[];
}
@Injectable({ providedIn: 'root' })
export class OperationsApiService {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(GATEWAY_URL)}/api/v1/admin/operations`;
  overview() {
    return this.http.get<OperationsOverview>(this.url);
  }
}
