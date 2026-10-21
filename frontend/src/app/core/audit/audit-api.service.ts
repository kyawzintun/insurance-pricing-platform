import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { GATEWAY_URL } from '@app/core/auth/gateway.config';
import { AuditDetail, AuditPage, AuditQuery } from './audit.models';

@Injectable({ providedIn: 'root' })
export class AuditApiService {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(GATEWAY_URL)}/api/v1/admin/audit`;

  list(query: AuditQuery) {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query))
      if (value !== undefined && value !== '') params = params.set(key, value);
    return this.http.get<AuditPage>(this.base, { params });
  }

  detail(id: string) {
    return this.http.get<AuditDetail>(`${this.base}/${encodeURIComponent(id)}`);
  }
}
