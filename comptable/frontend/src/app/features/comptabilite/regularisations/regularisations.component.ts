import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule, DecimalPipe } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite.service';
import { CompteComptableDto, ExerciceComptableDto } from '../models/comptabilite.model';
import {
  LIBELLES_STATUT_REGULARISATION,
  LIBELLES_TYPE_REGULARISATION,
  RegularisationDto,
  RegularisationType,
} from '../models/regularisation.model';

/** Regularisations de fin de periode (mission cloture Etape 4) : liste + creation + proposition.
 * La validation/le rejet reutilisent l'ecran Operations existant (meme convention que l'inventaire,
 * voir inventaire-detail.component) plutot que de dupliquer un second ecran de validation. */
@Component({
  selector: 'app-regularisations',
  standalone: true,
  imports: [ReactiveFormsModule, DecimalPipe, CommonModule],
  templateUrl: './regularisations.component.html',
  styleUrl: './regularisations.component.scss',
})
export class RegularisationsComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);

  readonly libellesType = LIBELLES_TYPE_REGULARISATION;
  readonly libellesStatut = LIBELLES_STATUT_REGULARISATION;

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly comptes = signal<CompteComptableDto[]>([]);
  readonly exercices = signal<ExerciceComptableDto[]>([]);
  readonly regularisations = signal<RegularisationDto[]>([]);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly afficherFormulaire = signal(false);
  readonly envoiEnCours = signal(false);

  readonly types: RegularisationType[] = [
    'CHARGE_CONSTATEE_AVANCE',
    'PRODUIT_CONSTATE_AVANCE',
    'FACTURE_NON_PARVENUE',
    'PRODUIT_A_RECEVOIR',
    'CHARGE_A_PAYER',
    'PRODUIT_A_REPORTER',
  ];

  readonly form = this.fb.nonNullable.group({
    exerciceId: ['', Validators.required],
    type: this.fb.nonNullable.control<RegularisationType>('CHARGE_CONSTATEE_AVANCE', Validators.required),
    dateOrigine: [new Date().toISOString().slice(0, 10), Validators.required],
    dateDebut: [''],
    dateFin: [''],
    montant: ['', Validators.required],
    libelle: ['', Validators.required],
    compteOrigineId: ['', Validators.required],
    compteContrepartieId: ['', Validators.required],
  });

  ngOnInit(): void {
    this.comptabiliteService.listerComptes(this.entrepriseId()).subscribe((comptes) => this.comptes.set(comptes));
    this.comptabiliteService.listerExercices(this.entrepriseId()).subscribe((exercices) => this.exercices.set(exercices));
    this.rafraichir();
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.comptabiliteService
      .listerRegularisations(this.entrepriseId())
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (regularisations) => this.regularisations.set(regularisations),
        error: () => this.erreur.set('Impossible de charger les régularisations.'),
      });
  }

  creer(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    const v = this.form.getRawValue();
    this.comptabiliteService
      .creerRegularisation(this.entrepriseId(), {
        exerciceId: v.exerciceId,
        type: v.type,
        dateOrigine: v.dateOrigine,
        dateDebut: v.dateDebut || undefined,
        dateFin: v.dateFin || undefined,
        montant: Number(v.montant),
        libelle: v.libelle,
        compteOrigineId: v.compteOrigineId,
        compteContrepartieId: v.compteContrepartieId,
      })
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (regularisation) => {
          this.regularisations.update((liste) => [regularisation, ...liste]);
          this.afficherFormulaire.set(false);
          this.form.reset({
            exerciceId: '', type: 'CHARGE_CONSTATEE_AVANCE', dateOrigine: new Date().toISOString().slice(0, 10),
            dateDebut: '', dateFin: '', montant: '', libelle: '', compteOrigineId: '', compteContrepartieId: '',
          });
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de créer cette régularisation.'),
      });
  }

  proposer(regularisation: RegularisationDto): void {
    this.erreur.set(null);
    this.comptabiliteService.proposerRegularisation(this.entrepriseId(), regularisation.id).subscribe({
      next: (miseAJour) => this.remplacer(miseAJour),
      error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de proposer cette régularisation.'),
    });
  }

  private remplacer(regularisation: RegularisationDto): void {
    this.regularisations.update((liste) => liste.map((r) => (r.id === regularisation.id ? regularisation : r)));
  }
}
