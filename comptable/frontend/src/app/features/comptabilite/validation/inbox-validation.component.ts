import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { forkJoin } from 'rxjs';
import { finalize, map } from 'rxjs/operators';
import { AideContextuelleComponent } from '../../../core/aide/aide-contextuelle.component';
import { ComptabiliteService } from '../comptabilite.service';
import { DocumentAgentService } from '../document-agent.service';
import { CompteComptableDto } from '../models/comptabilite.model';
import { DocumentEnAttenteValidationDto, ValiderDocumentRequest } from '../models/document-agent.model';

/**
 * Inbox de validation par lot (Sprint 4) : pieces WhatsApp/upload extraites par le VLM et en
 * pause dans le pipeline agentique (noeud attente_validation), en attente de revue humaine.
 * "Valider" ne comptabilise jamais directement (pas de choix de tiers/compte ici, à dessein -
 * cf. commentaire sur validerDocumentActif) : ça persiste les corrections eventuelles et reprend
 * le graphe LangGraph4j, qui retente une comptabilisation automatique (rarement possible
 * aujourd'hui) ou laisse la piece prete pour une comptabilisation manuelle classique.
 */
@Component({
  selector: 'app-inbox-validation',
  standalone: true,
  imports: [FormsModule, DecimalPipe, DatePipe, AideContextuelleComponent],
  templateUrl: './inbox-validation.component.html',
  styleUrl: './inbox-validation.component.scss',
})
export class InboxValidationComponent implements OnInit, OnDestroy {
  private readonly documentAgentService = inject(DocumentAgentService);
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly documents = signal<DocumentEnAttenteValidationDto[]>([]);
  readonly comptes = signal<CompteComptableDto[]>([]);
  readonly selection = signal<Set<string>>(new Set());
  readonly documentActifId = signal<string | null>(null);
  readonly imageUrl = signal<string | null>(null);
  readonly chargement = signal(false);
  readonly chargementApercu = signal(false);
  readonly envoiEnCours = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly succes = signal<string | null>(null);

  // Formulaire d'edition de la piece active - simples champs, pas de ReactiveForms ici pour
  // rester coherent avec le reste du module comptabilite (FormsModule + ngModel partout).
  // string | number : valeur initiale texte (pre-remplissage), mais le NumberValueAccessor
  // d'Angular ecrit un number des que l'utilisateur modifie un champ &lt;input type="number"&gt;.
  montantHt: string | number = '';
  montantTva: string | number = '';
  montantTtc: string | number = '';
  datePiece = '';
  compteSuggereId = '';

  private objectUrlActuelle: string | null = null;

  readonly documentActif = computed(() => this.documents().find((d) => d.id === this.documentActifId()) ?? null);
  readonly tailleSelection = computed(() => this.selection().size);
  readonly toutEstSelectionne = computed(
    () => this.documents().length > 0 && this.selection().size === this.documents().length,
  );

  ngOnInit(): void {
    this.comptabiliteService.listerComptes(this.entrepriseId()).subscribe((comptes) => this.comptes.set(comptes));
    this.rafraichir();
  }

  ngOnDestroy(): void {
    this.revoquerApercu();
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.erreur.set(null);
    this.documentAgentService
      .listerEnAttenteValidation(this.entrepriseId())
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (documents) => {
          this.documents.set(documents);
          const idsExistants = new Set(documents.map((d) => d.id));
          this.selection.update((s) => new Set([...s].filter((id) => idsExistants.has(id))));
          const actifId = this.documentActifId();
          if (actifId && !idsExistants.has(actifId)) {
            this.fermerDetail();
          }
        },
        error: () => this.erreur.set('Impossible de charger les pièces en attente de validation.'),
      });
  }

  estSelectionne(id: string): boolean {
    return this.selection().has(id);
  }

  basculerSelection(id: string, event: Event): void {
    event.stopPropagation();
    this.selection.update((s) => {
      const copie = new Set(s);
      if (copie.has(id)) {
        copie.delete(id);
      } else {
        copie.add(id);
      }
      return copie;
    });
  }

  basculerToutSelectionner(event: Event): void {
    const coche = (event.target as HTMLInputElement).checked;
    this.selection.set(coche ? new Set(this.documents().map((d) => d.id)) : new Set());
  }

  ouvrirDetail(document: DocumentEnAttenteValidationDto): void {
    this.documentActifId.set(document.id);
    this.montantHt = document.montantHt !== null ? String(document.montantHt) : '';
    this.montantTva = document.montantTva !== null ? String(document.montantTva) : '';
    this.montantTtc = document.montantTtc !== null ? String(document.montantTtc) : '';
    this.datePiece = document.datePiece ?? '';
    this.compteSuggereId = '';
    this.erreur.set(null);
    this.succes.set(null);
    this.chargerApercu(document);
  }

  fermerDetail(): void {
    this.documentActifId.set(null);
    this.revoquerApercu();
  }

  /** Motif affiche sur le badge compact de la liste : l'alerte qui a determine le score global
   * (les alertes ne sont pas triees par gravite - RapportFraude conserve l'ordre des controles). */
  alerteDuScore(document: DocumentEnAttenteValidationDto): string | null {
    const alerte = document.alertesFraude.find((a) => a.gravite === document.scoreRisqueFraude);
    return alerte?.motif ?? document.alertesFraude[0]?.motif ?? null;
  }

  /** Valide uniquement la piece ouverte dans le detail, avec les corrections eventuellement
   * saisies. N'effectue PAS la comptabilisation definitive : celle-ci exige un tiers et un compte
   * de charge/produit choisis deliberement (cf. ComptabilisationService), une action distincte et
   * volontairement hors de ce flux "zero friction" - voir l'ecran Documents pour comptabiliser
   * une fois la piece validee. compteSuggereId est une note de preparation pour le comptable, pas
   * une donnee envoyee au pipeline (aucune inference de compte n'existe cote IA aujourd'hui). */
  validerDocumentActif(): void {
    const document = this.documentActif();
    if (!document) {
      return;
    }
    this.envoyerValidation([document.id], this.construireCorrections());
  }

  validerSelection(): void {
    const ids = [...this.selection()];
    if (ids.length === 0) {
      return;
    }
    const aucuneCorrection: ValiderDocumentRequest = {
      montantHt: null,
      montantTva: null,
      montantTtc: null,
      datePiece: null,
    };
    this.envoyerValidation(ids, aucuneCorrection);
  }

  private construireCorrections(): ValiderDocumentRequest {
    return {
      montantHt: this.montantVersNombre(this.montantHt),
      montantTva: this.montantVersNombre(this.montantTva),
      montantTtc: this.montantVersNombre(this.montantTtc),
      datePiece: this.datePiece.trim() === '' ? null : this.datePiece,
    };
  }

  /** ngModel sur un &lt;input type="number"&gt; passe par le NumberValueAccessor d'Angular, qui
   * fournit un `number` (ou `null` si vide) et non le `string` declare sur les champs du
   * composant - accepter les deux evite un TypeError a l'execution malgre le typage TS. */
  private montantVersNombre(valeur: string | number | null): number | null {
    if (valeur === null || valeur === undefined || valeur === '') {
      return null;
    }
    const nombre = typeof valeur === 'number' ? valeur : Number(valeur);
    return Number.isNaN(nombre) ? null : nombre;
  }

  private envoyerValidation(ids: string[], request: ValiderDocumentRequest): void {
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    this.succes.set(null);

    const requetes = ids.map((id) => this.documentAgentService.validerEtReprendre(this.entrepriseId(), id, request));
    forkJoin(requetes)
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: () => {
          this.succes.set(ids.length > 1 ? `${ids.length} pièces validées.` : 'Pièce validée.');
          this.selection.update((s) => {
            const copie = new Set(s);
            ids.forEach((id) => copie.delete(id));
            return copie;
          });
          const actifId = this.documentActifId();
          if (actifId && ids.includes(actifId)) {
            this.fermerDetail();
          }
          this.rafraichir();
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Échec de la validation.'),
      });
  }

  private chargerApercu(document: DocumentEnAttenteValidationDto): void {
    this.revoquerApercu();
    this.chargementApercu.set(true);
    this.documentAgentService
      .telechargerFichier(this.entrepriseId(), document.id)
      .pipe(finalize(() => this.chargementApercu.set(false)))
      .subscribe({
        next: (blob) => {
          const url = URL.createObjectURL(blob);
          this.objectUrlActuelle = url;
          this.imageUrl.set(url);
        },
        error: () => this.erreur.set("Impossible de charger l'aperçu de la pièce."),
      });
  }

  private revoquerApercu(): void {
    if (this.objectUrlActuelle) {
      URL.revokeObjectURL(this.objectUrlActuelle);
      this.objectUrlActuelle = null;
    }
    this.imageUrl.set(null);
  }
}
