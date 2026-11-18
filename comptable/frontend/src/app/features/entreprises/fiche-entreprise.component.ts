import { Component, OnInit, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite/comptabilite.service';
import { FicheEntreprise } from '../comptabilite/models/comptabilite.model';

/**
 * Fiche légale de l'entreprise : l'identité qui figure en tête de ses devis et factures (IFU, RCCM, adresse, coordonnées, mentions). Ce qui n'est pas renseigné apparaît « non renseigné » sur les
 * documents : le logiciel n'invente jamais une mention légale.
 */
@Component({
  selector: 'app-fiche-entreprise',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <div class="page">
      <h2>Fiche de l’entreprise</h2>
      <p class="intro">Ces informations figurent en tête de vos devis et factures. Vérifiez-les avec l’entreprise : ce sont des mentions légales.</p>

      @if (manquants().length > 0) {
        <p class="alerte" role="status" data-zone="fiche-manquants">À compléter : {{ manquants().join(', ') }}.</p>
      }
      @if (succes()) {
        <p class="succes" role="status" data-zone="fiche-enregistree">Fiche enregistrée.</p>
      }
      @if (erreur()) {
        <p class="erreur" role="alert">{{ erreur() }}</p>
      }

      <form [formGroup]="form" (ngSubmit)="enregistrer()" class="grille">
        <label class="champ large"><span>Raison sociale</span><input type="text" formControlName="raisonSociale" data-champ="raisonSociale" /></label>
        <label class="champ"><span>IFU (numéro d’identification fiscale)</span><input type="text" formControlName="numeroContribuable" data-champ="numeroContribuable" /></label>
        <label class="champ"><span>RCCM</span><input type="text" formControlName="rccm" data-champ="rccm" /></label>
        <label class="champ"><span>Forme juridique</span><input type="text" formControlName="formeJuridique" placeholder="SARL, SA, entreprise individuelle…" /></label>
        <label class="champ"><span>Régime fiscal</span><input type="text" formControlName="regimeFiscal" placeholder="RNI, RSI, CME…" /></label>
        <label class="champ large"><span>Adresse</span><input type="text" formControlName="adresse" data-champ="adresse" /></label>
        <label class="champ"><span>Téléphone</span><input type="text" formControlName="telephone" /></label>
        <label class="champ"><span>E-mail</span><input type="email" formControlName="email" /></label>
        <label class="champ large"><span>Coordonnées bancaires ou Mobile Money (pour le règlement)</span><input type="text" formControlName="coordonneesBancaires" /></label>
        <label class="champ large"><span>Mentions en bas de document (conditions de paiement, pénalités…)</span><textarea rows="3" formControlName="mentionsFacture"></textarea></label>
        <div class="actions large"><button type="submit" class="bouton-principal" [disabled]="enCours() || form.invalid" data-action="enregistrer-fiche">{{ enCours() ? 'Enregistrement…' : 'Enregistrer' }}</button></div>
      </form>
    </div>
  `,
  styles: [
    `
      .page { max-width: 820px; display: flex; flex-direction: column; gap: 0.9rem; }
      h2 { margin: 0; }
      .intro { margin: 0; color: var(--text-secondary, #475467); font-size: 0.875rem; }
      .grille { display: grid; grid-template-columns: 1fr 1fr; gap: 0.8rem; }
      .champ { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8125rem; color: var(--text-secondary, #475467); }
      .large { grid-column: 1 / -1; }
      input, textarea { padding: 0.5rem 0.6rem; border: 1px solid var(--border-default, #d0d5dd); border-radius: 8px; font: inherit; }
      .alerte { background: #fffaeb; border: 1px solid #f2b705; border-radius: 8px; padding: 0.55rem 0.8rem; margin: 0; font-size: 0.875rem; }
      .succes { color: #14662f; margin: 0; font-size: 0.875rem; }
      .erreur { color: #b42318; margin: 0; font-size: 0.875rem; }
      @media (max-width: 640px) { .grille { grid-template-columns: 1fr; } }
    `,
  ],
})
export class FicheEntrepriseComponent implements OnInit {
  private readonly service = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), { initialValue: '' });
  readonly manquants = signal<string[]>([]);
  readonly enCours = signal(false);
  readonly succes = signal(false);
  readonly erreur = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    raisonSociale: ['', Validators.required],
    numeroContribuable: [''],
    rccm: [''],
    formeJuridique: [''],
    regimeFiscal: [''],
    adresse: [''],
    telephone: [''],
    email: [''],
    coordonneesBancaires: [''],
    mentionsFacture: [''],
  });

  ngOnInit(): void {
    this.service.ficheEntreprise(this.entrepriseId()).subscribe({
      next: (f) => this.afficher(f),
      error: () => this.erreur.set('La fiche n’a pas pu être chargée. Vérifiez votre connexion.'),
    });
  }

  private afficher(f: FicheEntreprise): void {
    this.form.setValue({
      raisonSociale: f.raisonSociale ?? '',
      numeroContribuable: f.numeroContribuable ?? '',
      rccm: f.rccm ?? '',
      formeJuridique: f.formeJuridique ?? '',
      regimeFiscal: f.regimeFiscal ?? '',
      adresse: f.adresse ?? '',
      telephone: f.telephone ?? '',
      email: f.email ?? '',
      coordonneesBancaires: f.coordonneesBancaires ?? '',
      mentionsFacture: f.mentionsFacture ?? '',
    });
    this.manquants.set(f.manquants ?? []);
  }

  enregistrer(): void {
    if (this.form.invalid) {
      return;
    }
    this.enCours.set(true);
    this.succes.set(false);
    this.erreur.set(null);
    const v = this.form.getRawValue();
    this.service
      .modifierFicheEntreprise(this.entrepriseId(), {
        ...v,
        numeroContribuable: v.numeroContribuable || null,
        rccm: v.rccm || null,
        formeJuridique: v.formeJuridique || null,
        regimeFiscal: v.regimeFiscal || null,
        adresse: v.adresse || null,
        telephone: v.telephone || null,
        email: v.email || null,
        coordonneesBancaires: v.coordonneesBancaires || null,
        mentionsFacture: v.mentionsFacture || null,
      })
      .subscribe({
        next: (f) => {
          this.enCours.set(false);
          this.afficher(f);
          this.succes.set(true);
        },
        error: (e) => {
          this.enCours.set(false);
          this.erreur.set(e.error?.message ?? 'La fiche n’a pas pu être enregistrée.');
        },
      });
  }
}
