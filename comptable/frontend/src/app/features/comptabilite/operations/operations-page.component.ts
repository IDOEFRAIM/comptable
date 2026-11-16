import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { map } from 'rxjs/operators';
import { BrouillonLocalService } from '../../../core/forms/brouillon-local.service';
import { FileAttenteService } from '../../../core/pwa/file-attente.service';
import { HorsLigneService } from '../../../core/pwa/hors-ligne.service';
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

const FORMULAIRE = 'operation';

interface BrouillonOperation {
  categorie: Categorie;
  sens: Sens;
  date: string;
  libelle: string;
  reference: string;
  montant: number;
  compteId: string;
  montantTtcAttendu: number | null;
  moyenPaiement: MoyenPaiement;
  natureAchat: NatureAchat;
  natureVente: NatureVente;
  tiersId: string;
}

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
  private readonly brouillon = inject(BrouillonLocalService);
  readonly horsLigne = inject(HorsLigneService);
  private readonly fileAttente = inject(FileAttenteService);

  /** Message affiché quand l'opération a été gardée sur l'appareil (hors ligne) au lieu d'être envoyée. */
  readonly messageEnFile = signal<string | null>(null);
  /** Propositions du dossier qui attendent une décision (dont celles issues de saisies faites hors ligne). */
  readonly propositionsEnAttente = signal<AccountingOperationProposalDto[]>([]);

  /** Moment du brouillon retrouvé à l'ouverture ; null si la saisie est neuve. */
  readonly brouillonRestaure = signal<number | null>(null);

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

  constructor() {
    // après chaque synchronisation (retour d'Internet), les opérations envoyées apparaissent dans la liste « à valider »
    effect(() => {
      this.fileAttente.synchroniseeLe();
      if (this.entrepriseId()) {
        this.chargerPropositionsEnAttente();
      }
    });
  }

  chargerPropositionsEnAttente(): void {
    this.comptabiliteService.listerPropositionsEnAttente(this.entrepriseId()).subscribe({
      next: (liste) => this.propositionsEnAttente.set(liste),
      error: () => undefined, // hors ligne sans copie locale : la liste reste telle quelle
    });
  }

  ouvrirProposition(proposition: AccountingOperationProposalDto): void {
    this.erreur.set(null);
    this.messageEnFile.set(null);
    this.proposition.set(proposition);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  ngOnInit(): void {
    const id = this.entrepriseId();
    const retrouve = this.brouillon.lire<BrouillonOperation>(FORMULAIRE, id);
    if (retrouve) {
      const b = retrouve.valeurs;
      this.categorie.set(b.categorie);
      this.sens.set(b.sens);
      Object.assign(this, { date: b.date, libelle: b.libelle, reference: b.reference, montant: b.montant, compteId: b.compteId, montantTtcAttendu: b.montantTtcAttendu, moyenPaiement: b.moyenPaiement, natureAchat: b.natureAchat, natureVente: b.natureVente, tiersId: b.tiersId });
      this.brouillonRestaure.set(retrouve.le);
    }
    this.comptabiliteService.listerTiers(id, 'FOURNISSEUR').subscribe((t) => this.fournisseurs.set(t));
    this.comptabiliteService.listerTiers(id, 'CLIENT').subscribe((t) => this.clients.set(t));
    this.comptabiliteService.listerComptes(id).subscribe((c) => this.comptes.set(c));
  }

  /** Gardé sur l'appareil à chaque modification (appelé depuis le gabarit) : une coupure de courant ne fait plus perdre la saisie en cours. */
  memoriser(): void {
    // laisse ngModel écrire la valeur saisie dans les propriétés avant de les lire
    setTimeout(() => {
      const vide = !this.tiersId && !this.libelle && !this.reference && !this.montant && !this.compteId;
      if (vide) {
        this.brouillon.effacer(FORMULAIRE, this.entrepriseId());
        return;
      }
      const valeurs: BrouillonOperation = {
        categorie: this.categorie(),
        sens: this.sens(),
        date: this.date,
        libelle: this.libelle,
        reference: this.reference,
        montant: this.montant,
        compteId: this.compteId,
        montantTtcAttendu: this.montantTtcAttendu,
        moyenPaiement: this.moyenPaiement,
        natureAchat: this.natureAchat,
        natureVente: this.natureVente,
        tiersId: this.tiersId,
      };
      this.brouillon.sauver(FORMULAIRE, this.entrepriseId(), valeurs);
    });
  }

  changerCategorie(categorie: Categorie): void {
    this.categorie.set(categorie);
    this.reinitialiserFormulaire();
    this.memoriser();
  }

  changerSens(sens: Sens): void {
    this.sens.set(sens);
    this.tiersId = '';
    this.proposition.set(null);
    this.memoriser();
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
        this.envoiEnCours.set(false);
        if ((dto as unknown as { enFileAttente?: boolean })?.enFileAttente) {
          // hors ligne : l'opération est gardée sur l'appareil ; la proposition d'écriture sera générée à la synchronisation
          this.brouillon.effacer(FORMULAIRE, entrepriseId);
          this.brouillonRestaure.set(null);
          this.reinitialiserFormulaire();
          this.messageEnFile.set('Opération enregistrée sur cet appareil. Dès le retour d’Internet, elle sera envoyée et sa proposition d’écriture vous attendra ci-dessous pour validation.');
          return;
        }
        this.messageEnFile.set(null);
        this.proposition.set(dto);
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
        this.brouillon.effacer(FORMULAIRE, this.entrepriseId());
        this.brouillonRestaure.set(null);
        this.proposition.set(dto);
        this.envoiEnCours.set(false);
        this.chargerPropositionsEnAttente();
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
        this.chargerPropositionsEnAttente();
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
    this.messageEnFile.set(null);
    this.proposition.set(null);
    this.reinitialiserFormulaire();
    this.brouillon.effacer(FORMULAIRE, this.entrepriseId());
    this.brouillonRestaure.set(null);
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
