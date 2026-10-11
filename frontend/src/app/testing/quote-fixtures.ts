import { CreateQuoteRequest, Quote, QuotePage } from '@app/core/quotes/quote.models';
export const quoteInput: CreateQuoteRequest = {
  dateOfBirth: '1995-04-20',
  drivingExperienceYears: 8,
  previousClaimsCount: 1,
  vehicleBrandId: 'brand-a',
  vehicleModelId: 'model-a',
  vehicleManufacturingYear: 2021,
  vehicleValue: 700000,
  engineSizeCc: 1500,
  coverageType: 'COMPREHENSIVE',
};
export const storedQuote: Quote = {
  id: 'quote-a',
  quoteReference: 'Q-HISTORICAL',
  status: 'PRICED',
  coverageType: 'COMPREHENSIVE',
  driver: { dateOfBirth: '1995-04-20', drivingExperienceYears: 8, previousClaimsCount: 1 },
  vehicle: {
    brandId: 'brand-a',
    modelId: 'model-a',
    brandName: 'Historical brand',
    modelName: 'Historical model',
    manufacturingYear: 2021,
    vehicleValue: 700000,
    engineSizeCc: 1500,
  },
  pricing: {
    basePremium: 8000,
    finalPremium: 11200,
    currency: 'THB',
    calculatedAt: '2026-10-01T00:00:00Z',
    adjustments: [
      {
        pricingRuleId: 'rule-a',
        ruleType: 'COVERAGE_TYPE',
        description: 'Stored coverage adjustment',
        inputValue: 'COMPREHENSIVE',
        factor: 1.4,
        amountBefore: 8000,
        amountAfter: 11200,
        sequenceNumber: 1,
      },
    ],
  },
  createdAt: '2026-10-01T00:00:00Z',
  pricedAt: '2026-10-01T00:00:00Z',
  expiresAt: '2026-10-31T00:00:00Z',
};
export const quotePage: QuotePage = {
  content: [storedQuote],
  page: 0,
  size: 20,
  totalElements: 21,
  totalPages: 2,
};
