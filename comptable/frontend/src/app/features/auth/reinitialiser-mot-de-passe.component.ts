import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs/operators';
import { AuthService } from '../../core/auth/auth.service';

/** Réinitialisation du mot de passe avec le jeton à usage unique remis par l'expert-comptable du cabinet (lien reçu hors application : aucun e-mail n'est supposé envoyé par la plateforme). */
@Component({
  selector: 'app-reinitialiser-mot-de-passe',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  styleUrl: './login.component.scss',
  template: `
    <div class="page-login" data-zone="reinitialiser-mot-de-passe">
      <aside class="panneau-marque">
        <div class="panneau-contenu">
          <span class="marque"><span class="marque-icone" aria-hidden="true">C</span>Comptable</span>
          <h2>Réinitialisation du mot de passe.</h2>
          <ul class="atouts"><li>Le lien est à usage unique</li><li>Il expire au bout de 24 heures</li></ul>
        </div>
      </aside>
      <div class="panneau-formulaire">
        @if (termine()) {
          <div class="carte-login" data-champ="reinitialisation-reussie">
            <h1>Mot de passe modifié</h1>
            <p class="sous-titre">Vous pouvez maintenant vous connecter avec votre nouveau mot de passe.</p>
            <a class="bouton-principal" routerLink="/login">Se connecter</a>
          </div>
        } @else {
          <form [formGroup]="form" (ngSubmit)="soumettre()" class="carte-login">
            <h1>Nouveau mot de passe</h1>
            @if (!jeton) { <p class="message-erreur" role="alert" data-champ="jeton-absent">Ce lien est incomplet. Demandez un nouveau lien à l’expert-comptable de votre cabinet.</p> }
            <label class="champ"><span>Nouveau mot de passe</span><input type="password" formControlName="nouveau" autocomplete="new-password" data-champ="nouveau" /></label>
            <label class="champ"><span>Confirmation</span><input type="password" formControlName="confirmation" autocomplete="new-password" data-champ="confirmation" /></label>
            @if (form.touched && form.controls.confirmation.value && form.controls.nouveau.value !== form.controls.confirmation.value) { <p class="message-erreur-champ" data-champ="erreur-confirmation">Les deux mots de passe ne correspondent pas.</p> }
            @if (erreur()) { <p class="message-erreur" role="alert" data-champ="erreur">{{ erreur() }}</p> }
            <button type="submit" class="bouton-principal" [disabled]="envoiEnCours() || !valide()" data-action="reinitialiser">{{ envoiEnCours() ? 'Enregistrement…' : 'Enregistrer' }}</button>
          </form>
        }
      </div>
    </div>
  `,
})
export class ReinitialiserMotDePasseComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  protected readonly router = inject(Router);

  readonly jeton = this.route.snapshot.queryParamMap.get('jeton') ?? '';
  readonly envoiEnCours = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly termine = signal(false);
  readonly form = this.fb.nonNullable.group({
    nouveau: ['', [Validators.required, Validators.minLength(12)]],
    confirmation: ['', Validators.required],
  });

  valide(): boolean {
    const v = this.form.getRawValue();
    return !!this.jeton && this.form.valid && v.nouveau === v.confirmation;
  }

  soumettre(): void {
    this.form.markAllAsTouched();
    if (!this.valide()) {
      return;
    }
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    this.auth
      .reinitialiserMotDePasse(this.jeton, this.form.getRawValue().nouveau)
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({ next: () => this.termine.set(true), error: (err) => this.erreur.set(err.error?.message ?? 'Le mot de passe n’a pas pu être réinitialisé.') });
  }
}
