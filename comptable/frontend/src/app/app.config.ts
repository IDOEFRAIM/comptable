import { registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { ApplicationConfig, LOCALE_ID, isDevMode } from '@angular/core';
import { provideRouter, withRouterConfig } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { routes } from './app.routes';
import { jwtInterceptor } from './core/auth/jwt.interceptor';
import { errorInterceptor } from './core/auth/error.interceptor';
import { provideServiceWorker } from '@angular/service-worker';
import { cacheLecturesInterceptor } from './core/pwa/cache-lectures.interceptor';
import { horsLigneInterceptor } from './core/pwa/hors-ligne.interceptor';

// Interface en francais : dates, nombres (« 637 259 ») et monnaie (« F CFA ») suivent les usages francophones, jamais le format americain par defaut.
registerLocaleData(localeFr);

export const appConfig: ApplicationConfig = {
  providers: [
    { provide: LOCALE_ID, useValue: 'fr-FR' },
    // 'always' : les onglets (documents/tiers/ecritures/rapports) heritent de :entrepriseId
    // resolu par leur route parente, sans avoir a le repasser explicitement.
    provideRouter(routes, withRouterConfig({ paramsInheritanceStrategy: 'always' })),
    provideHttpClient(withInterceptors([horsLigneInterceptor, cacheLecturesInterceptor, jwtInterceptor, errorInterceptor])),
    // PWA : service worker actif uniquement dans le build de production (en HTTPS, ou sur localhost).
    provideServiceWorker('ngsw-worker.js', { enabled: !isDevMode(), registrationStrategy: 'registerWhenStable:30000' }),
  ],
};
