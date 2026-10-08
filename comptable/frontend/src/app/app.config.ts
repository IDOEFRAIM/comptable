import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { ApplicationConfig, LOCALE_ID } from '@angular/core';
import { provideRouter, withRouterConfig } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { routes } from './app.routes';
import { jwtInterceptor } from './core/auth/jwt.interceptor';
import { errorInterceptor } from './core/auth/error.interceptor';

// Interface en francais : dates, nombres (« 637 259 ») et monnaie (« F CFA ») suivent les usages francophones, jamais le format americain par defaut.
registerLocaleData(localeFr);

export const appConfig: ApplicationConfig = {
  providers: [
    { provide: LOCALE_ID, useValue: 'fr-FR' },
    // 'always' : les onglets (documents/tiers/ecritures/rapports) heritent de :entrepriseId
    // resolu par leur route parente, sans avoir a le repasser explicitement.
    provideRouter(routes, withRouterConfig({ paramsInheritanceStrategy: 'always' })),
    provideHttpClient(withInterceptors([jwtInterceptor, errorInterceptor])),
  ],
};
