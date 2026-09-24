import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, OnChanges, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite.service';
import { ArticleDto } from '../models/comptabilite.model';
import { InventaireDto, LIBELLES_STATUT_INVENTAIRE } from '../models/inventaire.model';

/** Ecran de saisie/validation d'un inventaire : theorique/compte/ecart par article, puis
 * Valider (calcule les ecarts, enregistre les mouvements de regularisation et genere une
 * proposition d'ecriture via AccountingOperationEngine - jamais une ecriture directement) et
 * Cloturer (n'est possible qu'une fois cette proposition validee par un comptable depuis l'ecran
 * Operations existant - voir la bannière "Écriture comptable générée"). */
@Component({
  selector: 'app-inventaire-detail',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './inventaire-detail.component.html',
  styleUrl: './inventaire-detail.component.scss',
})
export class InventaireDetailComponent implements OnChanges {
  private readonly comptabiliteService = inject(ComptabiliteService);

  @Input({ required: true }) entrepriseId!: string;
  @Input({ required: true }) inventaireId!: string;
  @Input() articles: ArticleDto[] = [];
  @Output() readonly misAJour = new EventEmitter<InventaireDto>();

  readonly libellesStatut = LIBELLES_STATUT_INVENTAIRE;
  readonly inventaire = signal<InventaireDto | null>(null);
  readonly chargement = signal(false);
  readonly envoiEnCours = signal(false);
  readonly erreur = signal<string | null>(null);

  articleIdSaisie = '';
  stockCompteSaisie = '';
  coutUnitaireManuelSaisie = '';

  ngOnChanges(): void {
    this.charger();
  }

  private charger(): void {
    this.chargement.set(true);
    this.comptabiliteService
      .obtenirInventaire(this.entrepriseId, this.inventaireId)
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (inventaire) => this.inventaire.set(inventaire),
        error: () => this.erreur.set("Impossible de charger cet inventaire."),
      });
  }

  get modifiable(): boolean {
    const statut = this.inventaire()?.statut;
    return statut === 'BROUILLON' || statut === 'EN_COURS';
  }

  saisirLigne(): void {
    if (!this.articleIdSaisie || !this.stockCompteSaisie) {
      this.erreur.set('Veuillez sélectionner un article et saisir le stock compté.');
      return;
    }
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    this.comptabiliteService
      .saisirLigneInventaire(this.entrepriseId, this.inventaireId, {
        articleId: this.articleIdSaisie,
        stockCompte: Number(this.stockCompteSaisie),
        coutUnitaireManuel: this.coutUnitaireManuelSaisie ? Number(this.coutUnitaireManuelSaisie) : undefined,
      })
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (inventaire) => {
          this.inventaire.set(inventaire);
          this.misAJour.emit(inventaire);
          this.articleIdSaisie = '';
          this.stockCompteSaisie = '';
          this.coutUnitaireManuelSaisie = '';
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de saisir cette ligne.'),
      });
  }

  valider(): void {
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    this.comptabiliteService
      .validerInventaire(this.entrepriseId, this.inventaireId)
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (inventaire) => {
          this.inventaire.set(inventaire);
          this.misAJour.emit(inventaire);
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de valider cet inventaire.'),
      });
  }

  cloturer(): void {
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    this.comptabiliteService
      .cloturerInventaire(this.entrepriseId, this.inventaireId)
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (inventaire) => {
          this.inventaire.set(inventaire);
          this.misAJour.emit(inventaire);
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de clôturer cet inventaire (la proposition comptable a-t-elle été validée ?).'),
      });
  }
}
