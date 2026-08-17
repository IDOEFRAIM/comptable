import { ApplicationConfig } from '@angular/core';
import { provideRouter, withRouterConfig } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { routes } from './app.routes';
import { jwtInterceptor } from './core/auth/jwt.interceptor';
import { errorInterceptor } from './core/auth/error.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    // 'always' : les onglets (documents/tiers/ecritures/rapports) heritent de :entrepriseId
    // resolu par leur route parente, sans avoir a le repasser explicitement.
    provideRouter(routes, withRouterConfig({ paramsInheritanceStrategy: 'always' })),
    provideHttpClient(withInterceptors([jwtInterceptor, errorInterceptor])),
  ],
};
