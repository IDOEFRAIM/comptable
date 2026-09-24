import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs/operators';
import { AuthService } from '../../core/auth/auth.service';
import { messageErreurChamp } from '../../core/forms/messages-validation';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly messageErreurChamp = messageErreurChamp;

  readonly envoiEnCours = signal(false);
  readonly erreur = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    motDePasse: ['', Validators.required],
  });

  soumettre(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.envoiEnCours.set(true);
    this.erreur.set(null);
    const { email, motDePasse } = this.form.getRawValue();

    this.authService
      .login(email, motDePasse)
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: () => this.router.navigateByUrl('/entreprises'),
        error: (err) => {
          this.erreur.set(
            err.status === 401 ? 'Identifiants invalides.' : 'Connexion impossible. Réessayez plus tard.',
          );
        },
      });
  }
}
