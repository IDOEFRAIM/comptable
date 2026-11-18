import { DecimalPipe } from '@angular/common';
import { Component, OnDestroy, OnInit, inject, input, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { finalize } from 'rxjs/operators';
import { messageErreur } from '../../../core/forms/message-erreur';
import { ComptabiliteService } from '../../comptabilite/comptabilite.service';
import { TiersDto } from '../../comptabilite/models/comptabilite.model';
import { DocumentHubService } from '../document-hub.service';
import { LigneHubDto } from '../models/document-hub.model';

const AUTRE = '__autre__';

/**
 * Quand la lecture automatique n'a pas pu lire une pièce (photo floue, service indisponible), le comptable la complète sans
 * quitter l'écran : la pièce s'affiche À CÔTÉ du formulaire (rien à ouvrir dans un autre onglet), le fournisseur se CHOISIT dans la
 * liste (pas de faute de frappe possible) et la TVA se déduit de HT et TTC.
 */
@Component({
  selector: 'app-completer-piece',
  standalone: true,
  imports: [ReactiveFormsModule, DecimalPipe],
  template: `
    <div class="fond" (click)="annule.emit()">
      <div class="fenetre" role="dialog" aria-modal="true" aria-labelledby="titre-completer" (click)="$event.stopPropagation()" data-zone="completer-piece">
        <header class="entete">
          <div>
            <h3 id="titre-completer">Compléter la pièce</h3>
            <p class="motif">{{ ligne().motifLecture ?? 'Recopiez les informations lues sur le document.' }}</p>
          </div>
          <button type="button" class="fermer" (click)="annule.emit()" aria-label="Fermer">×</button>
        </header>

        <div class="corps">
          <section class="apercu" aria-label="Aperçu de la pièce">
            @if (chargementApercu()) {
              <p class="etat">Chargement de la pièce…</p>
            } @else if (urlImage()) {
              <img [src]="urlImage()" alt="Pièce à compléter" (click)="agrandir()" title="Cliquer pour ouvrir en grand" />
            } @else if (urlPdf()) {
              <iframe [src]="urlPdf()" title="Pièce à compléter"></iframe>
            } @else {
              <p class="etat">Aperçu indisponible : utilisez « Ouvrir » sur la carte.</p>
            }
          </section>

          <form [formGroup]="form" (ngSubmit)="soumettre()" class="formulaire">
            <label class="champ">
              <span>{{ libelleTiers() }}</span>
              <select formControlName="tiersChoix" data-champ="tiers">
                <option value="">Sélectionner…</option>
                @for (t of tiers(); track t.id) {
                  <option [value]="t.id">{{ t.nom }}</option>
                }
                <option [value]="autre">Autre — saisir le nom…</option>
              </select>
              @if (tiers().length === 0 && !chargementTiers()) {
                <span class="astuce">Aucun {{ libelleTiers().toLowerCase() }} enregistré : saisissez son nom, vous pourrez le créer ensuite dans « Tiers ».</span>
              }
            </label>
            @if (form.controls.tiersChoix.value === autre) {
              <label class="champ">
                <span>Nom du {{ libelleTiers().toLowerCase() }}</span>
                <input type="text" formControlName="nouveauNom" data-champ="fournisseur" placeholder="Nom tel qu’écrit sur la pièce" />
              </label>
            }

            <div class="rangee">
              <label class="champ">
                <span>Date de la pièce</span>
                <input type="date" formControlName="datePiece" data-champ="datePiece" />
              </label>
              <label class="champ">
                <span>N° de facture</span>
                <input type="text" formControlName="numeroFacture" data-champ="numeroFacture" />
              </label>
            </div>

            <div class="rangee trois">
              <label class="champ">
                <span>Montant HT</span>
                <input type="number" inputmode="decimal" min="0" step="any" formControlName="montantHt" data-champ="montantHt" />
              </label>
              <label class="champ">
                <span>TVA</span>
                <input type="number" inputmode="decimal" min="0" step="any" formControlName="montantTva" data-champ="montantTva"
                  [placeholder]="tvaDeduite() !== null ? 'déduite : ' + (tvaDeduite() | number: '1.0-2') : 'déduite'" />
              </label>
              <label class="champ">
                <span>Montant TTC</span>
                <input type="number" inputmode="decimal" min="0" step="any" formControlName="montantTtc" data-champ="montantTtc" />
              </label>
            </div>
            <p class="astuce">Montants en {{ ligne().devise ?? 'XOF' }}. Laissez la TVA vide : elle se calcule (TTC − HT).</p>

            @if (erreur()) {
              <p class="message-erreur" role="alert" data-zone="erreur-completer">{{ erreur() }}</p>
            }
            <div class="actions">
              <button type="button" class="bouton-secondaire" (click)="annule.emit()">Annuler</button>
              <button type="submit" class="bouton-principal" [disabled]="envoiEnCours()" data-action="enregistrer-completer">{{ envoiEnCours() ? 'Enregistrement…' : 'Enregistrer' }}</button>
            </div>
          </form>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .fond { position: fixed; inset: 0; background: rgba(16, 24, 40, 0.5); display: flex; align-items: center; justify-content: center; z-index: 50; padding: 1rem; }
      .fenetre { background: #fff; border-radius: 14px; width: 100%; max-width: 1040px; max-height: 94vh; display: flex; flex-direction: column; overflow: hidden; box-shadow: 0 20px 50px rgba(16, 24, 40, 0.25); }
      .entete { display: flex; justify-content: space-between; align-items: flex-start; gap: 1rem; padding: 1.1rem 1.4rem 0.9rem; border-bottom: 1px solid var(--border-subtle, #eaecf0); }
      h3 { margin: 0 0 0.25rem; font-size: 1.15rem; }
      .motif { margin: 0; font-size: 0.85rem; color: #8a5a00; }
      .fermer { border: none; background: transparent; font-size: 1.6rem; line-height: 1; color: var(--text-tertiary, #667085); cursor: pointer; padding: 0 0.3rem; border-radius: 6px; }
      .fermer:hover { background: var(--bg-subtle-hover, #f2f4f7); }
      .corps { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(320px, 1fr); min-height: 0; overflow: auto; }
      .apercu { background: #f2f4f7; display: flex; align-items: flex-start; justify-content: center; padding: 0.8rem; min-height: 260px; overflow: auto; max-height: calc(94vh - 90px); }
      .apercu img { max-width: 100%; height: auto; border-radius: 6px; box-shadow: 0 1px 4px rgba(16, 24, 40, 0.2); cursor: zoom-in; }
      .apercu iframe { width: 100%; height: 70vh; border: none; border-radius: 6px; background: #fff; }
      .etat { margin: auto; color: var(--text-tertiary, #667085); font-size: 0.875rem; text-align: center; }
      .formulaire { padding: 1.1rem 1.4rem 1.2rem; display: flex; flex-direction: column; gap: 0.85rem; overflow: auto; max-height: calc(94vh - 90px); }
      .champ { display: flex; flex-direction: column; gap: 0.3rem; font-size: 0.8125rem; font-weight: 600; color: var(--text-secondary, #475467); flex: 1; min-width: 0; }
      .champ input, .champ select { padding: 0.55rem 0.65rem; border: 1px solid var(--border-subtle, #d0d5dd); border-radius: 8px; font: inherit; font-size: 0.9375rem; font-weight: 400; color: var(--text-primary, #101828); background: #fff; }
      .champ input:focus, .champ select:focus { outline: 2px solid var(--color-primary, #14662f); outline-offset: 1px; border-color: transparent; }
      .rangee { display: flex; gap: 0.7rem; }
      .astuce { margin: 0; font-size: 0.78rem; font-weight: 400; color: var(--text-tertiary, #667085); }
      .message-erreur { margin: 0; color: #b42318; background: #fef3f2; border: 1px solid #fda29b; border-radius: 8px; padding: 0.55rem 0.7rem; font-size: 0.875rem; }
      .actions { display: flex; justify-content: flex-end; gap: 0.6rem; margin-top: 0.2rem; }
      @media (max-width: 820px) {
        .corps { grid-template-columns: 1fr; }
        .apercu { max-height: 38vh; min-height: 160px; }
        .formulaire { max-height: none; }
        .rangee { flex-direction: column; }
        .rangee.trois { flex-direction: row; }
        .rangee.trois .champ:nth-child(2) { display: none; }
      }
    `,
  ],
})
export class CompleterPieceComponent implements OnInit, OnDestroy {
  entrepriseId = input.required<string>();
  ligne = input.required<LigneHubDto>();
  enregistre = output<void>();
  annule = output<void>();

  private readonly fb = inject(FormBuilder);
  private readonly hub = inject(DocumentHubService);
  private readonly comptabilite = inject(ComptabiliteService);
  private readonly sanitizer = inject(DomSanitizer);

  readonly autre = AUTRE;
  readonly envoiEnCours = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly tiers = signal<TiersDto[]>([]);
  readonly chargementTiers = signal(true);
  readonly chargementApercu = signal(true);
  readonly urlImage = signal<string | null>(null);
  readonly urlPdf = signal<SafeResourceUrl | null>(null);
  private urlObjet: string | null = null;

  readonly form = this.fb.group({
    tiersChoix: [''],
    nouveauNom: [''],
    datePiece: [''],
    numeroFacture: [''],
    montantHt: [null as number | null, [Validators.min(0)]],
    montantTva: [null as number | null, [Validators.min(0)]],
    montantTtc: [null as number | null, [Validators.min(0)]],
  });

  libelleTiers(): string {
    return this.ligne().type === 'FACTURE_VENTE' ? 'Client' : 'Fournisseur';
  }

  /** TVA qui sera enregistrée si le champ reste vide : TTC − HT (jamais négative). */
  tvaDeduite(): number | null {
    const { montantHt, montantTtc, montantTva } = this.form.getRawValue();
    if (montantTva !== null && montantTva !== undefined) {
      return null;
    }
    if (montantHt === null || montantTtc === null || montantHt === undefined || montantTtc === undefined) {
      return null;
    }
    const tva = montantTtc - montantHt;
    return tva >= 0 ? tva : null;
  }

  ngOnInit(): void {
    const l = this.ligne();
    this.form.patchValue({
      datePiece: l.datePiece ?? '',
      numeroFacture: l.numeroFacture ?? '',
      montantTtc: l.montantTtc,
    });

    const type = l.type === 'FACTURE_VENTE' ? 'CLIENT' : 'FOURNISSEUR';
    this.comptabilite
      .listerTiers(this.entrepriseId(), type)
      .pipe(finalize(() => this.chargementTiers.set(false)))
      .subscribe({
        next: (liste) => {
          this.tiers.set(liste);
          this.preselectionnerTiers(liste);
        },
        error: () => this.preselectionnerTiers([]),
      });

    this.hub
      .fichier(this.entrepriseId(), l.id)
      .pipe(finalize(() => this.chargementApercu.set(false)))
      .subscribe({
        next: (blob) => {
          this.urlObjet = URL.createObjectURL(blob);
          if (blob.type === 'application/pdf') {
            this.urlPdf.set(this.sanitizer.bypassSecurityTrustResourceUrl(this.urlObjet));
          } else {
            this.urlImage.set(this.urlObjet);
          }
        },
        error: () => undefined, // l'aperçu est un confort : la saisie reste possible sans lui
      });
  }

  ngOnDestroy(): void {
    if (this.urlObjet) {
      URL.revokeObjectURL(this.urlObjet);
    }
  }

  /** Reprend le tiers déjà identifié, sinon celui dont le nom correspond au nom lu, sinon propose de saisir le nom lu. */
  private preselectionnerTiers(liste: TiersDto[]): void {
    const l = this.ligne();
    const lu = (l.tiersNom ?? l.fournisseurExtrait ?? '').trim();
    const connu = (l.tiersId ? liste.find((t) => t.id === l.tiersId) : undefined) ?? (lu ? liste.find((t) => t.nom.trim().toLowerCase() === lu.toLowerCase()) : undefined);
    if (connu) {
      this.form.patchValue({ tiersChoix: connu.id });
    } else if (lu) {
      this.form.patchValue({ tiersChoix: AUTRE, nouveauNom: lu });
    }
  }

  agrandir(): void {
    if (this.urlImage()) {
      window.open(this.urlImage()!, '_blank');
    }
  }

  soumettre(): void {
    const v = this.form.getRawValue();
    const choisi = v.tiersChoix && v.tiersChoix !== AUTRE ? v.tiersChoix : undefined;
    const nom = v.tiersChoix === AUTRE ? v.nouveauNom?.trim() || undefined : undefined;
    const corps = {
      tiersId: choisi,
      fournisseur: nom,
      datePiece: v.datePiece || undefined,
      numeroFacture: v.numeroFacture?.trim() || undefined,
      montantHt: v.montantHt ?? undefined,
      montantTva: v.montantTva ?? undefined,
      montantTtc: v.montantTtc ?? undefined,
    };
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    this.hub
      .corrigerDonnees(this.entrepriseId(), this.ligne().id, corps)
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: () => this.enregistre.emit(),
        error: (err) => this.erreur.set(messageErreur(err, 'Enregistrement impossible.')),
      });
  }
}
