import { Component, inject, OnInit, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, finalize, map, of } from 'rxjs';
import { AideContextuelleComponent } from '../../core/aide/aide-contextuelle.component';
import { CabinetService } from '../cabinet/cabinet.service';
import { CollaborateurDto } from '../cabinet/models/cabinet.model';
import { CopilotWidgetComponent } from '../copilot/copilot-widget.component';
import { LIBELLES_TYPE_IMPOT } from '../fiscalite/models/fiscalite.model';
import { AccountingProposalDto } from './models/accounting-proposal.model';
import { DashboardService } from './dashboard.service';
import {
  ClotureReadinessDto,
  DossierComptableDto,
  FindingDto,
  LIBELLES_CLOTURE_READINESS,
  LIBELLES_SEVERITE_FINDING,
  LIBELLES_STATUT_DOSSIER,
  TableauDeBordDto,
} from './models/dashboard.model';

/** Mission P0 "Core Intelligent" : le tableau de bord existant devient le point d'entree unique
 * "etat du dossier" (section 26 - "ne pas construire plusieurs interfaces redondantes") - score
 * du Dossier Comptable Intelligent, actions prioritaires (Inbox des Finding), et Closing
 * Readiness s'ajoutent en sections au-dessus des compteurs/echeances existants, plutot que de
 * creer un ecran separe. */
@Component({
  selector: 'app-tableau-de-bord',
  standalone: true,
  imports: [RouterLink, FormsModule, AideContextuelleComponent, CopilotWidgetComponent],
  templateUrl: './tableau-de-bord.component.html',
  styleUrl: './tableau-de-bord.component.scss',
})
export class TableauDeBordComponent implements OnInit {
  private readonly dashboardService = inject(DashboardService);
  private readonly cabinetService = inject(CabinetService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly libellesType = LIBELLES_TYPE_IMPOT;
  readonly libellesSeverite = LIBELLES_SEVERITE_FINDING;
  readonly libellesStatutDossier = LIBELLES_STATUT_DOSSIER;
  readonly libellesClotureReadiness = LIBELLES_CLOTURE_READINESS;

  readonly tableau = signal<TableauDeBordDto | null>(null);
  readonly dossier = signal<DossierComptableDto | null>(null);
  readonly findings = signal<FindingDto[]>([]);
  readonly clotureReadiness = signal<ClotureReadinessDto | null>(null);
  readonly detailsOuverts = signal<Set<string>>(new Set());
  readonly collaborateurs = signal<CollaborateurDto[]>([]);
  /** Cache par sourceId de Document : undefined = pas encore charge, null = pas de proposition
   * (404 attendu - tous les Finding source DOCUMENT n'ont pas de proposition, voir
   * AccountingProposalBuilder). */
  readonly propositionsParDocument = signal<Map<string, AccountingProposalDto | null>>(new Map());

  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);

  ngOnInit(): void {
    this.rafraichir();
    this.cabinetService.obtenirCollaborateurs().subscribe({
      next: (collaborateurs) => this.collaborateurs.set(collaborateurs),
      error: () => {}, // le selecteur d'assignation reste simplement vide si indisponible
    });
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.dashboardService
      .obtenir(this.entrepriseId())
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (tableau) => this.tableau.set(tableau),
        error: () => this.erreur.set('Impossible de charger le tableau de bord.'),
      });

    this.dashboardService.obtenirDossier(this.entrepriseId()).subscribe({
      next: (dossier) => this.dossier.set(dossier),
      error: () => this.erreur.set('Impossible de charger le dossier comptable.'),
    });

    this.rafraichirFindings();

    this.dashboardService.obtenirClotureReadiness(this.entrepriseId()).subscribe({
      next: (readiness) => this.clotureReadiness.set(readiness),
      error: () => {}, // section optionnelle, ne bloque pas le reste du tableau de bord
    });
  }

  private rafraichirFindings(): void {
    this.dashboardService.listerFindings(this.entrepriseId()).subscribe({
      next: (findings) => this.findings.set(findings),
      error: () => this.erreur.set('Impossible de charger les actions prioritaires.'),
    });
  }

  basculerDetails(finding: FindingDto): void {
    const ouverts = new Set(this.detailsOuverts());
    if (ouverts.has(finding.id)) {
      ouverts.delete(finding.id);
    } else {
      ouverts.add(finding.id);
      if (finding.sourceType === 'DOCUMENT' && !this.propositionsParDocument().has(finding.sourceId)) {
        this.chargerPropositionPourDocument(finding.sourceId);
      }
    }
    this.detailsOuverts.set(ouverts);
  }

  private chargerPropositionPourDocument(documentId: string): void {
    this.dashboardService
      .obtenirPropositionPourDocument(this.entrepriseId(), documentId)
      .pipe(catchError(() => of(null)))
      .subscribe((proposition) => {
        const propositions = new Map(this.propositionsParDocument());
        propositions.set(documentId, proposition);
        this.propositionsParDocument.set(propositions);
      });
  }

  propositionPour(finding: FindingDto): AccountingProposalDto | null | undefined {
    return this.propositionsParDocument().get(finding.sourceId);
  }

  accepterProposition(finding: FindingDto, propositionId: string): void {
    this.dashboardService.accepterProposition(this.entrepriseId(), propositionId).subscribe({
      next: (proposition) => this.remplacerProposition(finding.sourceId, proposition),
      error: (err) => this.erreur.set(err.error?.message ?? 'Impossible d’accepter cette proposition.'),
    });
  }

  rejeterProposition(finding: FindingDto, propositionId: string): void {
    this.dashboardService.rejeterProposition(this.entrepriseId(), propositionId).subscribe({
      next: (proposition) => this.remplacerProposition(finding.sourceId, proposition),
      error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de rejeter cette proposition.'),
    });
  }

  private remplacerProposition(documentId: string, proposition: AccountingProposalDto): void {
    const propositions = new Map(this.propositionsParDocument());
    propositions.set(documentId, proposition);
    this.propositionsParDocument.set(propositions);
  }

  assigner(findingId: string, collaborateurId: string): void {
    this.dashboardService
      .assignerFinding(this.entrepriseId(), findingId, collaborateurId || null, null)
      .subscribe({
        next: () => this.rafraichirFindings(),
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible d’assigner ce constat.'),
      });
  }

  resoudre(findingId: string): void {
    this.dashboardService.resoudreFinding(this.entrepriseId(), findingId).subscribe({
      next: () => this.rafraichirFindings(),
      error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de résoudre ce constat.'),
    });
  }

  rejeter(findingId: string): void {
    this.dashboardService.rejeterFinding(this.entrepriseId(), findingId).subscribe({
      next: () => this.rafraichirFindings(),
      error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de rejeter ce constat.'),
    });
  }

  evidenceEntries(finding: FindingDto): [string, unknown][] {
    return finding.evidence ? Object.entries(finding.evidence) : [];
  }
}
