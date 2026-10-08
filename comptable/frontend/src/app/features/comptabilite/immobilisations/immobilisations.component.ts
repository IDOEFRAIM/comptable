import { Component, inject, OnInit, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite.service';
import {
  CompteComptableDto,
  ImmobilisationDto,
  ModeAmortissement,
  MotifSortie,
  TiersDto,
  TypeImmobilisation,
  CandidatAcquisitionDto,
} from '../models/comptabilite.model';
import { messageErreur } from '../../../core/forms/message-erreur';

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
  readonly fournisseurs = signal<TiersDto[]>([]);
  /** Action en cours de saisie sur une immobilisation (un seul panneau ouvert a la fois). */
  readonly action = signal<{ id: string; type: 'ACQUISITION' | 'LIER' | 'MISE_EN_SERVICE' | 'SORTIE' } | null>(null);
  readonly saisieFournisseurId = signal('');
  readonly candidatsAcquisition = signal<CandidatAcquisitionDto[]>([]);
  readonly chargementCandidats = signal(false);
  /** Clé « type/id » de la facture choisie dans le sélecteur. */
  readonly saisieSource = signal('');
  readonly saisieSansFacture = signal(false);
  readonly saisieMontantTva = signal('');
  readonly saisieDate = signal('');
  readonly saisieMotif = signal<MotifSortie>('CESSION');
  readonly saisieMontantCession = signal('');
  readonly actionEnCours = signal(false);
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
    type: this.fb.nonNullable.control<TypeImmobilisation>('CORPORELLE', Validators.required),
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
    this.comptabiliteService
      .listerTiers(this.entrepriseId(), 'FOURNISSEUR')
      .subscribe((liste) => this.fournisseurs.set(liste));
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
    const { compteId, compteAmortissementId, libelle, type, dateAcquisition, valeurOrigine, dureeAnnees, mode } =
      this.form.getRawValue();
    this.comptabiliteService
      .creerImmobilisation(this.entrepriseId(), {
        compteId,
        compteAmortissementId,
        libelle,
        type,
        dateAcquisition,
        valeurOrigine: Number(valeurOrigine),
        dureeAnnees: Number(dureeAnnees),
        mode,
      })
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (immo) => {
          this.immobilisations.update((liste) => [...liste, immo]);
          this.form.reset({ compteId: '', compteAmortissementId: '', libelle: '', type: 'CORPORELLE', dateAcquisition: '', valeurOrigine: '', dureeAnnees: '5', mode: 'LINEAIRE' });
          this.afficherFormulaire.set(false);
        },
        error: (err) => this.erreur.set(messageErreur(err, 'Impossible de créer cette immobilisation.')),
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
        error: (err) => this.erreur.set(messageErreur(err, 'Impossible de générer les dotations.')),
      });
  }

  ouvrirAction(immo: ImmobilisationDto, type: 'ACQUISITION' | 'LIER' | 'MISE_EN_SERVICE' | 'SORTIE'): void {
    this.erreur.set(null);
    this.saisieDate.set('');
    this.saisieMontantTva.set('');
    this.saisieMontantCession.set('');
    this.saisieFournisseurId.set('');
    this.saisieSource.set('');
    this.candidatsAcquisition.set([]);
    this.saisieSansFacture.set(false);
    this.action.set({ id: immo.id, type });
    if (type === 'LIER') {
      this.chargementCandidats.set(true);
      this.comptabiliteService
        .candidatsAcquisition(this.entrepriseId(), immo.id)
        .pipe(finalize(() => this.chargementCandidats.set(false)))
        .subscribe({
          next: (c) => this.candidatsAcquisition.set(c),
          error: (err) => this.erreur.set(messageErreur(err, 'Impossible de charger les factures candidates.')),
        });
    }
  }

  annulerAction(): void {
    this.action.set(null);
  }

  confirmerAction(immo: ImmobilisationDto): void {
    const courante = this.action();
    if (!courante) {
      return;
    }
    this.actionEnCours.set(true);
    this.erreur.set(null);
    const id = this.entrepriseId();
    const appel =
      courante.type === 'ACQUISITION'
        ? this.comptabiliteService.comptabiliserAcquisition(id, immo.id, {
            fournisseurId: this.saisieFournisseurId(),
            montantTva: this.saisieMontantTva() ? Number(this.saisieMontantTva()) : null,
            confirmerAbsenceDeFacture: this.saisieSansFacture() || null,
          })
        : courante.type === 'LIER'
          ? this.comptabiliteService.lierAcquisition(id, immo.id, {
              sourceType: this.saisieSource().split('/')[0],
              sourceId: this.saisieSource().split('/')[1],
            })
        : courante.type === 'MISE_EN_SERVICE'
          ? this.comptabiliteService.mettreImmobilisationEnService(id, immo.id, this.saisieDate())
          : this.comptabiliteService.sortirImmobilisation(id, immo.id, {
              dateSortie: this.saisieDate(),
              motif: this.saisieMotif(),
              montantCession:
                this.saisieMotif() === 'CESSION' && this.saisieMontantCession()
                  ? Number(this.saisieMontantCession())
                  : null,
            });
    appel.pipe(finalize(() => this.actionEnCours.set(false))).subscribe({
      next: (maj) => {
        this.immobilisations.update((liste) => liste.map((i) => (i.id === maj.id ? maj : i)));
        this.action.set(null);
      },
      error: (err) => this.erreur.set(messageErreur(err, "Cette opération sur l'immobilisation a échoué.")),
    });
  }

  libelleStatut(statut: string): string {
    return statut === 'ACQUISE' ? 'Acquise' : statut === 'EN_SERVICE' ? 'En service' : 'Sortie';
  }
}
