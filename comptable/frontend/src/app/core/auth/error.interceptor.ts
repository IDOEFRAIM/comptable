import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { TokenStorageService } from './token-storage.service';

/** Un 401 signifie un token absent/expire/invalide : on nettoie et on renvoie au login. */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const tokenStorage = inject(TokenStorageService);
  const router = inject(Router);

  return next(req).pipe(
    catchError((error) => {
      if (error.status === 401) {
        tokenStorage.clear();
        router.navigateByUrl('/login');
      }
      return throwError(() => error);
    }),
  );
};
