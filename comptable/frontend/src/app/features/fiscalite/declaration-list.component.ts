import { Component, inject, OnInit, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { FiscaliteService } from './fiscalite.service';
import {
  DeclarationFiscaleDto,
  LIBELLES_FOURNISSEUR,
  LIBELLES_STATUT_DECLARATION,
  LIBELLES_STATUT_PAIEMENT,
  LIBELLES_TYPE_IMPOT,
  PaiementFiscalDto,
  PaiementFournisseur,
  TypeImpot,
} from './models/fiscalite.model';

@Component({
  selector: 'app-declaration-list',
  standalone: true,
  imports: [ReactiveFormsModule, FormsModule, RouterLink],
  templateUrl: './declaration-list.component.html',
  styleUrl: './declaration-list.component.scss',
})
export class DeclarationListComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly fiscaliteService = inject(FiscaliteService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly libellesType = LIBELLES_TYPE_IMPOT;
  readonly libellesStatut = LIBELLES_STATUT_DECLARATION;
  readonly libellesFournisseur = LIBELLES_FOURNISSEUR;
  readonly libellesStatutPaiement = LIBELLES_STATUT_PAIEMENT;
  readonly typesImpot = Object.keys(LIBELLES_TYPE_IMPOT) as TypeImpot[];
  readonly fournisseursPaiement = Object.keys(LIBELLES_FOURNISSEUR) as PaiementFournisseur[];

  readonly declarations = signal<DeclarationFiscaleDto[]>([]);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly afficherFormulaire = signal(false);
  readonly envoiEnCours = signal(false);
  readonly actionEnCours = signal<string | null>(null);
  readonly anneeEcheancier = signal(new Date().getFullYear());

  readonly paiementOuvertPour = signal<string | null>(null);
  readonly fournisseurChoisi = signal<PaiementFournisseur>('E_SYNTAXE');
  readonly telephonePayeur = signal('');
  readonly derniersPaiements = signal<Record<string, PaiementFiscalDto>>({});

  readonly form = this.fb.nonNullable.group({
    typeImpot: this.fb.nonNullable.control<TypeImpot>('AUTRE', Validators.required),
    periode: ['', Validators.required],
    dateEcheance: ['', Validators.required],
    montant: [''],
  });

  ngOnInit(): void {
    this.rafraichir();
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.fiscaliteService
      .lister(this.entrepriseId())
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (declarations) => this.declarations.set(declarations),
        error: () => this.erreur.set('Impossible de charger les déclarations.'),
      });
  }

  genererEcheancierTva(): void {
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    this.fiscaliteService
      .genererEcheancierTva(this.entrepriseId(), this.anneeEcheancier())
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (declarations) => this.declarations.set(declarations),
        error: (err) => this.erreur.set(err.error?.message ?? "Impossible de générer l'échéancier."),
      });
  }

  creer(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    const { typeImpot, periode, dateEcheance, montant } = this.form.getRawValue();
    this.fiscaliteService
      .creer(this.entrepriseId(), {
        typeImpot,
        periode,
        dateEcheance,
        montant: montant ? Number(montant) : undefined,
      })
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (declaration) => {
          this.declarations.update((liste) =>
            [...liste, declaration].sort((a, b) => a.dateEcheance.localeCompare(b.dateEcheance)),
          );
          this.form.reset({ typeImpot: 'AUTRE', periode: '', dateEcheance: '', montant: '' });
          this.afficherFormulaire.set(false);
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de créer cette déclaration.'),
      });
  }

  marquerDeposee(declaration: DeclarationFiscaleDto): void {
    this.actionEnCours.set(declaration.id);
    this.fiscaliteService
      .marquerDeposee(this.entrepriseId(), declaration.id)
      .pipe(finalize(() => this.actionEnCours.set(null)))
      .subscribe({
        next: (maj) => this.remplacer(maj),
        error: (err) => this.erreur.set(err.error?.message ?? 'Action impossible.'),
      });
  }

  marquerPayee(declaration: DeclarationFiscaleDto): void {
    this.actionEnCours.set(declaration.id);
    this.fiscaliteService
      .marquerPayee(this.entrepriseId(), declaration.id)
      .pipe(finalize(() => this.actionEnCours.set(null)))
      .subscribe({
        next: (maj) => this.remplacer(maj),
        error: (err) => this.erreur.set(err.error?.message ?? 'Action impossible.'),
      });
  }

  recalculerTva(declaration: DeclarationFiscaleDto): void {
    this.actionEnCours.set(declaration.id);
    this.erreur.set(null);
    this.fiscaliteService
      .recalculerTva(this.entrepriseId(), declaration.id)
      .pipe(finalize(() => this.actionEnCours.set(null)))
      .subscribe({
        next: (maj) => this.remplacer(maj),
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de recalculer la TVA.'),
      });
  }

  ouvrirPaiement(declaration: DeclarationFiscaleDto): void {
    this.paiementOuvertPour.set(declaration.id);
    this.fournisseurChoisi.set('E_SYNTAXE');
    this.telephonePayeur.set('');
  }

  annulerPaiement(): void {
    this.paiementOuvertPour.set(null);
  }

  payerEnLigne(declaration: DeclarationFiscaleDto): void {
    this.actionEnCours.set(declaration.id);
    this.erreur.set(null);
    this.fiscaliteService
      .initierPaiement(this.entrepriseId(), declaration.id, {
        fournisseur: this.fournisseurChoisi(),
        numeroTelephonePayeur: this.telephonePayeur() || undefined,
      })
      .pipe(finalize(() => this.actionEnCours.set(null)))
      .subscribe({
        next: (paiement) => {
          this.derniersPaiements.update((liste) => ({ ...liste, [declaration.id]: paiement }));
          this.paiementOuvertPour.set(null);
        },
        error: (err) => this.erreur.set(err.error?.message ?? "Impossible d'initier le paiement."),
      });
  }

  simulerConfirmation(declaration: DeclarationFiscaleDto, reussite: boolean): void {
    const paiement = this.derniersPaiements()[declaration.id];
    if (!paiement) {
      return;
    }
    this.actionEnCours.set(declaration.id);
    this.fiscaliteService
      .simulerConfirmationPaiement(this.entrepriseId(), declaration.id, paiement.id, reussite)
      .pipe(finalize(() => this.actionEnCours.set(null)))
      .subscribe({
        next: (maj) => {
          this.derniersPaiements.update((liste) => ({ ...liste, [declaration.id]: maj }));
          if (reussite) {
            this.rafraichir();
          }
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de confirmer ce paiement.'),
      });
  }

  private remplacer(maj: DeclarationFiscaleDto): void {
    this.declarations.update((liste) => liste.map((d) => (d.id === maj.id ? maj : d)));
  }

  classeStatut(declaration: DeclarationFiscaleDto): string {
    if (declaration.enRetard) {
      return 'badge badge-retard';
    }
    switch (declaration.statut) {
      case 'PAYEE':
        return 'badge badge-payee';
      case 'DEPOSEE':
        return 'badge badge-deposee';
      default:
        return 'badge badge-a-faire';
    }
  }
}
