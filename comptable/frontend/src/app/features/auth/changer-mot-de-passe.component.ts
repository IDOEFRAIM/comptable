import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs/operators';
import { AuthService } from '../../core/auth/auth.service';
import { TokenStorageService } from '../../core/auth/token-storage.service';

/**
 * Changement de mot de passe. Obligatoire à la première connexion avec un mot de passe temporaire (ou après une réinitialisation) : tant qu'il n'est pas fait, le serveur refuse toute autre route. La politique
 * (12 caractères minimum, deux types de caractères) est contrôlée par le serveur ; l'écran n'en donne qu'un aperçu.
 */
@Component({
  selector: 'app-changer-mot-de-passe',
  standalone: true,
  imports: [ReactiveFormsModule],
  styleUrl: './login.component.scss',
  template: `
    <div class="page-login" data-zone="changer-mot-de-passe">
      <aside class="panneau-marque">
        <div class="panneau-contenu">
          <span class="marque"><span class="marque-icone" aria-hidden="true">C</span>Comptable</span>
          <h2>Choisissez votre mot de passe.</h2>
          <ul class="atouts"><li>12 caractères au minimum</li><li>Mélangez lettres, chiffres et symboles</li><li>Ne réutilisez pas un mot de passe d’un autre service</li></ul>
        </div>
      </aside>
      <div class="panneau-formulaire">
        <form [formGroup]="form" (ngSubmit)="soumettre()" class="carte-login">
          <h1>Nouveau mot de passe</h1>
          @if (obligatoire) { <p class="sous-titre" data-champ="motif-obligatoire">Votre mot de passe est temporaire : vous devez en choisir un nouveau pour continuer.</p> }
          <label class="champ"><span>Mot de passe actuel</span><input type="password" formControlName="actuel" autocomplete="current-password" data-champ="actuel" /></label>
          <label class="champ"><span>Nouveau mot de passe</span><input type="password" formControlName="nouveau" autocomplete="new-password" data-champ="nouveau" /></label>
          <label class="champ"><span>Confirmation</span><input type="password" formControlName="confirmation" autocomplete="new-password" data-champ="confirmation" /></label>
          @if (form.touched && form.controls.nouveau.value && form.controls.nouveau.value.length < 12) { <p class="message-erreur-champ" data-champ="erreur-longueur">12 caractères au minimum.</p> }
          @if (form.touched && form.controls.confirmation.value && form.controls.nouveau.value !== form.controls.confirmation.value) { <p class="message-erreur-champ" data-champ="erreur-confirmation">Les deux mots de passe ne correspondent pas.</p> }
          @if (erreur()) { <p class="message-erreur" role="alert" data-champ="erreur">{{ erreur() }}</p> }
          <button type="submit" class="bouton-principal" [disabled]="envoiEnCours() || !valide()" data-action="changer-mot-de-passe">{{ envoiEnCours() ? 'Enregistrement…' : 'Changer le mot de passe' }}</button>
        </form>
      </div>
    </div>
  `,
})
export class ChangerMotDePasseComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly tokens = inject(TokenStorageService);

  readonly obligatoire = this.tokens.doitChangerMotDePasse();
  readonly envoiEnCours = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly form = this.fb.nonNullable.group({
    actuel: ['', Validators.required],
    nouveau: ['', [Validators.required, Validators.minLength(12)]],
    confirmation: ['', Validators.required],
  });

  valide(): boolean {
    const v = this.form.getRawValue();
    return this.form.valid && v.nouveau === v.confirmation;
  }

  soumettre(): void {
    this.form.markAllAsTouched();
    if (!this.valide()) {
      return;
    }
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    const { actuel, nouveau } = this.form.getRawValue();
    this.auth
      .changerMotDePasse(actuel, nouveau)
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: () => {
          const roles = this.tokens.getRoles();
          this.router.navigateByUrl(roles.includes('ROLE_EXPERT_COMPTABLE') || roles.includes('ROLE_COLLABORATEUR') ? '/cabinet' : '/entreprises');
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Le mot de passe n’a pas pu être changé.'),
      });
  }
}
