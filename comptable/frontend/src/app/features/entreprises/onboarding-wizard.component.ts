import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs/operators';
import { PAYS_OHADA_OUEST_AFRICAIN } from '../auth/models/auth.model';
import { EntrepriseService } from './entreprise.service';
import { REGIMES_FISCAUX_UEMOA } from './models/entreprise.model';

type EtapeOnboarding = 1 | 2 | 3;

/**
 * Wizard de premier demarrage (Sprint 8) : remplace l'ancien formulaire mono-etape
 * (onboarding-entreprise) par un parcours guide en 3 etapes juste apres l'inscription du
 * cabinet. Objectif pedagogique, pas juste fonctionnel : un comptable de terrain qui n'a jamais
 * vu d'IA doit comprendre CE QUI va se passer (extraction automatique, controle de fraude,
 * validation humaine) avant d'y etre confronte dans l'inbox.
 * <p>
 * L'etape 2 (connexion WhatsApp) est volontairement une simple page d'instructions : brancher un
 * numero WhatsApp Business reel passe par Meta Business Manager (hors de cette application), pas
 * par un flux OAuth qui n'existe pas ici - afficher un faux bouton "connecter" qui ne connecte
 * rien serait trompeur. L'etape 3 ne "teste" rien non plus automatiquement : le bouton final
 * emmene simplement vers l'inbox, ou la piece test envoyee par le comptable via son telephone
 * apparaitra une fois traitee.
 */
@Component({
  selector: 'app-onboarding-wizard',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './onboarding-wizard.component.html',
  styleUrl: './onboarding-wizard.component.scss',
})
export class OnboardingWizardComponent {
  private readonly fb = inject(FormBuilder);
  private readonly entrepriseService = inject(EntrepriseService);
  private readonly router = inject(Router);

  readonly paysDisponibles = PAYS_OHADA_OUEST_AFRICAIN;
  readonly regimesFiscaux = REGIMES_FISCAUX_UEMOA;

  readonly etape = signal<EtapeOnboarding>(1);
  readonly envoiEnCours = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly entrepriseId = signal<string | null>(null);
  readonly entrepriseNom = signal<string>('');

  readonly formEtape1 = this.fb.nonNullable.group({
    raisonSociale: ['', Validators.required],
    pays: ['CI', Validators.required],
    devise: ['XOF', Validators.required],
    regimeFiscal: ['REEL_NORMAL', Validators.required],
    secteurActivite: [''],
  });

  soumettreEtape1(): void {
    if (this.formEtape1.invalid) {
      this.formEtape1.markAllAsTouched();
      return;
    }

    this.envoiEnCours.set(true);
    this.erreur.set(null);

    this.entrepriseService
      .creer(this.formEtape1.getRawValue())
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (entreprise) => {
          this.entrepriseId.set(entreprise.id);
          this.entrepriseNom.set(entreprise.raisonSociale);
          this.etape.set(2);
        },
        error: (err) => this.erreur.set(err.error?.message ?? "Impossible de créer l'entreprise."),
      });
  }

  allerEtape3(): void {
    this.etape.set(3);
  }

  retourEtape(etape: EtapeOnboarding): void {
    this.etape.set(etape);
  }

  /** "Envoyer un reçu test" n'automatise rien depuis le serveur (ça reviendrait à envoyer un
   * message au nom du client, ce que rien ici n'autorise) : ça ouvre directement l'inbox, où la
   * pièce apparaîtra dès que le comptable aura réellement envoyé une photo par WhatsApp. */
  allerVersInbox(): void {
    const id = this.entrepriseId();
    if (id) {
      this.router.navigate(['/entreprises', id, 'validation']);
    }
  }

  passerPourLinstant(): void {
    const id = this.entrepriseId();
    this.router.navigate(id ? ['/entreprises', id, 'tableau-de-bord'] : ['/entreprises']);
  }
}
