import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { finalize } from 'rxjs/operators';
import { TelemetryService } from './telemetry.service';
import { ProductKpiDto } from './models/telemetry.model';

/**
 * "Quality Dashboard" (mission P1.9, section 24) : repond aux 4 questions du chantier avec des
 * donnees reelles - destine au produit/cabinet, pas au client final (section 24). Chaque KPI
 * affiche son echantillon (N) a cote du pourcentage, jamais un chiffre nu sur un petit
 * echantillon (voir docs/P1_9_AUDIT.md section H).
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

  readonly kpi = signal<ProductKpiDto | null>(null);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);

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
