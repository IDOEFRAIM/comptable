import { Component, EventEmitter, OnInit, Output, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, forkJoin } from 'rxjs';
import { messageErreur } from '../../core/forms/message-erreur';
import { ComptabiliteService } from './comptabilite.service';
import { CompteComptableDto, EcritureComptableDto, TiersDto, TypeTiers } from './models/comptabilite.model';

/** Formulaire de comptabilisation d'une facture d'achat/vente : choix du tiers et du
 * compte de charge/produit, seules informations que l'OCR ne peut pas deviner de facon fiable. */
@Component({
  selector: 'app-comptabiliser-modal',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './comptabiliser-modal.component.html',
  styleUrl: './comptabiliser-modal.component.scss',
})
export class ComptabiliserModalComponent implements OnInit {
  entrepriseId = input.required<string>();
  documentId = input.required<string>();
  /** 'FACTURE_ACHAT' -> tiers FOURNISSEUR + compte CHARGE ; 'FACTURE_VENTE' -> CLIENT + PRODUIT. */
  typeDocument = input.required<'FACTURE_ACHAT' | 'FACTURE_VENTE'>();

  @Output() valide = new EventEmitter<EcritureComptableDto>();
  @Output() annule = new EventEmitter<void>();

  private readonly fb = inject(FormBuilder);
  private readonly comptabiliteService = inject(ComptabiliteService);

  readonly chargement = signal(true);
  readonly envoiEnCours = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly tiers = signal<TiersDto[]>([]);
  readonly comptes = signal<CompteComptableDto[]>([]);

  readonly form = this.fb.nonNullable.group({
    tiersId: ['', Validators.required],
    compteChargeOuProduitId: ['', Validators.required],
    libelle: [''],
  });

  ngOnInit(): void {
    const typeTiers: TypeTiers = this.typeDocument() === 'FACTURE_ACHAT' ? 'FOURNISSEUR' : 'CLIENT';
    const natureAttendue = this.typeDocument() === 'FACTURE_ACHAT' ? 'CHARGE' : 'PRODUIT';

    forkJoin({
      tiers: this.comptabiliteService.listerTiers(this.entrepriseId(), typeTiers),
      comptes: this.comptabiliteService.listerComptes(this.entrepriseId()),
    })
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: ({ tiers, comptes }) => {
          this.tiers.set(tiers);
          this.comptes.set(comptes.filter((c) => c.nature === natureAttendue));
        },
        error: () => this.erreur.set('Impossible de charger les tiers/comptes.'),
      });
  }

  soumettre(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    const { tiersId, compteChargeOuProduitId, libelle } = this.form.getRawValue();

    this.comptabiliteService
      .comptabiliserDocument(this.entrepriseId(), this.documentId(), {
        tiersId,
        compteChargeOuProduitId,
        libelle: libelle || undefined,
      })
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (ecriture) => this.valide.emit(ecriture),
        error: (err) => this.erreur.set(messageErreur(err, 'Comptabilisation impossible.')),
      });
  }
}
