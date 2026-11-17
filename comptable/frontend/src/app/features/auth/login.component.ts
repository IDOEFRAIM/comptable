import { Component, inject, signal } from '@angular/core';
import { ConfigPubliqueService } from '../../core/config/config-publique.service';
import { TokenStorageService } from '../../core/auth/token-storage.service';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs/operators';
import { AuthService } from '../../core/auth/auth.service';
import { CodePinService } from '../../core/auth/code-pin.service';
import { accueilPourRoles } from '../../core/auth/admin.guard';
import { HorsLigneService } from '../../core/pwa/hors-ligne.service';
import { InvitationInstallationComponent } from '../../core/pwa/invitation-installation.component';
import { messageErreurChamp } from '../../core/forms/messages-validation';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, InvitationInstallationComponent],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent {
  readonly config = inject(ConfigPubliqueService);
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly tokens = inject(TokenStorageService);
  private readonly pin = inject(CodePinService);
  readonly horsLigne = inject(HorsLigneService);

  protected readonly messageErreurChamp = messageErreurChamp;

  readonly envoiEnCours = signal(false);
  readonly erreur = signal<string | null>(null);

  /** Session expirée, pas d'Internet : l'accès aux dossiers déjà téléchargés reste possible (7 jours), derrière le code PIN s'il est défini. */
  readonly continuerPossible = () => !this.horsLigne.enLigne() && this.tokens.peutContinuerHorsLigne();
  readonly pinRequis = () => this.pin.defini();
  readonly codePin = signal('');
  readonly erreurPin = signal<string | null>(null);

  async continuerHorsLigne(): Promise<void> {
    this.erreurPin.set(null);
    const resultat = await this.pin.verifier(this.codePin());
    if (resultat === 'bloque') {
      this.tokens.clear();
      this.erreurPin.set('Trop d’essais : connectez-vous avec Internet pour continuer.');
      return;
    }
    if (resultat === 'incorrect') {
      this.erreurPin.set(`Code incorrect. Il reste ${this.pin.essaisRestants()} essai(s).`);
      return;
    }
    this.router.navigateByUrl(accueilPourRoles(this.tokens.getRoles()));
  }

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
          this.router.navigateByUrl(accueilPourRoles(this.tokens.getRoles()));
        },
        error: (err) => {
          this.erreur.set(err.status === 401 ? 'Identifiants invalides.' : err.status === 429 || err.status === 403 ? err.error?.message : 'Connexion impossible. Réessayez plus tard.');
        },
      });
  }
}
