import { Component, effect, inject, input, signal } from '@angular/core';
import { finalize } from 'rxjs/operators';
import { DetailLigneDto, TypeEtat } from './dossier-financier.model';
import { DossierFinancierService } from './dossier-financier.service';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { SectionHeaderComponent } from '../../shared/ui/section-header.component';
import { formaterMontant } from './montant';

const LIBELLES_ETAT: Record<TypeEtat, string> = {
  COMPTE_RESULTAT: 'Compte de résultat',
  BILAN_ACTIF: 'Bilan — Actif',
  BILAN_PASSIF: 'Bilan — Passif',
  TFT: 'Tableau des flux de trésorerie',
};

/**
 * « Pourquoi ce montant ? » : rubrique → comptes → soldes → écritures → lineage (facture, banque…) ; pour le TFT, les écritures et événements de trésorerie. Tout vient des endpoints de
 * drill-down existants ; rien n'est recalculé ici.
 */
@Component({
  selector: 'app-onglet-pourquoi',
  standalone: true,
  imports: [EmptyStateComponent, SectionHeaderComponent],
  template: `
    <section data-zone="pourquoi">
      @if (!selection()) {
        <app-empty-state icone="eye" titre="Aucune ligne sélectionnée" detail="Ouvrez le menu d’une ligne d’un état (⋯) pour comprendre son montant : comptes, soldes, écritures et origine." />
      } @else {
        <app-section-header titre="Détail du montant" [sousTitre]="libelleEtat(selection()!.type) + ' · ligne ' + selection()!.code" />
        @if (erreur()) {
          <p class="ui-notice ui-notice--danger">{{ erreur() }}</p>
        }
        @if (chargement()) {
          <p class="ui-meta">Chargement…</p>
        }
        @if (detail(); as d) {
          @if (d.accounts.length) {
            <h4 class="ui-eyebrow sous-section">Comptes composant la ligne</h4>
            <table class="ui-table">
              <thead><tr><th>Compte</th><th>Libellé</th><th>Rôle</th><th class="ui-num">Montant N</th><th></th></tr></thead>
              <tbody>
                @for (a of d.accounts; track a.accountId) {
                  <tr [attr.data-compte]="a.number">
                    <td class="ui-code">{{ a.number }}</td>
                    <td>{{ a.label }}</td>
                    <td>{{ a.role === 'DEPRECIATION' ? 'Amort. / dépréciation' : 'Brut' }}</td>
                    <td class="ui-num">{{ montant(a.amountN) }}</td>
                    @if (peutDetaillerCompte()) {
                      <td><button type="button" class="ui-btn ui-btn--link" (click)="voirCompte(a.accountId)">Voir les écritures</button></td>
                    } @else {
                      <td></td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          } @else if (!d.components.length) {
            <p class="ui-meta">Aucun compte ni écriture ne compose cette ligne (montant nul ou ligne calculée par formule).</p>
          }

          @if (d.components.length) {
            <h4 class="ui-eyebrow sous-section">Écritures et événements de trésorerie</h4>
            <table class="ui-table">
              <thead><tr><th>Date</th><th>Pièce</th><th>Événement</th><th class="ui-num">Montant</th><th>Origine</th></tr></thead>
              <tbody>
                @for (c of d.components; track c.ecritureId + c.ligneTft) {
                  <tr>
                    <td>{{ c.date }}</td>
                    <td class="ui-code">{{ c.numeroPiece }}</td>
                    <td>{{ c.evenement }}</td>
                    <td class="ui-num">{{ montant(c.montant) }}</td>
                    <td>
                      @for (l of c.lineage; track l.sourceId) {
                        <span class="ui-meta lineage">{{ l.sourceType }} / {{ l.sourceEvent }}</span>
                      } @empty {
                        <span class="ui-meta lineage">Saisie manuelle</span>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          }

          @if (d.account; as c) {
            <h4 class="ui-eyebrow sous-section">Compte {{ c.number }} — {{ c.label }}</h4>
            <p class="ui-meta lineage">Ouverture {{ montant(c.opening) }} · Mouvements débit {{ montant(c.movementDebit) }} / crédit {{ montant(c.movementCredit) }} · Clôture {{ montant(c.closing) }}</p>
            <table class="ui-table" data-zone="ecritures-compte">
              <thead><tr><th>Date</th><th>Journal</th><th>Pièce</th><th>Libellé</th><th class="ui-num">Débit</th><th class="ui-num">Crédit</th><th>Origine</th></tr></thead>
              <tbody>
                @for (e of c.entries; track e.ligneId) {
                  <tr>
                    <td>{{ e.date }}</td>
                    <td>{{ e.journal }}</td>
                    <td class="ui-code">{{ e.numeroPiece }}</td>
                    <td>{{ e.libelle }}</td>
                    <td class="ui-num">{{ montant(e.debit) }}</td>
                    <td class="ui-num">{{ montant(e.credit) }}</td>
                    <td>
                      @for (l of e.lineage; track l.sourceId) {
                        <span class="ui-meta lineage">{{ l.sourceType }} / {{ l.sourceEvent }}</span>
                      } @empty {
                        <span class="ui-meta lineage">Saisie manuelle</span>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          }
        }
      }
    </section>
  `,
  styles: [
    `
      .sous-section { margin: var(--ui-s5) 0 var(--ui-s2); }
      .lineage { display: block; }
    `,
  ],
})
export class OngletPourquoiComponent {
  private readonly service = inject(DossierFinancierService);

  readonly entrepriseId = input.required<string>();
  readonly exerciceId = input.required<string>();
  readonly selection = input<{ type: TypeEtat; code: string } | null>(null);

  readonly detail = signal<DetailLigneDto | null>(null);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);

  montant = formaterMontant;

  constructor() {
    effect(
      () => {
        const s = this.selection();
        if (s) {
          this.charger(s.type, s.code);
        } else {
          this.detail.set(null);
        }
      },
      { allowSignalWrites: true },
    );
  }

  libelleEtat(type: TypeEtat): string {
    return LIBELLES_ETAT[type];
  }

  /** Le détail par compte (écritures) n'existe que pour le Compte de résultat et le Bilan. */
  peutDetaillerCompte(): boolean {
    const s = this.selection();
    return !!s && s.type !== 'TFT';
  }

  private charger(type: TypeEtat, code: string, compteId?: string): void {
    this.chargement.set(true);
    this.erreur.set(null);
    this.service
      .detailLigne(this.entrepriseId(), this.exerciceId(), type, code, compteId)
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (d) => this.detail.set(d),
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de charger le détail de cette ligne.'),
      });
  }

  voirCompte(compteId: string): void {
    const s = this.selection();
    if (s) {
      this.charger(s.type, s.code, compteId);
    }
  }
}
