import { Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs/operators';
import { AuthService } from '../../core/auth/auth.service';
import { messageErreurChamp } from '../../core/forms/messages-validation';
import { PAYS_OHADA_OUEST_AFRICAIN } from './models/auth.model';

/** Les deux mots de passe doivent correspondre. */
function motsDePasseIdentiques(groupe: AbstractControl): ValidationErrors | null {
  const motDePasse = groupe.get('motDePasse')?.value;
  const confirmation = groupe.get('confirmationMotDePasse')?.value;
  return motDePasse === confirmation ? null : { motsDePasseDifferents: true };
}

@Component({
  selector: 'app-register-cabinet',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './register-cabinet.component.html',
  styleUrl: './register-cabinet.component.scss',
})
export class RegisterCabinetComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly messageErreurChamp = messageErreurChamp;

  readonly paysDisponibles = PAYS_OHADA_OUEST_AFRICAIN;
  readonly envoiEnCours = signal(false);
  readonly erreur = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group(
    {
      raisonSocialeCabinet: ['', Validators.required],
      pays: ['CI', Validators.required],
      nom: ['', Validators.required],
      prenom: ['', Validators.required],
      email: ['', [Validators.required, Validators.email]],
      telephone: [''],
      motDePasse: ['', [Validators.required, Validators.minLength(8)]],
      confirmationMotDePasse: ['', Validators.required],
    },
    { validators: motsDePasseIdentiques },
  );

  soumettre(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.envoiEnCours.set(true);
    this.erreur.set(null);
    const { confirmationMotDePasse, ...request } = this.form.getRawValue();

    this.authService
      .registerCabinet(request)
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: () => this.router.navigateByUrl('/onboarding/entreprise'),
        error: (err) => {
          this.erreur.set(
            err.status === 409
              ? 'Cet email est déjà utilisé.'
              : (err.error?.message ?? 'Inscription impossible. Réessayez plus tard.'),
          );
        },
      });
  }
}
