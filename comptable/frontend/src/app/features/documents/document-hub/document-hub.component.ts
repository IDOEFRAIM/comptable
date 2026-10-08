import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize } from 'rxjs/operators';
import { ComptabiliserModalComponent } from '../../comptabilite/comptabiliser-modal.component';
import { DocumentHubService } from '../document-hub.service';
import {
  CandidatDemandeDto,
  CandidatFactureDto,
  DocumentSimpleDto,
  LIBELLES_CONSTAT_DOCUMENT,
  LigneHubDto,
  SyntheseHubDto,
  TuileHub,
} from '../models/document-hub.model';
import { LIBELLES_CANAL, LIBELLES_TYPE_DOCUMENT, TypeDocument } from '../models/document.model';

type Panneau = { documentId: string; type: 'ASSOCIER' | 'DOUBLON' | 'DEMANDE' };

const TUILES: { code: TuileHub; libelle: string; cle: keyof SyntheseHubDto }[] = [
  { code: 'RECUS', libelle: 'Reçus', cle: 'recus' },
  { code: 'TRAITES', libelle: 'Traités', cle: 'traites' },
  { code: 'A_VERIFIER', libelle: 'À vérifier', cle: 'aVerifier' },
  { code: 'A_CONFIRMER', libelle: 'À confirmer', cle: 'aConfirmer' },
  { code: 'DOUBLONS', libelle: 'Doublons', cle: 'doublons' },
  { code: 'NON_RAPPROCHES', libelle: 'Non rapprochés', cle: 'nonRapproches' },
];

/**
 * Hub documents du cabinet : ce qui est arrivé, ce qui est déjà traité, ce qui demande UNE décision. Les pièces
 * certaines se rangent seules ; ici on ne voit que le travail restant. L'association se choisit dans une liste de
 * factures candidates — jamais en saisissant un identifiant.
 */
@Component({
  selector: 'app-document-hub',
  standalone: true,
  imports: [FormsModule, DatePipe, CurrencyPipe, ComptabiliserModalComponent],
  templateUrl: './document-hub.component.html',
  styleUrl: './document-hub.component.scss',
})
export class DocumentHubComponent implements OnInit, OnDestroy {
  entrepriseId = input.required<string>();
  peutGerer = input<boolean>(true);
  /** Une décision sur une pièce peut avoir clos une demande de pièce : la collecte se relit. */
  modifie = output<void>();

  private readonly hub = inject(DocumentHubService);
  private readonly router = inject(Router);

  readonly tuiles = TUILES;
  readonly libellesType = LIBELLES_TYPE_DOCUMENT;
  readonly libellesCanal = LIBELLES_CANAL;
  readonly libellesConstat: Record<string, string | undefined> = LIBELLES_CONSTAT_DOCUMENT;
  readonly typesDocument = Object.keys(LIBELLES_TYPE_DOCUMENT) as TypeDocument[];

  readonly synthese = signal<SyntheseHubDto | null>(null);
  readonly lignes = signal<LigneHubDto[]>([]);
  readonly total = signal(0);
  readonly page = signal(0);
  readonly totalPages = signal(0);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly info = signal<string | null>(null);

  readonly panneau = signal<Panneau | null>(null);
  readonly candidats = signal<CandidatFactureDto[]>([]);
  readonly demandesCandidates = signal<CandidatDemandeDto[]>([]);
  readonly originaux = signal<DocumentSimpleDto[]>([]);
  readonly choix = signal<string | null>(null);
  readonly chargementPanneau = signal(false);

  readonly apercuLot = signal<DocumentSimpleDto[] | null>(null);
  readonly selectionLot = signal<Set<string>>(new Set());
  readonly documentEnComptabilisation = signal<LigneHubDto | null>(null);

  tuile: TuileHub | '' = '';
  type: TypeDocument | '' = '';
  recherche = '';
  periode = new Date().toISOString().slice(0, 7);
  toutesPeriodes = false;
  readonly taille = 20;
  private minuteur: ReturnType<typeof setTimeout> | null = null;

  ngOnInit(): void {
    this.rafraichir();
  }

  ngOnDestroy(): void {
    if (this.minuteur) {
      clearTimeout(this.minuteur);
    }
  }

  private periodeEffective(): string | undefined {
    return this.toutesPeriodes || !this.periode ? undefined : this.periode;
  }

  rafraichir(): void {
    this.chargerSynthese();
    this.chargerLignes();
  }

  chargerSynthese(): void {
    this.hub.synthese(this.entrepriseId(), this.periodeEffective()).subscribe({
      next: (s) => this.synthese.set(s),
      error: () => this.erreur.set('Impossible de charger la synthèse des documents.'),
    });
  }

  chargerLignes(): void {
    this.chargement.set(true);
    this.hub
      .lister(this.entrepriseId(), {
        tuile: this.tuile,
        type: this.type,
        q: this.recherche,
        periode: this.periodeEffective(),
        page: this.page(),
        taille: this.taille,
      })
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (p) => {
          this.lignes.set(p.contenu);
          this.total.set(p.total);
          this.totalPages.set(p.totalPages);
        },
        error: () => this.erreur.set('Impossible de charger les documents.'),
      });
  }

  nombre(cle: keyof SyntheseHubDto): number {
    const valeur = this.synthese()?.[cle];
    return typeof valeur === 'number' ? valeur : 0;
  }

  choisirTuile(code: TuileHub): void {
    this.tuile = this.tuile === code ? '' : code;
    this.filtresChanges();
  }

  filtresChanges(): void {
    this.page.set(0);
    this.chargerSynthese();
    this.chargerLignes();
  }

  rechercheChangee(): void {
    if (this.minuteur) {
      clearTimeout(this.minuteur);
    }
    this.minuteur = setTimeout(() => this.chargerLignes(), 300);
  }

  changerPage(delta: number): void {
    const cible = this.page() + delta;
    if (cible < 0 || cible >= this.totalPages()) {
      return;
    }
    this.page.set(cible);
    this.chargerLignes();
  }

  voirPiecesManquantes(): void {
    this.router.navigate(['/entreprises', this.entrepriseId(), 'revision'], { queryParams: { tuile: 'PIECES_MANQUANTES' } });
  }

  // ------------------------------------------------------------------------------------------ actions

  private apresAction(message: string): void {
    this.info.set(message);
    this.erreur.set(null);
    this.panneau.set(null);
    this.rafraichir();
    this.modifie.emit();
  }

  private echec(err: { error?: { message?: string } }, defaut: string): void {
    this.erreur.set(err.error?.message ?? defaut);
  }

  ouvrirFichier(ligne: LigneHubDto): void {
    this.hub.fichier(this.entrepriseId(), ligne.id).subscribe({
      next: (blob) => window.open(URL.createObjectURL(blob), '_blank'),
      error: () => this.erreur.set('Impossible d’ouvrir ce fichier.'),
    });
  }

  confirmer(ligne: LigneHubDto): void {
    this.hub.valider(this.entrepriseId(), ligne.id).subscribe({
      next: () => this.apresAction('Rapprochement confirmé.'),
      error: (err) => this.echec(err, 'Impossible de confirmer ce rapprochement.'),
    });
  }

  validerPiece(ligne: LigneHubDto): void {
    this.hub.valider(this.entrepriseId(), ligne.id).subscribe({
      next: () => this.apresAction('Pièce validée.'),
      error: (err) => this.echec(err, 'Impossible de valider cette pièce.'),
    });
  }

  rejeterProposition(ligne: LigneHubDto): void {
    this.hub.rejeterProposition(this.entrepriseId(), ligne.id).subscribe({
      next: () => this.apresAction('Proposition rejetée : elle ne sera plus proposée pour cette pièce.'),
      error: (err) => this.echec(err, 'Impossible de rejeter cette proposition.'),
    });
  }

  retraiter(ligne: LigneHubDto): void {
    this.hub.retraiter(this.entrepriseId(), ligne.id).subscribe({
      next: () => this.apresAction('Pièce réanalysée.'),
      error: (err) => this.echec(err, 'Impossible de réanalyser cette pièce.'),
    });
  }

  ouvrirAssociation(ligne: LigneHubDto): void {
    this.choix.set(null);
    this.candidats.set([]);
    this.chargementPanneau.set(true);
    this.panneau.set({ documentId: ligne.id, type: 'ASSOCIER' });
    this.hub
      .candidats(this.entrepriseId(), ligne.id)
      .pipe(finalize(() => this.chargementPanneau.set(false)))
      .subscribe({
        next: (c) => this.candidats.set(c),
        error: (err) => this.echec(err, 'Impossible de charger les factures candidates.'),
      });
  }

  confirmerAssociation(ligne: LigneHubDto): void {
    const cible = this.candidats().find((c) => c.id === this.choix());
    if (!cible) {
      return;
    }
    this.hub.associer(this.entrepriseId(), ligne.id, cible.cibleType, cible.id).subscribe({
      next: () => this.apresAction('Pièce associée à la facture ' + cible.numero + '.'),
      error: (err) => this.echec(err, 'Impossible d’associer cette pièce.'),
    });
  }

  /** Associe la pièce à une demande de pièce ouverte, choisie dans la liste des candidates (jamais saisie). */
  ouvrirAssociationDemande(ligne: LigneHubDto): void {
    this.choix.set(null);
    this.demandesCandidates.set([]);
    this.chargementPanneau.set(true);
    this.panneau.set({ documentId: ligne.id, type: 'DEMANDE' });
    this.hub
      .demandesCandidates(this.entrepriseId(), ligne.id)
      .pipe(finalize(() => this.chargementPanneau.set(false)))
      .subscribe({
        next: (c) => {
          this.demandesCandidates.set(c);
          // Une seule candidate certaine : elle est pré-sélectionnée, le comptable n'a qu'à confirmer.
          if (c.length > 0 && c[0].niveau === 'EXACT') {
            this.choix.set(c[0].demandeId);
          }
        },
        error: (err) => this.echec(err, 'Impossible de charger les demandes candidates.'),
      });
  }

  confirmerAssociationDemande(ligne: LigneHubDto): void {
    const demande = this.demandesCandidates().find((c) => c.demandeId === this.choix());
    if (!demande) {
      return;
    }
    this.hub.associerDemande(this.entrepriseId(), ligne.id, demande.demandeId).subscribe({
      next: () => this.apresAction('Pièce associée à la demande : la demande et le constat sont résolus.'),
      error: (err) => this.echec(err, 'Impossible d’associer cette pièce à la demande.'),
    });
  }

  ouvrirDoublon(ligne: LigneHubDto): void {
    this.choix.set(null);
    this.originaux.set([]);
    this.chargementPanneau.set(true);
    this.panneau.set({ documentId: ligne.id, type: 'DOUBLON' });
    this.hub
      .originauxPossibles(this.entrepriseId(), ligne.id)
      .pipe(finalize(() => this.chargementPanneau.set(false)))
      .subscribe({
        next: (o) => this.originaux.set(o),
        error: (err) => this.echec(err, 'Impossible de chercher l’original.'),
      });
  }

  confirmerDoublon(ligne: LigneHubDto): void {
    const original = this.choix();
    if (!original) {
      return;
    }
    this.hub.marquerDoublon(this.entrepriseId(), ligne.id, original).subscribe({
      next: () => this.apresAction('Pièce marquée comme doublon : elle sort du travail à faire (elle reste conservée).'),
      error: (err) => this.echec(err, 'Impossible de marquer ce doublon.'),
    });
  }

  niveauDemande(niveau: CandidatDemandeDto['niveau']): string {
    switch (niveau) {
      case 'EXACT':
        return 'correspondance certaine';
      case 'PROBABLE':
        return 'correspondance probable';
      case 'AMBIGUOUS':
        return 'plusieurs demandes possibles : à vous de choisir';
      default:
        return 'possible';
    }
  }

  fermerPanneau(): void {
    this.panneau.set(null);
  }

  // ------------------------------------------------------------------------------------------ validation en masse

  ouvrirApercuLot(): void {
    this.hub.apercuValidationLot(this.entrepriseId()).subscribe({
      next: (apercu) => {
        this.apercuLot.set(apercu);
        this.selectionLot.set(new Set(apercu.map((d) => d.id)));
      },
      error: () => this.erreur.set('Impossible de charger l’aperçu.'),
    });
  }

  basculerSelection(id: string): void {
    const s = new Set(this.selectionLot());
    if (s.has(id)) {
      s.delete(id);
    } else {
      s.add(id);
    }
    this.selectionLot.set(s);
  }

  validerSelection(): void {
    const ids = [...this.selectionLot()];
    if (ids.length === 0) {
      return;
    }
    this.hub.validerLot(this.entrepriseId(), ids).subscribe({
      next: (r) => {
        this.apercuLot.set(null);
        this.apresAction(`${r.valides.length} pièce(s) validée(s)` + (r.ignores.length ? `, ${r.ignores.length} ignorée(s) (état modifié entre-temps).` : '.'));
      },
      error: (err) => this.echec(err, 'Impossible de valider la sélection.'),
    });
  }

  // ------------------------------------------------------------------------------------------ affichage

  peutComptabiliser(ligne: LigneHubDto): boolean {
    return (
      this.peutGerer() &&
      ligne.statut === 'TRAITE' &&
      !ligne.ecritureId &&
      !ligne.association &&
      !ligne.doublonConfirme &&
      (ligne.type === 'FACTURE_ACHAT' || ligne.type === 'FACTURE_VENTE')
    );
  }

  asTypeComptabilisable(type: TypeDocument): 'FACTURE_ACHAT' | 'FACTURE_VENTE' {
    return type as 'FACTURE_ACHAT' | 'FACTURE_VENTE';
  }

  peutAssocier(ligne: LigneHubDto): boolean {
    return (
      this.peutGerer() &&
      (ligne.type === 'FACTURE_ACHAT' || ligne.type === 'FACTURE_VENTE') &&
      !ligne.doublonConfirme &&
      ligne.statutRapprochement !== 'RAPPROCHE' &&
      ligne.statutRapprochement !== 'A_CONFIRMER'
    );
  }

  libelleStatut(ligne: LigneHubDto): { texte: string; classe: string } {
    if (ligne.doublonConfirme) {
      return { texte: 'Doublon confirmé', classe: 'badge-neutre' };
    }
    switch (ligne.statutRapprochement) {
      case 'RAPPROCHE':
        return ligne.valide ? { texte: 'Validée', classe: 'badge-succes' } : { texte: 'Rapprochée', classe: 'badge-info' };
      case 'A_CONFIRMER':
        return { texte: 'À confirmer', classe: 'badge-attente' };
      case 'AMBIGU':
        return { texte: 'Ambiguë', classe: 'badge-erreur' };
      default:
        break;
    }
    if (ligne.statut === 'ERREUR') {
      return { texte: 'Illisible', classe: 'badge-erreur' };
    }
    if (ligne.statut === 'EN_ATTENTE_VALIDATION_HUMAINE') {
      return { texte: 'À valider', classe: 'badge-attente' };
    }
    if (ligne.ecritureId) {
      return { texte: 'Comptabilisée', classe: 'badge-succes' };
    }
    return { texte: 'À traiter', classe: 'badge-neutre' };
  }

  confiance(ligne: LigneHubDto): string | null {
    const c = ligne.statutRapprochement === 'A_CONFIRMER' ? ligne.confianceRapprochement : ligne.confianceTiers;
    return c ? Math.round(c * 100) + ' %' : null;
  }

  libelleQualite(q: string | null): string {
    switch (q) {
      case 'EXACT':
        return 'Fournisseur identifié';
      case 'PROBABLE':
        return 'Fournisseur probable';
      case 'AMBIGU':
        return 'Fournisseur ambigu';
      case 'INCONNU':
        return 'Fournisseur inconnu';
      default:
        return '';
    }
  }
}
