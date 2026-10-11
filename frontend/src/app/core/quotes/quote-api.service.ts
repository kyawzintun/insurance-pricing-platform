import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { GATEWAY_URL } from '@app/core/auth/gateway.config';
import {
  CreateQuoteRequest,
  Quote,
  QuotePage,
  VehicleReference,
} from '@app/core/quotes/quote.models';

@Injectable({ providedIn: 'root' })
export class QuoteApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(GATEWAY_URL);

  createQuote(request: CreateQuoteRequest) {
    return this.http.post<Quote>(`${this.base}/api/v1/quotes`, request);
  }
  getQuotes(page = 0, size = 20) {
    return this.http.get<QuotePage>(`${this.base}/api/v1/quotes`, { params: { page, size } });
  }
  getQuote(id: string) {
    return this.http.get<Quote>(`${this.base}/api/v1/quotes/${encodeURIComponent(id)}`);
  }
  getVehicleBrands() {
    return this.http.get<VehicleReference[]>(`${this.base}/api/v1/vehicles/brands`);
  }
  getVehicleModels(brandId: string) {
    return this.http.get<VehicleReference[]>(
      `${this.base}/api/v1/vehicles/brands/${encodeURIComponent(brandId)}/models`,
    );
  }
}
