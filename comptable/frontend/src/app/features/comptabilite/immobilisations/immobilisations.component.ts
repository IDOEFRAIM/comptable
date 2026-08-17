import { Component, inject, OnInit, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite.service';
import { CompteComptableDto, ImmobilisationDto, ModeAmortissement } from '../models/comptabilite.model';

@Component({
  selector: 'app-immobilisations',
  standalone: true,
  imports: [ReactiveFormsModule, FormsModule, DecimalPipe],
  templateUrl: './immobilisations.component.html',
  styleUrl: './immobilisations.component.scss',
})
export class ImmobilisationsComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly comptesActif = signal<CompteComptableDto[]>([]);
  readonly comptesAmortissement = signal<CompteComptableDto[]>([]);
  readonly immobilisations = signal<ImmobilisationDto[]>([]);
  readonly immobilisationOuverte = signal<string | null>(null);
  readonly chargement = signal(false);
  readonly envoiEnCours = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly afficherFormulaire = signal(false);
  readonly anneeDotation = signal(new Date().getFullYear());
  readonly generationEnCours = signal(false);
  readonly dernierResultat = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    compteId: ['', Validators.required],
    compteAmortissementId: ['', Validators.required],
    libelle: ['', Validators.required],
    dateAcquisition: ['', Validators.required],
    valeurOrigine: ['', Validators.required],
    dureeAnnees: ['5', Validators.required],
    mode: this.fb.nonNullable.control<ModeAmortissement>('LINEAIRE', Validators.required),
  });

  ngOnInit(): void {
    this.comptabiliteService.listerComptes(this.entrepriseId()).subscribe((comptes) => {
      this.comptesActif.set(comptes.filter((c) => c.numero.startsWith('2') && !c.numero.startsWith('28')));
      this.comptesAmortissement.set(comptes.filter((c) => c.numero.startsWith('28')));
    });
    this.rafraichir();
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.comptabiliteService
      .listerImmobilisations(this.entrepriseId())
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (liste) => this.immobilisations.set(liste),
        error: () => this.erreur.set('Impossible de charger les immobilisations.'),
      });
  }

  basculer(immo: ImmobilisationDto): void {
    this.immobilisationOuverte.set(this.immobilisationOuverte() === immo.id ? null : immo.id);
  }

  creer(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    const { compteId, compteAmortissementId, libelle, dateAcquisition, valeurOrigine, dureeAnnees, mode } =
      this.form.getRawValue();
    this.comptabiliteService
      .creerImmobilisation(this.entrepriseId(), {
        compteId,
        compteAmortissementId,
        libelle,
        dateAcquisition,
        valeurOrigine: Number(valeurOrigine),
        dureeAnnees: Number(dureeAnnees),
        mode,
      })
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (immo) => {
          this.immobilisations.update((liste) => [...liste, immo]);
          this.form.reset({ compteId: '', compteAmortissementId: '', libelle: '', dateAcquisition: '', valeurOrigine: '', dureeAnnees: '5', mode: 'LINEAIRE' });
          this.afficherFormulaire.set(false);
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de créer cette immobilisation.'),
      });
  }

  genererDotations(): void {
    this.generationEnCours.set(true);
    this.erreur.set(null);
    this.dernierResultat.set(null);
    this.comptabiliteService
      .genererDotations(this.entrepriseId(), this.anneeDotation())
      .pipe(finalize(() => this.generationEnCours.set(false)))
      .subscribe({
        next: (resultat) => {
          this.dernierResultat.set(
            resultat.nombreImmobilisations === 0
              ? `Aucune dotation à générer pour ${resultat.annee} (déjà générées ou hors plan).`
              : `${resultat.nombreImmobilisations} dotation(s) générée(s) pour ${resultat.annee}, total ${resultat.totalDotations}.`,
          );
          this.rafraichir();
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de générer les dotations.'),
      });
  }
}
