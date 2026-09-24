import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { map } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite.service';
import { CompteComptableDto, TiersDto } from '../models/comptabilite.model';
import {
  AccountingOperationProposalDto,
  AccountingOperationType,
  MoyenPaiement,
  NatureAchat,
  NatureVente,
} from '../models/operations.model';
import { PropositionPreviewComponent } from './proposition-preview.component';

type Categorie = 'ACHAT' | 'VENTE' | 'REGLEMENT' | 'AVOIR';
type Sens = 'CLIENT' | 'FOURNISSEUR';

/** Nouvelle operation courante (achat/vente/reglement/avoir) : le formulaire ne fait que
 * construire la requete envoyee au moteur de regles - jamais de calcul de TVA ni de resolution de
 * compte cote frontend, tout est decide par AccountingOperationEngine. Le comptable voit toujours
 * la proposition (comptes/debit/credit) avant de valider - voir PropositionPreviewComponent. */
@Component({
  selector: 'app-operations-page',
  standalone: true,
  imports: [CommonModule, FormsModule, PropositionPreviewComponent],
  templateUrl: './operations-page.component.html',
  styleUrl: './operations-page.component.scss',
})
export class OperationsPageComponent implements OnInit {
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly categorie = signal<Categorie>('ACHAT');
  readonly sens = signal<Sens>('CLIENT');

  readonly fournisseurs = signal<TiersDto[]>([]);
  readonly clients = signal<TiersDto[]>([]);
  readonly comptes = signal<CompteComptableDto[]>([]);

  readonly proposition = signal<AccountingOperationProposalDto | null>(null);
  readonly envoiEnCours = signal(false);
  readonly erreur = signal<string | null>(null);

  date = new Date().toISOString().slice(0, 10);
  libelle = '';
  reference = '';
  montant = 0;
  compteId = '';
  montantTtcAttendu: number | null = null;
  moyenPaiement: MoyenPaiement = 'VIREMENT';
  natureAchat: NatureAchat = 'MARCHANDISE';
  natureVente: NatureVente = 'VENTE_MARCHANDISE';
  tiersId = '';

  readonly typeOperation = computed<AccountingOperationType>(() => {
    switch (this.categorie()) {
      case 'ACHAT':
        return 'ACHAT';
      case 'VENTE':
        return 'VENTE';
      case 'REGLEMENT':
        return this.sens() === 'CLIENT' ? 'REGLEMENT_CLIENT' : 'REGLEMENT_FOURNISSEUR';
      case 'AVOIR':
        return this.sens() === 'CLIENT' ? 'AVOIR_VENTE' : 'AVOIR_ACHAT';
    }
  });

  readonly tiersDisponibles = computed<TiersDto[]>(() => {
    if (this.categorie() === 'ACHAT') {
      return this.fournisseurs();
    }
    if (this.categorie() === 'VENTE') {
      return this.clients();
    }
    return this.sens() === 'CLIENT' ? this.clients() : this.fournisseurs();
  });

  readonly libelleTiers = computed(() => (this.categorie() === 'ACHAT' ? 'Fournisseur' : this.categorie() === 'VENTE' ? 'Client' : this.sens() === 'CLIENT' ? 'Client' : 'Fournisseur'));

  readonly afficheMontantHt = computed(() => this.categorie() !== 'REGLEMENT');
  readonly afficheCompte = computed(() => this.categorie() === 'ACHAT' || this.categorie() === 'VENTE' || this.categorie() === 'AVOIR');
  readonly afficheNature = computed(() => this.categorie() === 'ACHAT' || this.categorie() === 'VENTE');
  readonly afficheMoyenPaiement = computed(() => this.categorie() === 'REGLEMENT');
  readonly afficheSens = computed(() => this.categorie() === 'REGLEMENT' || this.categorie() === 'AVOIR');

  ngOnInit(): void {
    const id = this.entrepriseId();
    this.comptabiliteService.listerTiers(id, 'FOURNISSEUR').subscribe((t) => this.fournisseurs.set(t));
    this.comptabiliteService.listerTiers(id, 'CLIENT').subscribe((t) => this.clients.set(t));
    this.comptabiliteService.listerComptes(id).subscribe((c) => this.comptes.set(c));
  }

  changerCategorie(categorie: Categorie): void {
    this.categorie.set(categorie);
    this.reinitialiserFormulaire();
  }

  changerSens(sens: Sens): void {
    this.sens.set(sens);
    this.tiersId = '';
    this.proposition.set(null);
  }

  proposer(): void {
    const entrepriseId = this.entrepriseId();
    if (!this.tiersId || !this.libelle.trim() || this.montant <= 0) {
      this.erreur.set('Veuillez renseigner le tiers, le libellé et un montant positif.');
      return;
    }
    if (this.afficheCompte() && !this.compteId) {
      this.erreur.set('Veuillez sélectionner un compte.');
      return;
    }

    this.erreur.set(null);
    this.envoiEnCours.set(true);
    this.proposition.set(null);

    const appel = (() => {
      switch (this.categorie()) {
        case 'ACHAT':
          return this.comptabiliteService.proposerAchat(entrepriseId, {
            natureAchat: this.natureAchat,
            fournisseurId: this.tiersId,
            date: this.date,
            reference: this.reference || undefined,
            libelle: this.libelle.trim(),
            montantHt: this.montant,
            compteChargeId: this.compteId,
            montantTtcAttendu: this.montantTtcAttendu ?? undefined,
          });
        case 'VENTE':
          return this.comptabiliteService.proposerVente(entrepriseId, {
            natureVente: this.natureVente,
            clientId: this.tiersId,
            date: this.date,
            reference: this.reference || undefined,
            libelle: this.libelle.trim(),
            montantHt: this.montant,
            compteProduitId: this.compteId,
            montantTtcAttendu: this.montantTtcAttendu ?? undefined,
          });
        case 'REGLEMENT':
          return this.comptabiliteService.proposerReglement(entrepriseId, {
            type: this.typeOperation() as 'REGLEMENT_CLIENT' | 'REGLEMENT_FOURNISSEUR',
            tiersId: this.tiersId,
            date: this.date,
            reference: this.reference || undefined,
            libelle: this.libelle.trim(),
            montant: this.montant,
            moyenPaiement: this.moyenPaiement,
          });
        case 'AVOIR':
          return this.comptabiliteService.proposerAvoir(entrepriseId, {
            type: this.typeOperation() as 'AVOIR_VENTE' | 'AVOIR_ACHAT',
            tiersId: this.tiersId,
            date: this.date,
            reference: this.reference || undefined,
            libelle: this.libelle.trim(),
            montantHt: this.montant,
            compteId: this.compteId,
            montantTtcAttendu: this.montantTtcAttendu ?? undefined,
          });
      }
    })();

    appel.subscribe({
      next: (dto) => {
        this.proposition.set(dto);
        this.envoiEnCours.set(false);
      },
      error: (err) => {
        this.erreur.set(err?.error?.message ?? 'Impossible de générer la proposition.');
        this.envoiEnCours.set(false);
      },
    });
  }

  validerProposition(): void {
    const proposition = this.proposition();
    if (!proposition) {
      return;
    }
    this.envoiEnCours.set(true);
    this.comptabiliteService.validerProposition(this.entrepriseId(), proposition.id).subscribe({
      next: (dto) => {
        this.proposition.set(dto);
        this.envoiEnCours.set(false);
      },
      error: (err) => {
        this.erreur.set(err?.error?.message ?? 'Impossible de valider cette proposition.');
        this.envoiEnCours.set(false);
      },
    });
  }

  rejeterProposition(motif: string): void {
    const proposition = this.proposition();
    if (!proposition) {
      return;
    }
    this.envoiEnCours.set(true);
    this.comptabiliteService.rejeterProposition(this.entrepriseId(), proposition.id, { motif }).subscribe({
      next: (dto) => {
        this.proposition.set(dto);
        this.envoiEnCours.set(false);
      },
      error: (err) => {
        this.erreur.set(err?.error?.message ?? 'Impossible de rejeter cette proposition.');
        this.envoiEnCours.set(false);
      },
    });
  }

  allerVersSaisieManuelle(): void {
    this.router.navigate(['../ecritures/nouvelle'], { relativeTo: this.route });
  }

  nouvelleOperation(): void {
    this.proposition.set(null);
    this.reinitialiserFormulaire();
  }

  private reinitialiserFormulaire(): void {
    this.tiersId = '';
    this.compteId = '';
    this.libelle = '';
    this.reference = '';
    this.montant = 0;
    this.montantTtcAttendu = null;
    this.erreur.set(null);
    this.proposition.set(null);
  }
}
