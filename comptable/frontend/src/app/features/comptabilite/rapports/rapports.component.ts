import { Component, inject, OnInit, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite.service';
import {
  BalanceAgeeDto,
  BalanceDto,
  CompteComptableDto,
  EcritureComptableDto,
  ExerciceComptableDto,
  GrandLivreDto,
  JournalDto,
} from '../models/comptabilite.model';

type ModeRapport = 'BALANCE' | 'GRAND_LIVRE' | 'JOURNAL' | 'BALANCE_AGEE' | 'FEC';

function premierJourAnnee(): string {
  return `${new Date().getFullYear()}-01-01`;
}

function dernierJourAnnee(): string {
  return `${new Date().getFullYear()}-12-31`;
}

@Component({
  selector: 'app-rapports',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './rapports.component.html',
  styleUrl: './rapports.component.scss',
})
export class RapportsComponent implements OnInit {
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly mode = signal<ModeRapport>('BALANCE');
  dateDebut = premierJourAnnee();
  dateFin = dernierJourAnnee();
  compteSelectionne = '';
  journalSelectionne = '';
  exerciceSelectionne = '';
  dateArreteBalanceAgee = new Date().toISOString().slice(0, 10);
  lettragePartiel = false;

  readonly comptes = signal<CompteComptableDto[]>([]);
  readonly journaux = signal<JournalDto[]>([]);
  readonly exercices = signal<ExerciceComptableDto[]>([]);
  readonly balance = signal<BalanceDto | null>(null);
  readonly grandLivre = signal<GrandLivreDto | null>(null);
  readonly ecrituresJournal = signal<EcritureComptableDto[] | null>(null);
  readonly balanceAgee = signal<BalanceAgeeDto | null>(null);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly succes = signal<string | null>(null);
  readonly selectionLettrage = signal<Set<string>>(new Set());
  readonly lettrageEnCours = signal(false);
  readonly exportEnCours = signal(false);

  get compteLettrable(): boolean {
    return this.comptes().find((c) => c.id === this.compteSelectionne)?.lettrable ?? false;
  }

  ngOnInit(): void {
    this.comptabiliteService.listerComptes(this.entrepriseId()).subscribe((comptes) => this.comptes.set(comptes));
    this.comptabiliteService.listerJournaux(this.entrepriseId()).subscribe((journaux) => this.journaux.set(journaux));
    this.comptabiliteService.listerExercices(this.entrepriseId()).subscribe((exercices) => {
      this.exercices.set(exercices);
      if (!this.exerciceSelectionne && exercices.length > 0) {
        this.exerciceSelectionne = exercices[0].id;
      }
    });
    this.generer();
  }

  changerMode(mode: ModeRapport): void {
    this.mode.set(mode);
    this.balance.set(null);
    this.grandLivre.set(null);
    this.ecrituresJournal.set(null);
    this.balanceAgee.set(null);
    this.erreur.set(null);
    this.succes.set(null);
    if (mode === 'BALANCE' || mode === 'BALANCE_AGEE') {
      this.generer();
    }
  }

  generer(): void {
    this.erreur.set(null);
    if (this.mode() === 'BALANCE') {
      this.chargement.set(true);
      this.comptabiliteService
        .balance(this.entrepriseId(), this.dateDebut, this.dateFin)
        .pipe(finalize(() => this.chargement.set(false)))
        .subscribe({
          next: (balance) => this.balance.set(balance),
          error: () => this.erreur.set('Impossible de générer la balance.'),
        });
    } else if (this.mode() === 'GRAND_LIVRE') {
      if (!this.compteSelectionne) {
        this.erreur.set('Sélectionnez un compte.');
        return;
      }
      this.selectionLettrage.set(new Set());
      this.chargement.set(true);
      this.comptabiliteService
        .grandLivre(this.entrepriseId(), this.compteSelectionne, this.dateDebut, this.dateFin)
        .pipe(finalize(() => this.chargement.set(false)))
        .subscribe({
          next: (grandLivre) => this.grandLivre.set(grandLivre),
          error: () => this.erreur.set('Impossible de générer le grand livre.'),
        });
    } else if (this.mode() === 'JOURNAL') {
      if (!this.journalSelectionne) {
        this.erreur.set('Sélectionnez un journal.');
        return;
      }
      this.chargement.set(true);
      this.comptabiliteService
        .journalOperations(this.entrepriseId(), this.journalSelectionne, this.dateDebut, this.dateFin)
        .pipe(finalize(() => this.chargement.set(false)))
        .subscribe({
          next: (ecritures) => this.ecrituresJournal.set(ecritures),
          error: () => this.erreur.set('Impossible de générer le journal.'),
        });
    } else if (this.mode() === 'BALANCE_AGEE') {
      this.chargement.set(true);
      this.comptabiliteService
        .balanceAgee(this.entrepriseId(), this.dateArreteBalanceAgee)
        .pipe(finalize(() => this.chargement.set(false)))
        .subscribe({
          next: (balanceAgee) => this.balanceAgee.set(balanceAgee),
          error: () => this.erreur.set('Impossible de générer la balance âgée.'),
        });
    }
  }

  lettrerAutomatiquement(): void {
    if (!this.compteSelectionne) {
      this.erreur.set('Sélectionnez un compte.');
      return;
    }
    this.lettrageEnCours.set(true);
    this.erreur.set(null);
    this.succes.set(null);
    this.comptabiliteService
      .lettrerAutomatiquement(this.entrepriseId(), this.compteSelectionne)
      .pipe(finalize(() => this.lettrageEnCours.set(false)))
      .subscribe({
        next: (resultats) => {
          this.succes.set(
            resultats.length > 0
              ? `${resultats.length} lettrage(s) automatique(s) effectué(s) (${resultats.map((r) => r.code).join(', ')}).`
              : 'Aucune paire exacte et non ambiguë à lettrer automatiquement.',
          );
          this.generer();
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de lettrer automatiquement ce compte.'),
      });
  }

  exporterFec(): void {
    if (!this.exerciceSelectionne) {
      this.erreur.set('Sélectionnez un exercice.');
      return;
    }
    this.exportEnCours.set(true);
    this.erreur.set(null);
    this.comptabiliteService
      .exporterFec(this.entrepriseId(), this.exerciceSelectionne)
      .pipe(finalize(() => this.exportEnCours.set(false)))
      .subscribe({
        next: (blob) => {
          const url = URL.createObjectURL(blob);
          const lien = document.createElement('a');
          lien.href = url;
          const exercice = this.exercices().find((e) => e.id === this.exerciceSelectionne);
          lien.download = `FEC-${exercice?.dateFin ?? this.exerciceSelectionne}.txt`;
          lien.click();
          URL.revokeObjectURL(url);
        },
        error: () => this.erreur.set("Impossible de générer l'export FEC."),
      });
  }

  estSelectionnee(ligneId: string): boolean {
    return this.selectionLettrage().has(ligneId);
  }

  basculerSelection(ligneId: string): void {
    const nouvelle = new Set(this.selectionLettrage());
    if (nouvelle.has(ligneId)) {
      nouvelle.delete(ligneId);
    } else {
      nouvelle.add(ligneId);
    }
    this.selectionLettrage.set(nouvelle);
  }

  lettrerSelection(): void {
    const ligneIds = Array.from(this.selectionLettrage());
    if (ligneIds.length < 2) {
      this.erreur.set('Sélectionnez au moins deux lignes à lettrer.');
      return;
    }
    this.lettrageEnCours.set(true);
    this.erreur.set(null);
    this.succes.set(null);
    this.comptabiliteService
      .lettrer(this.entrepriseId(), { compteId: this.compteSelectionne, ligneIds, partiel: this.lettragePartiel })
      .pipe(finalize(() => this.lettrageEnCours.set(false)))
      .subscribe({
        next: (resultat) => {
          this.selectionLettrage.set(new Set());
          this.succes.set(
            resultat.ecart !== 0
              ? `Lettrage partiel "${resultat.code}" enregistré, écart résiduel : ${resultat.ecart}.`
              : `Lettrage "${resultat.code}" enregistré.`,
          );
          this.generer();
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de lettrer cette sélection.'),
      });
  }

  delettrerCode(code: string): void {
    if (!confirm(`Délettrer le groupe "${code}" ?`)) {
      return;
    }
    this.lettrageEnCours.set(true);
    this.erreur.set(null);
    this.comptabiliteService
      .delettrer(this.entrepriseId(), this.compteSelectionne, code)
      .pipe(finalize(() => this.lettrageEnCours.set(false)))
      .subscribe({
        next: () => this.generer(),
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de délettrer ce groupe.'),
      });
  }
}
