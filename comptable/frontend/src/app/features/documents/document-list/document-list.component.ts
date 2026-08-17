import { DatePipe } from '@angular/common';
import { Component, inject, input, OnChanges, OnInit, signal } from '@angular/core';
import { ComptabiliserModalComponent } from '../../comptabilite/comptabiliser-modal.component';
import { DocumentService } from '../document.service';
import {
  DocumentDto,
  LIBELLES_STATUT,
  LIBELLES_TYPE_DOCUMENT,
  StatutDocument,
  TypeDocument,
} from '../models/document.model';

const TYPES_COMPTABILISABLES = new Set(['FACTURE_ACHAT', 'FACTURE_VENTE']);

@Component({
  selector: 'app-document-list',
  standalone: true,
  imports: [DatePipe, ComptabiliserModalComponent],
  templateUrl: './document-list.component.html',
  styleUrl: './document-list.component.scss',
})
export class DocumentListComponent implements OnInit, OnChanges {
  entrepriseId = input.required<string>();

  private readonly documentService = inject(DocumentService);

  readonly libellesStatut = LIBELLES_STATUT;
  readonly libellesType = LIBELLES_TYPE_DOCUMENT;
  readonly filtres: Array<{ valeur: StatutDocument | null; libelle: string }> = [
    { valeur: null, libelle: 'Tous' },
    { valeur: 'EN_ATTENTE', libelle: 'En attente' },
    { valeur: 'TRAITE', libelle: 'Traité' },
    { valeur: 'ERREUR', libelle: 'Erreur' },
  ];

  readonly documents = signal<DocumentDto[]>([]);
  readonly filtreActif = signal<StatutDocument | null>(null);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly documentEnComptabilisation = signal<DocumentDto | null>(null);

  ngOnInit(): void {
    this.rafraichir();
  }

  ngOnChanges(): void {
    this.rafraichir();
  }

  changerFiltre(statut: StatutDocument | null): void {
    this.filtreActif.set(statut);
    this.rafraichir();
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.erreur.set(null);
    this.documentService
      .lister(this.entrepriseId(), this.filtreActif() ?? undefined)
      .subscribe({
        next: (documents) => {
          this.documents.set(documents);
          this.chargement.set(false);
        },
        error: () => {
          this.erreur.set('Impossible de charger les documents.');
          this.chargement.set(false);
        },
      });
  }

  peutComptabiliser(document: DocumentDto): boolean {
    return document.statut === 'TRAITE' && !document.ecritureId && TYPES_COMPTABILISABLES.has(document.typeDocument);
  }

  /** Sur au type deja filtre par peutComptabiliser() : evite un cast dans le template. */
  asTypeComptabilisable(type: TypeDocument): 'FACTURE_ACHAT' | 'FACTURE_VENTE' {
    return type as 'FACTURE_ACHAT' | 'FACTURE_VENTE';
  }

  onComptabiliserValide(): void {
    this.documentEnComptabilisation.set(null);
    this.rafraichir();
  }

  classeStatut(statut: StatutDocument): string {
    switch (statut) {
      case 'TRAITE':
        return 'badge badge-succes';
      case 'ERREUR':
        return 'badge badge-erreur';
      default:
        return 'badge badge-attente';
    }
  }
}
