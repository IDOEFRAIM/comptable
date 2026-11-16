import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, ElementRef, HostListener, QueryList, ViewChildren, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { debounceTime, finalize, map } from 'rxjs/operators';
import { BrouillonLocalService } from '../../../core/forms/brouillon-local.service';
import { AvecModificationsNonEnregistrees } from '../../../core/forms/modifications-non-enregistrees.guard';
import { ComptabiliteService } from '../comptabilite.service';
import { CompteComptableDto, JournalDto, TiersDto } from '../models/comptabilite.model';

const FORMULAIRE = 'ecriture';

interface BrouillonEcriture {
  journalId: string;
  dateEcriture: string;
  libelle: string;
  numeroPiece: string;
  lignes: { compteId: string; libelle: string; debit: number; credit: number; tiersId: string }[];
}

function aujourdhui(): string {
  return new Date().toISOString().slice(0, 10);
}

@Component({
  selector: 'app-saisie-ecriture',
  standalone: true,
  imports: [DatePipe, DecimalPipe, ReactiveFormsModule, RouterLink],
  templateUrl: './saisie-ecriture.component.html',
  styleUrl: './saisie-ecriture.component.scss',
})
export class SaisieEcritureComponent implements AvecModificationsNonEnregistrees {
  private readonly fb = inject(FormBuilder);
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly brouillon = inject(BrouillonLocalService);

  /** Moment du brouillon retrouvé à l'ouverture (coupure, fermeture accidentelle) ; null si la saisie est neuve. */
  readonly brouillonRestaure = signal<number | null>(null);

  @ViewChildren('compteSelect') compteSelects!: QueryList<ElementRef<HTMLSelectElement>>;

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly journaux = signal<JournalDto[]>([]);
  readonly comptes = signal<CompteComptableDto[]>([]);
  readonly tiers = signal<TiersDto[]>([]);
  readonly envoiEnCours = signal(false);
  readonly erreur = signal<string | null>(null);
  private enregistree = false;

  readonly form = this.fb.nonNullable.group({
    journalId: ['', Validators.required],
    dateEcriture: [aujourdhui(), Validators.required],
    libelle: [''],
    numeroPiece: [''],
    lignes: this.fb.array([this.creerLigne(), this.creerLigne()]),
  });

  get lignes(): FormArray {
    return this.form.controls.lignes;
  }

  constructor() {
    const entrepriseId = this.entrepriseId();
    this.comptabiliteService.listerJournaux(entrepriseId).subscribe((j) => this.journaux.set(j));
    this.comptabiliteService.listerComptes(entrepriseId).subscribe((c) => this.comptes.set(c));
    this.comptabiliteService.listerTiers(entrepriseId).subscribe((t) => this.tiers.set(t));

    // la saisie est gardée sur l'appareil à chaque modification : une coupure de courant ne fait plus perdre l'écriture en cours
    const retrouve = this.brouillon.lire<BrouillonEcriture>(FORMULAIRE, entrepriseId);
    if (retrouve) {
      this.appliquer(retrouve.valeurs);
      this.brouillonRestaure.set(retrouve.le);
      this.form.markAsDirty();
    }
    this.form.valueChanges.pipe(debounceTime(200), takeUntilDestroyed()).subscribe(() => this.memoriser());
  }

  private appliquer(valeurs: BrouillonEcriture): void {
    while (this.lignes.length < valeurs.lignes.length) {
      this.lignes.push(this.creerLigne());
    }
    this.form.patchValue(valeurs);
  }

  private memoriser(): void {
    if (this.enregistree || !this.form.dirty) {
      return;
    }
    this.brouillon.sauver(FORMULAIRE, this.entrepriseId(), this.form.getRawValue());
  }

  /** Abandonne le brouillon retrouvé et repart d'une saisie vide. */
  recommencer(): void {
    this.brouillon.effacer(FORMULAIRE, this.entrepriseId());
    this.brouillonRestaure.set(null);
    this.lignes.clear();
    this.lignes.push(this.creerLigne());
    this.lignes.push(this.creerLigne());
    this.form.reset({ journalId: '', dateEcriture: aujourdhui(), libelle: '', numeroPiece: '' });
    this.form.markAsPristine();
  }

  /** Vrai dès qu'une saisie existe et n'est pas enregistrée : le routeur et la fermeture de l'onglet demandent alors confirmation. */
  aDesModificationsNonEnregistrees(): boolean {
    return this.form.dirty && !this.enregistree;
  }

  @HostListener('window:beforeunload', ['$event'])
  avantFermetureOnglet(evenement: BeforeUnloadEvent): void {
    if (this.aDesModificationsNonEnregistrees()) {
      evenement.preventDefault();
    }
  }

  private creerLigne() {
    return this.fb.nonNullable.group({
      compteId: ['', Validators.required],
      libelle: [''],
      debit: [0, [Validators.required, Validators.min(0)]],
      credit: [0, [Validators.required, Validators.min(0)]],
      tiersId: [''],
    });
  }

  ajouterLigne(focus = true): void {
    this.lignes.push(this.creerLigne());
    if (focus) {
      setTimeout(() => this.compteSelects.last?.nativeElement.focus());
    }
  }

  supprimerLigne(index: number): void {
    if (this.lignes.length <= 2) {
      return;
    }
    this.lignes.removeAt(index);
  }

  /** Entrée sur le débit/crédit de la dernière ligne = ajoute une ligne et y place le focus, sans soumettre le formulaire. */
  gererEntreeSurDerniereLigne(index: number, event: Event): void {
    if (index !== this.lignes.length - 1) {
      return;
    }
    event.preventDefault();
    this.ajouterLigne();
  }

  totalDebit(): number {
    return this.lignes.controls.reduce((total, ligne) => total + Number(ligne.get('debit')!.value || 0), 0);
  }

  totalCredit(): number {
    return this.lignes.controls.reduce((total, ligne) => total + Number(ligne.get('credit')!.value || 0), 0);
  }

  ecart(): number {
    return this.totalDebit() - this.totalCredit();
  }

  estEquilibree(): boolean {
    return this.totalDebit() > 0 && this.ecart() === 0;
  }

  peutSoumettre(): boolean {
    return this.form.valid && this.estEquilibree() && !this.envoiEnCours();
  }

  soumettre(): void {
    if (!this.peutSoumettre()) {
      this.form.markAllAsTouched();
      return;
    }
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    const valeurs = this.form.getRawValue();
    this.comptabiliteService
      .creerEcriture(this.entrepriseId(), {
        journalId: valeurs.journalId,
        dateEcriture: valeurs.dateEcriture,
        libelle: valeurs.libelle || undefined,
        numeroPiece: valeurs.numeroPiece || undefined,
        lignes: valeurs.lignes.map((ligne) => ({
          compteId: ligne.compteId,
          libelle: ligne.libelle || undefined,
          debit: Number(ligne.debit) || 0,
          credit: Number(ligne.credit) || 0,
          tiersId: ligne.tiersId || undefined,
        })),
      })
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: () => {
          this.enregistree = true;
          this.brouillon.effacer(FORMULAIRE, this.entrepriseId());
          this.router.navigate(['..'], { relativeTo: this.route });
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de créer cette écriture.'),
      });
  }
}
