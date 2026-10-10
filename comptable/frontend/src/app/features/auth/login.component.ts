import { Component, inject, signal } from '@angular/core';
import { ConfigPubliqueService } from '../../core/config/config-publique.service';
import { TokenStorageService } from '../../core/auth/token-storage.service';
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
  readonly config = inject(ConfigPubliqueService);
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly tokens = inject(TokenStorageService);

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
        // un membre du cabinet arrive sur son espace de travail (Cabinet Workspace) ; un client sur la liste de ses entreprises
        next: (reponse) => {
          if (reponse.mustChangePassword) {
            this.router.navigateByUrl('/changer-mot-de-passe');
            return;
          }
          const roles = this.tokens.getRoles();
          const cabinet = roles.includes('ROLE_EXPERT_COMPTABLE') || roles.includes('ROLE_COLLABORATEUR');
          this.router.navigateByUrl(cabinet ? '/cabinet' : '/entreprises');
        },
        error: (err) => {
          this.erreur.set(err.status === 401 ? 'Identifiants invalides.' : err.status === 429 ? err.error?.message : 'Connexion impossible. Réessayez plus tard.');
        },
      });
  }
}
