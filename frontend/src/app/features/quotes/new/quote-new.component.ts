import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Store } from '@ngrx/store';
import { catchError, of, switchMap, tap } from 'rxjs';
import { QuoteApiService } from '@app/core/quotes/quote-api.service';
import { CoverageType, CreateQuoteRequest, VehicleReference } from '@app/core/quotes/quote.models';
import { quoteErrorMessage } from '@app/core/quotes/quote-errors';
import { QuoteActions } from '@app/store/quotes/quote.actions';
import { quoteFeature } from '@app/store/quotes/quote.reducer';
import { integer, pastDate, vehicleYear } from '@app/features/quotes/quote.validators';

@Component({
  selector: 'app-quote-new',
  imports: [ReactiveFormsModule],
  templateUrl: './quote-new.component.html',
})
export class QuoteNewComponent {
  private readonly api = inject(QuoteApiService);
  private readonly store = inject(Store);
  private readonly destroyRef = inject(DestroyRef);
  readonly status = this.store.selectSignal(quoteFeature.selectCreateStatus);
  readonly error = this.store.selectSignal(quoteFeature.selectCreateError);
  readonly brands = signal<VehicleReference[]>([]);
  readonly models = signal<VehicleReference[]>([]);
  readonly loadingBrands = signal(true);
  readonly loadingModels = signal(false);
  readonly catalogError = signal<string | null>(null);
  readonly currentYear = new Date().getUTCFullYear();

  readonly form = inject(FormBuilder).group({
    dateOfBirth: ['', [Validators.required, pastDate]],
    drivingExperienceYears: [
      null as number | null,
      [Validators.required, integer, Validators.min(0)],
    ],
    previousClaimsCount: [null as number | null, [Validators.required, integer, Validators.min(0)]],
    vehicleBrandId: ['', Validators.required],
    vehicleModelId: ['', Validators.required],
    vehicleManufacturingYear: [null as number | null, [Validators.required, vehicleYear]],
    vehicleValue: [
      null as number | null,
      [
        Validators.required,
        Validators.min(0.01),
        Validators.max(9999999999999.99),
        Validators.pattern(/^\d+(\.\d{1,2})?$/),
      ],
    ],
    engineSizeCc: [null as number | null, [Validators.required, integer, Validators.min(1)]],
    coverageType: ['' as CoverageType | '', Validators.required],
  });
  constructor() {
    this.loadBrands();
    this.form.controls.vehicleBrandId.valueChanges
      .pipe(
        tap(() => {
          this.form.controls.vehicleModelId.reset('');
          this.models.set([]);
          this.catalogError.set(null);
        }),
        switchMap((brandId) => {
          this.loadingModels.set(!!brandId);
          return brandId
            ? this.api.getVehicleModels(brandId).pipe(
                catchError((error) => {
                  this.catalogError.set(quoteErrorMessage(error, 'catalog'));
                  return of([]);
                }),
              )
            : of([]);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((models) => {
        this.models.set(models);
        this.loadingModels.set(false);
      });
  }
  loadBrands(): void {
    this.loadingBrands.set(true);
    this.catalogError.set(null);
    this.api
      .getVehicleBrands()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (brands) => {
          this.brands.set(brands);
          this.loadingBrands.set(false);
        },
        error: (error) => {
          this.catalogError.set(quoteErrorMessage(error, 'catalog'));
          this.loadingBrands.set(false);
        },
      });
  }
  invalid(field: keyof typeof this.form.controls): boolean {
    const control = this.form.controls[field];
    return control.touched && control.invalid;
  }
  submit(): void {
    if (this.status() === 'pending') return;
    this.form.markAllAsTouched();
    if (this.form.invalid || this.loadingModels() || this.loadingBrands()) return;
    const value = this.form.getRawValue();
    if (
      !this.brands().some((b) => b.id === value.vehicleBrandId) ||
      !this.models().some((m) => m.id === value.vehicleModelId)
    ) {
      this.catalogError.set('Selected vehicle is no longer available.');
      return;
    }
    // The validated form contains only the nine backend input fields, never identity or calculated values.
    this.store.dispatch(QuoteActions.createQuote({ request: value as CreateQuoteRequest }));
  }
}
