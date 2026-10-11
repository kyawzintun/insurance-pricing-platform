import { Routes } from '@angular/router';
import { authGuard, guestGuard } from '@app/core/guards/auth.guard';
import { customerGuard } from '@app/core/guards/customer.guard';

export const routes: Routes = [
  {
    path: 'login',
    canActivate: [guestGuard],
    loadComponent: () =>
      import('@app/features/auth/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'register',
    canActivate: [guestGuard],
    loadComponent: () =>
      import('@app/features/auth/register/register.component').then((m) => m.RegisterComponent),
  },
  {
    path: 'dashboard',
    canActivate: [authGuard],
    loadComponent: () =>
      import('@app/features/dashboard/dashboard.component').then((m) => m.DashboardComponent),
  },
  {
    path: 'quotes/new',
    canActivate: [customerGuard],
    loadComponent: () =>
      import('@app/features/quotes/new/quote-new.component').then((m) => m.QuoteNewComponent),
  },
  {
    path: 'quotes',
    canActivate: [customerGuard],
    loadComponent: () =>
      import('@app/features/quotes/history/quote-history.component').then(
        (m) => m.QuoteHistoryComponent,
      ),
  },
  {
    path: 'quotes/:id',
    canActivate: [customerGuard],
    loadComponent: () =>
      import('@app/features/quotes/detail/quote-detail.component').then(
        (m) => m.QuoteDetailComponent,
      ),
  },
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  { path: '**', redirectTo: 'dashboard' },
];
