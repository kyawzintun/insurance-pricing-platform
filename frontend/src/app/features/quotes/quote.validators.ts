import { AbstractControl, ValidationErrors } from '@angular/forms';
export function integer(control: AbstractControl): ValidationErrors | null {
  return control.value == null || control.value === '' || Number.isInteger(control.value)
    ? null
    : { integer: true };
}
export function pastDate(control: AbstractControl): ValidationErrors | null {
  if (!control.value) return null;
  const value = String(control.value);
  const date = new Date(`${value}T00:00:00Z`);
  return /^\d{4}-\d{2}-\d{2}$/.test(value) &&
    Number.isFinite(date.getTime()) &&
    date.toISOString().slice(0, 10) === value &&
    value < new Date().toISOString().slice(0, 10)
    ? null
    : { pastDate: true };
}
export function vehicleYear(control: AbstractControl): ValidationErrors | null {
  return control.value == null ||
    control.value === '' ||
    (Number.isInteger(control.value) &&
      control.value >= 1886 &&
      control.value <= new Date().getUTCFullYear())
    ? null
    : { vehicleYear: true };
}
