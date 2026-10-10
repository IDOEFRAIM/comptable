import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { finalize } from 'rxjs/operators';
import { TelemetryService } from './telemetry.service';
import { ProductKpiDto } from './models/telemetry.model';

/**
 * « Suivi du cabinet » : ce que le cabinet pilote (travail en cours, délai de réponse des clients, délai de traitement des constats), puis, repliée, la qualité de l'assistant automatique (suivi technique).
 * Audit de valeur métier (docs/audit-valeur-metier.md) : retirés de l'écran la latence, le P90, le « fallback » et les libellés anglais ; un pourcentage n'est affiché qu'à partir de 10 cas.
 */
@Component({
  selector: 'app-quality-dashboard',
  standalone: true,
  imports: [DecimalPipe],
  templateUrl: './quality-dashboard.component.html',
  styleUrl: './quality-dashboard.component.scss',
})
export class QualityDashboardComponent implements OnInit {
  private readonly telemetryService = inject(TelemetryService);

  /** En dessous, un pourcentage n'est pas significatif : seuls les nombres réels sont affichés. */
  readonly echantillonMin = 10;

  readonly kpi = signal<ProductKpiDto | null>(null);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);

  /** Pourcentage affiché seulement si l'échantillon atteint {@link echantillonMin} ; sinon « — » (jamais « 0 % » sur un ou deux cas). */
  pourcent(valeur: number | null, echantillon: number): string {
    return valeur === null || echantillon < this.echantillonMin ? '—' : `${Math.round(valeur)} %`;
  }

  ngOnInit(): void {
    this.rafraichir();
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.erreur.set(null);
    this.telemetryService
      .obtenirKpi()
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (kpi) => this.kpi.set(kpi),
        error: () => this.erreur.set('Impossible de charger les indicateurs produit.'),
      });
  }
}
