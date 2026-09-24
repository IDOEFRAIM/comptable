import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AccountingOperationProposalDto } from '../models/operations.model';

/** Apercu d'une proposition d'ecriture (comptes/debit/credit + explications/warnings) avant
 * comptabilisation reelle - le comptable doit voir les comptes proposes avant de valider, jamais
 * une ecriture deja validee sans son intervention. Composant dumb : toute action est emise vers le
 * parent, qui seul appelle le backend. */
@Component({
  selector: 'app-proposition-preview',
  standalone: true,
  template: `
    @if (proposition) {
      <div class="apercu-proposition">
        <div class="entete-apercu">
          <h3>Proposition d'écriture</h3>
          <span
            class="badge-statut"
            [class.statut-proposed]="proposition.statut === 'PROPOSED'"
            [class.statut-needs-review]="proposition.statut === 'NEEDS_REVIEW'"
            [class.statut-approved]="proposition.statut === 'APPROVED'"
            [class.statut-rejected]="proposition.statut === 'REJECTED'"
          >
            {{ libelleStatut(proposition.statut) }}
          </span>
        </div>

        <table class="tableau-lignes">
          <thead>
            <tr>
              <th>Compte</th>
              <th>Libellé</th>
              <th class="colonne-montant">Débit</th>
              <th class="colonne-montant">Crédit</th>
            </tr>
          </thead>
          <tbody>
            @for (ligne of proposition.lignes; track ligne.compteId + ligne.debit + ligne.credit) {
              <tr>
                <td>{{ ligne.compteNumero }} — {{ ligne.compteIntitule }}</td>
                <td>{{ ligne.libelle }}</td>
                <td class="colonne-montant">{{ ligne.debit > 0 ? (ligne.debit | number: '1.0-2') : '' }}</td>
                <td class="colonne-montant">{{ ligne.credit > 0 ? (ligne.credit | number: '1.0-2') : '' }}</td>
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <td colspan="2">Total</td>
              <td class="colonne-montant">{{ proposition.totalDebit | number: '1.0-2' }}</td>
              <td class="colonne-montant">{{ proposition.totalCredit | number: '1.0-2' }}</td>
            </tr>
          </tfoot>
        </table>

        <p class="indicateur-equilibre" [class.equilibree]="proposition.equilibree" [class.desequilibree]="!proposition.equilibree">
          {{ proposition.equilibree ? '✓ Écriture équilibrée' : '✗ Écriture non équilibrée' }}
        </p>

        @if (proposition.explications.length > 0) {
          <div class="bloc-explications">
            <p class="titre-bloc">Cette proposition nécessite une revue :</p>
            <ul>
              @for (explication of proposition.explications; track explication) {
                <li>{{ explication }}</li>
              }
            </ul>
          </div>
        }

        @if (proposition.warnings.length > 0) {
          <div class="bloc-warnings">
            <p class="titre-bloc">Avertissements :</p>
            <ul>
              @for (warning of proposition.warnings; track warning) {
                <li>{{ warning }}</li>
              }
            </ul>
          </div>
        }

        @if (proposition.statut === 'PROPOSED' || proposition.statut === 'NEEDS_REVIEW') {
          <div class="actions-proposition">
            @if (proposition.statut === 'PROPOSED') {
              <button type="button" class="bouton-principal" [disabled]="enCours" (click)="valider.emit()">
                Valider
              </button>
            }
            <button type="button" class="bouton-secondaire" [disabled]="enCours" (click)="modifier.emit()">
              Modifier (saisie manuelle)
            </button>
            <button type="button" class="bouton-lien bouton-rejeter" [disabled]="enCours" (click)="demanderRejet()">
              Rejeter
            </button>
          </div>
          @if (afficherMotifRejet) {
            <div class="ligne-champs">
              <label class="champ">
                Motif du rejet
                <input type="text" [(ngModel)]="motifRejet" placeholder="Ex. compte incorrect" />
              </label>
              <button
                type="button"
                class="bouton-principal"
                [disabled]="!motifRejet.trim() || enCours"
                (click)="confirmerRejet()"
              >
                Confirmer le rejet
              </button>
            </div>
          }
        }

        @if (proposition.statut === 'APPROVED') {
          <p class="message-succes">Écriture comptabilisée{{ proposition.ecritureId ? ' (n° ' + proposition.ecritureId.slice(0, 8) + '…)' : '' }}.</p>
        }
        @if (proposition.statut === 'REJECTED') {
          <p class="message-info">Proposition rejetée{{ proposition.rejectionReason ? ' : ' + proposition.rejectionReason : '' }}.</p>
        }
      </div>
    }
  `,
  styleUrl: './proposition-preview.component.scss',
  imports: [CommonModule, FormsModule],
})
export class PropositionPreviewComponent {
  @Input({ required: true }) proposition!: AccountingOperationProposalDto | null;
  @Input() enCours = false;

  @Output() readonly valider = new EventEmitter<void>();
  @Output() readonly rejeter = new EventEmitter<string>();
  @Output() readonly modifier = new EventEmitter<void>();

  afficherMotifRejet = false;
  motifRejet = '';

  libelleStatut(statut: string): string {
    switch (statut) {
      case 'PROPOSED':
        return 'À valider';
      case 'NEEDS_REVIEW':
        return 'Incomplète — revue nécessaire';
      case 'APPROVED':
        return 'Comptabilisée';
      case 'REJECTED':
        return 'Rejetée';
      default:
        return statut;
    }
  }

  demanderRejet(): void {
    this.afficherMotifRejet = true;
  }

  confirmerRejet(): void {
    if (!this.motifRejet.trim()) {
      return;
    }
    this.rejeter.emit(this.motifRejet.trim());
    this.afficherMotifRejet = false;
    this.motifRejet = '';
  }
}
