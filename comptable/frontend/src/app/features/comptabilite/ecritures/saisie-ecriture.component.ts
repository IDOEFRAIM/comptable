import { DecimalPipe } from '@angular/common';
import { Component, ElementRef, HostListener, QueryList, ViewChildren, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { AvecModificationsNonEnregistrees } from '../../../core/forms/modifications-non-enregistrees.guard';
import { ComptabiliteService } from '../comptabilite.service';
import { CompteComptableDto, JournalDto, TiersDto } from '../models/comptabilite.model';

function aujourdhui(): string {
  return new Date().toISOString().slice(0, 10);
}

@Component({
  selector: 'app-saisie-ecriture',
  standalone: true,
  imports: [DecimalPipe, ReactiveFormsModule, RouterLink],
  templateUrl: './saisie-ecriture.component.html',
  styleUrl: './saisie-ecriture.component.scss',
})
export class SaisieEcritureComponent implements AvecModificationsNonEnregistrees {
  private readonly fb = inject(FormBuilder);
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

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
          this.router.navigate(['..'], { relativeTo: this.route });
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de créer cette écriture.'),
      });
  }
}
