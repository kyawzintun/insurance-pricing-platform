export type CoverageType = 'COMPREHENSIVE' | 'THIRD_PARTY';
export interface VehicleReference {
  id: string;
  name: string;
}

export interface CreateQuoteRequest {
  dateOfBirth: string;
  drivingExperienceYears: number;
  previousClaimsCount: number;
  vehicleBrandId: string;
  vehicleModelId: string;
  vehicleManufacturingYear: number;
  vehicleValue: number;
  engineSizeCc: number;
  coverageType: CoverageType;
}

export interface Quote {
  id: string;
  quoteReference: string;
  status: string;
  coverageType: CoverageType;
  driver: {
    dateOfBirth: string;
    drivingExperienceYears: number;
    previousClaimsCount: number;
  } | null;
  vehicle: {
    brandId: string;
    brandName: string;
    modelId: string;
    modelName: string;
    manufacturingYear: number;
    vehicleValue: number;
    engineSizeCc: number;
  } | null;
  pricing: {
    basePremium: number;
    finalPremium: number;
    currency: string;
    calculatedAt: string;
    adjustments: {
      pricingRuleId: string;
      ruleType: string;
      description: string | null;
      inputValue: string;
      factor: number;
      amountBefore: number;
      amountAfter: number;
      sequenceNumber: number;
    }[];
  } | null;
  createdAt: string;
  pricedAt: string | null;
  expiresAt: string | null;
}

export interface QuotePage {
  content: Quote[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
