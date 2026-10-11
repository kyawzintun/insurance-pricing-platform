import { AbstractControl, ValidationErrors } from '@angular/forms';

export function nonBlank(control: AbstractControl): ValidationErrors | null {
  return typeof control.value === 'string' && control.value.trim().length
    ? null
    : { required: true };
}
export function passwordBytes(control: AbstractControl): ValidationErrors | null {
  return typeof control.value === 'string' && new TextEncoder().encode(control.value).length > 72
    ? { passwordBytes: true }
    : null;
}
