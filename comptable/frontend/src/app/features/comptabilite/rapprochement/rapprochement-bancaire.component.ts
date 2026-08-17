import { Component, inject, OnInit, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { AideContextuelleComponent } from '../../../core/aide/aide-contextuelle.component';
import { ComptabiliteService } from '../comptabilite.service';
import {
  AiMatchProposalDto,
  CompteComptableDto,
  GrandLivreLigneDto,
  LIBELLES_TYPE_CANDIDAT_IA,
  ReleveBancaireDto,
} from '../models/comptabilite.model';

@Component({
  selector: 'app-rapprochement-bancaire',
  standalone: true,
  imports: [FormsModule, DecimalPipe, AideContextuelleComponent],
  templateUrl: './rapprochement-bancaire.component.html',
  styleUrl: './rapprochement-bancaire.component.scss',
})
export class RapprochementBancaireComponent implements OnInit {
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly comptesTresorerie = signal<CompteComptableDto[]>([]);
  readonly releves = signal<ReleveBancaireDto[]>([]);
  readonly releveActif = signal<ReleveBancaireDto | null>(null);
  readonly candidats = signal<GrandLivreLigneDto[]>([]);
  /** Propositions du Universal Matching Engine (mission 2, section 12) restant à traiter (statut
   * PROPOSED, décision MATCH_REVIEW uniquement - un MATCH_AUTO a déjà pointé la ligne, un
   * NO_MATCH/AI_UNAVAILABLE n'a pas de candidat exploitable), indexées par ligneReleveId. */
  readonly propositionsIA = signal<Record<string, AiMatchProposalDto>>({});
  readonly detailsIAOuverts = signal<Set<string>>(new Set());
  readonly chargement = signal(false);
  readonly envoiEnCours = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly afficherFormulaire = signal(false);

  compteId = '';
  libelle = '';
  dateDebut = '';
  dateFin = '';
  soldeDebut = '';
  soldeFin = '';
  fichier: File | null = null;

  ngOnInit(): void {
    this.comptabiliteService.listerComptes(this.entrepriseId()).subscribe((comptes) => {
      this.comptesTresorerie.set(comptes.filter((c) => c.numero.startsWith('5')));
    });
    this.rafraichirListe();
  }

  rafraichirListe(): void {
    this.chargement.set(true);
    this.comptabiliteService
      .listerReleves(this.entrepriseId())
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (releves) => this.releves.set(releves),
        error: () => this.erreur.set('Impossible de charger les relevés.'),
      });
  }

  surFichierChoisi(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.fichier = input.files?.[0] ?? null;
  }

  importer(): void {
    if (!this.compteId || !this.fichier || !this.libelle || !this.dateDebut || !this.dateFin) {
      this.erreur.set('Complétez tous les champs et choisissez un fichier CSV.');
      return;
    }
    const donnees = new FormData();
    donnees.set('compteId', this.compteId);
    donnees.set('libelle', this.libelle);
    donnees.set('dateDebut', this.dateDebut);
    donnees.set('dateFin', this.dateFin);
    donnees.set('soldeDebut', this.soldeDebut || '0');
    donnees.set('soldeFin', this.soldeFin || '0');
    donnees.set('fichier', this.fichier);

    this.envoiEnCours.set(true);
    this.erreur.set(null);
    this.comptabiliteService
      .importerReleve(this.entrepriseId(), donnees)
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (releve) => {
          this.releves.update((liste) => [releve, ...liste]);
          this.afficherFormulaire.set(false);
          this.ouvrirReleve(releve.id);
        },
        error: (err) => this.erreur.set(err.error?.message ?? "Impossible d'importer ce relevé."),
      });
  }

  ouvrirReleve(releveId: string): void {
    this.chargement.set(true);
    this.erreur.set(null);
    this.comptabiliteService
      .obtenirReleve(this.entrepriseId(), releveId)
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (releve) => {
          this.releveActif.set(releve);
          this.chargerCandidats(releve);
          this.chargerPropositionsIA(releve);
        },
        error: () => this.erreur.set('Impossible de charger ce relevé.'),
      });
  }

  /** Une requête par ligne non pointée : le nombre de lignes encore à traiter dans un relevé
   * reste naturellement petit, et chaque appel ne fait que relire une proposition déjà calculée
   * (pas un nouvel appel IA) - cf. AiMatchProposalService côté Core. */
  private chargerPropositionsIA(releve: ReleveBancaireDto): void {
    const nonPointees = releve.lignes.filter((l) => !l.pointee);
    const propositions: Record<string, AiMatchProposalDto> = {};
    if (nonPointees.length === 0) {
      this.propositionsIA.set(propositions);
      return;
    }
    let restantes = nonPointees.length;
    nonPointees.forEach((ligne) => {
      this.comptabiliteService
        .obtenirPropositionIA(this.entrepriseId(), releve.id, ligne.id)
        .pipe(
          finalize(() => {
            restantes -= 1;
            if (restantes === 0) {
              this.propositionsIA.set({ ...propositions });
            }
          }),
        )
        .subscribe({
          next: (proposition) => {
            if (proposition.decision === 'MATCH_REVIEW') {
              propositions[ligne.id] = proposition;
            }
          },
          // Pas de proposition pour cette ligne (404) : rien à afficher, cas normal.
          error: () => {},
        });
    });
  }

  libelleTypeCandidatIA(type: string | null): string {
    return type ? (LIBELLES_TYPE_CANDIDAT_IA[type as keyof typeof LIBELLES_TYPE_CANDIDAT_IA] ?? type) : 'candidat';
  }

  basculerDetailsIA(propositionId: string): void {
    const ouverts = new Set(this.detailsIAOuverts());
    if (ouverts.has(propositionId)) {
      ouverts.delete(propositionId);
    } else {
      ouverts.add(propositionId);
    }
    this.detailsIAOuverts.set(ouverts);
  }

  accepterPropositionIA(propositionId: string): void {
    const releve = this.releveActif();
    if (!releve) {
      return;
    }
    this.comptabiliteService.accepterPropositionIA(this.entrepriseId(), propositionId).subscribe({
      next: () => this.ouvrirReleve(releve.id),
      error: (err) => this.erreur.set(err.error?.message ?? "Impossible d'accepter cette proposition."),
    });
  }

  rejeterPropositionIA(propositionId: string): void {
    const releve = this.releveActif();
    if (!releve) {
      return;
    }
    this.comptabiliteService.rejeterPropositionIA(this.entrepriseId(), propositionId).subscribe({
      next: () => this.ouvrirReleve(releve.id),
      error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de rejeter cette proposition.'),
    });
  }

  private chargerCandidats(releve: ReleveBancaireDto): void {
    const debut = this.decalerDate(releve.dateDebut, -15);
    const fin = this.decalerDate(releve.dateFin, 15);
    this.comptabiliteService.grandLivre(this.entrepriseId(), releve.compteId, debut, fin).subscribe((gl) => {
      const idsDejaPointes = new Set(releve.lignes.filter((l) => l.ligneEcritureId).map((l) => l.ligneEcritureId));
      this.candidats.set(gl.lignes.filter((l) => !idsDejaPointes.has(l.id)));
    });
  }

  private decalerDate(iso: string, jours: number): string {
    const date = new Date(iso);
    date.setDate(date.getDate() + jours);
    return date.toISOString().slice(0, 10);
  }

  candidatsPourMontant(montant: number): GrandLivreLigneDto[] {
    const cible = Math.abs(montant);
    return this.candidats().filter((c) => (montant > 0 ? c.debit : c.credit) === cible);
  }

  pointer(ligneReleveId: string, ligneEcritureId: string): void {
    const releve = this.releveActif();
    if (!releve || !ligneEcritureId) {
      return;
    }
    this.comptabiliteService.pointerLigne(this.entrepriseId(), releve.id, ligneReleveId, ligneEcritureId).subscribe({
      next: () => this.ouvrirReleve(releve.id),
      error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de pointer cette ligne.'),
    });
  }

  depointer(ligneReleveId: string): void {
    const releve = this.releveActif();
    if (!releve) {
      return;
    }
    this.comptabiliteService.depointerLigne(this.entrepriseId(), releve.id, ligneReleveId).subscribe({
      next: () => this.ouvrirReleve(releve.id),
      error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de dépointer cette ligne.'),
    });
  }
}
