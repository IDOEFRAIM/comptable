import { Component, inject, OnInit, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite.service';
import { BilanDto, CompteResultatDto, ExerciceComptableDto } from '../models/comptabilite.model';
import { SectionFinanciereComponent } from './section-financiere.component';

type ModeEtat = 'BILAN' | 'COMPTE_RESULTAT';

@Component({
  selector: 'app-etats-financiers',
  standalone: true,
  imports: [FormsModule, DecimalPipe, SectionFinanciereComponent],
  templateUrl: './etats-financiers.component.html',
  styleUrl: './etats-financiers.component.scss',
})
export class EtatsFinanciersComponent implements OnInit {
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly mode = signal<ModeEtat>('BILAN');
  readonly exercices = signal<ExerciceComptableDto[]>([]);
  exerciceSelectionne = '';

  readonly bilan = signal<BilanDto | null>(null);
  readonly compteResultat = signal<CompteResultatDto | null>(null);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly clotureEnCours = signal(false);

  get exerciceCourant(): ExerciceComptableDto | undefined {
    return this.exercices().find((e) => e.id === this.exerciceSelectionne);
  }

  ngOnInit(): void {
    this.comptabiliteService.listerExercices(this.entrepriseId()).subscribe((exercices) => {
      this.exercices.set(exercices);
      if (exercices.length > 0) {
        this.exerciceSelectionne = exercices[0].id;
        this.generer();
      }
    });
  }

  changerMode(mode: ModeEtat): void {
    this.mode.set(mode);
    this.erreur.set(null);
    this.generer();
  }

  generer(): void {
    if (!this.exerciceSelectionne) {
      this.erreur.set('Sélectionnez un exercice.');
      return;
    }
    this.erreur.set(null);
    this.chargement.set(true);
    if (this.mode() === 'BILAN') {
      this.comptabiliteService
        .bilan(this.entrepriseId(), this.exerciceSelectionne)
        .pipe(finalize(() => this.chargement.set(false)))
        .subscribe({
          next: (bilan) => this.bilan.set(bilan),
          error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de générer le bilan.'),
        });
    } else {
      this.comptabiliteService
        .compteResultat(this.entrepriseId(), this.exerciceSelectionne)
        .pipe(finalize(() => this.chargement.set(false)))
        .subscribe({
          next: (cr) => this.compteResultat.set(cr),
          error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de générer le compte de résultat.'),
        });
    }
  }

  cloturerExercice(): void {
    const cr = this.compteResultat();
    const exercice = this.exerciceCourant;
    if (!cr || !exercice) {
      return;
    }
    const sens = cr.resultatNet >= 0 ? 'bénéfice' : 'perte';
    const confirmation = confirm(
      `Clôturer l'exercice ${exercice.dateDebut} → ${exercice.dateFin} avec un résultat de ${cr.resultatNet} (${sens}) ?\n\n` +
        `Cette action est IRRÉVERSIBLE : le résultat sera transféré au compte 120/129 et plus aucune écriture ne pourra être saisie sur cet exercice.`,
    );
    if (!confirmation) {
      return;
    }
    this.clotureEnCours.set(true);
    this.erreur.set(null);
    this.comptabiliteService
      .cloturerExercice(this.entrepriseId(), this.exerciceSelectionne)
      .pipe(finalize(() => this.clotureEnCours.set(false)))
      .subscribe({
        next: () => {
          this.comptabiliteService.listerExercices(this.entrepriseId()).subscribe((exercices) => {
            this.exercices.set(exercices);
            this.generer();
          });
        },
        error: (err) => this.erreur.set(err.error?.message ?? "Impossible de clôturer cet exercice."),
      });
  }
}
