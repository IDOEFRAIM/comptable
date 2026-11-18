import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs/operators';
import { messageErreur } from '../../../core/forms/message-erreur';
import { DocumentHubService } from '../document-hub.service';
import { LigneHubDto } from '../models/document-hub.model';

/**
 * Quand la lecture automatique n'a pas pu lire une pièce (photo floue, service indisponible), le comptable saisit lui-même
 * l'essentiel au lieu de rester bloqué : fournisseur, date, numéro, montants. La TVA se déduit de HT et TTC.
 */
@Component({
  selector: 'app-completer-piece',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <div class="fond" (click)="annule.emit()">
      <form [formGroup]="form" (ngSubmit)="soumettre()" class="carte-modale" (click)="$event.stopPropagation()" data-zone="completer-piece">
        <h3>Compléter la pièce</h3>
        <p class="intro">{{ ligne().motifLecture ?? 'Saisissez les informations lues sur le document.' }} Ouvrez la pièce pour la lire, puis recopiez l’essentiel.</p>

        <label class="champ">
          <span>Fournisseur / client</span>
          <input type="text" formControlName="fournisseur" data-champ="fournisseur" />
        </label>
        <div class="deux">
          <label class="champ">
            <span>Date de la pièce</span>
            <input type="date" formControlName="datePiece" data-champ="datePiece" />
          </label>
          <label class="champ">
            <span>N° de facture</span>
            <input type="text" formControlName="numeroFacture" data-champ="numeroFacture" />
          </label>
        </div>
        <div class="trois">
          <label class="champ">
            <span>Montant HT</span>
            <input type="number" min="0" step="any" formControlName="montantHt" data-champ="montantHt" />
          </label>
          <label class="champ">
            <span>TVA</span>
            <input type="number" min="0" step="any" formControlName="montantTva" data-champ="montantTva" placeholder="déduite" />
          </label>
          <label class="champ">
            <span>Montant TTC</span>
            <input type="number" min="0" step="any" formControlName="montantTtc" data-champ="montantTtc" />
          </label>
        </div>
        <p class="astuce">Laissez la TVA vide : elle est calculée (TTC − HT). Les montants sont en {{ ligne().devise ?? 'XOF' }}.</p>

        @if (erreur()) {
          <p class="message-erreur" role="alert" data-zone="erreur-completer">{{ erreur() }}</p>
        }
        <div class="actions">
          <button type="button" class="bouton-secondaire" (click)="annule.emit()">Annuler</button>
          <button type="submit" class="bouton-principal" [disabled]="envoiEnCours()" data-action="enregistrer-completer">{{ envoiEnCours() ? 'Enregistrement…' : 'Enregistrer' }}</button>
        </div>
      </form>
    </div>
  `,
  styles: [
    `
      .fond { position: fixed; inset: 0; background: rgba(16, 24, 40, 0.45); display: flex; align-items: center; justify-content: center; z-index: 50; padding: 1rem; }
      .carte-modale { background: #fff; border-radius: 12px; padding: 1.4rem; width: 100%; max-width: 560px; max-height: 92vh; overflow: auto; display: flex; flex-direction: column; gap: 0.7rem; }
      h3 { margin: 0; }
      .intro { margin: 0; font-size: 0.875rem; color: var(--text-secondary, #475467); line-height: 1.4; }
      .champ { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.875rem; font-weight: 600; flex: 1; }
      .champ input { padding: 0.5rem 0.6rem; border: 1px solid var(--border-subtle, #d0d5dd); border-radius: 8px; font: inherit; font-weight: 400; }
      .deux, .trois { display: flex; gap: 0.7rem; }
      .astuce { margin: 0; font-size: 0.8125rem; color: var(--text-tertiary, #667085); }
      .message-erreur { margin: 0; color: #b42318; background: #fef3f2; border: 1px solid #fda29b; border-radius: 8px; padding: 0.55rem 0.7rem; font-size: 0.875rem; }
      .actions { display: flex; justify-content: flex-end; gap: 0.6rem; }
      @media (max-width: 520px) { .deux, .trois { flex-direction: column; } }
    `,
  ],
})
export class CompleterPieceComponent implements OnInit {
  entrepriseId = input.required<string>();
  ligne = input.required<LigneHubDto>();
  enregistre = output<void>();
  annule = output<void>();

  private readonly fb = inject(FormBuilder);
  private readonly hub = inject(DocumentHubService);

  readonly envoiEnCours = signal(false);
  readonly erreur = signal<string | null>(null);

  readonly form = this.fb.group({
    fournisseur: [''],
    datePiece: [''],
    numeroFacture: [''],
    montantHt: [null as number | null, [Validators.min(0)]],
    montantTva: [null as number | null, [Validators.min(0)]],
    montantTtc: [null as number | null, [Validators.min(0)]],
  });

  ngOnInit(): void {
    const l = this.ligne();
    this.form.patchValue({
      fournisseur: l.tiersNom ?? l.fournisseurExtrait ?? '',
      datePiece: l.datePiece ?? '',
      numeroFacture: l.numeroFacture ?? '',
      montantTtc: l.montantTtc,
    });
  }

  soumettre(): void {
    const v = this.form.getRawValue();
    const corps = {
      fournisseur: v.fournisseur?.trim() || undefined,
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
