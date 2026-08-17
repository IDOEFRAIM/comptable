import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CopilotWidgetComponent } from '../copilot/copilot-widget.component';
import { CabinetService } from './cabinet.service';
import {
  CabinetAnalyticsDto,
  CabinetCockpitDto,
  LIBELLES_STATUT_CLIENT,
  WorkloadDto,
} from './models/cabinet.model';

/**
 * "Cabinet Cockpit" (mission P1, section 8) : vue portefeuille de l'expert-comptable/
 * collaborateur - clients à jour/à surveiller/bloqués, ce qu'il faut traiter aujourd'hui, le
 * top des risques du portefeuille, la charge par collaborateur, et le Copilot niveau cabinet.
 */
@Component({
  selector: 'app-cockpit-cabinet',
  standalone: true,
  imports: [RouterLink, CopilotWidgetComponent],
  templateUrl: './cockpit-cabinet.component.html',
  styleUrl: './cockpit-cabinet.component.scss',
})
export class CockpitCabinetComponent implements OnInit {
  private readonly cabinetService = inject(CabinetService);

  readonly libellesStatut = LIBELLES_STATUT_CLIENT;

  readonly cockpit = signal<CabinetCockpitDto | null>(null);
  readonly charge = signal<WorkloadDto[]>([]);
  readonly analytics = signal<CabinetAnalyticsDto | null>(null);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  // Etats d'erreur distincts pour les widgets secondaires (mission P1.5, section 49 - "partial
  // data") : un echec de la charge ou des analytics ne doit pas etre silencieux, mais ne doit
  // pas non plus bloquer le reste de la page comme le ferait `erreur` (reserve au cockpit
  // principal).
  readonly erreurCharge = signal<string | null>(null);
  readonly erreurAnalytics = signal<string | null>(null);

  ngOnInit(): void {
    this.chargement.set(true);
    this.erreur.set(null);

    this.cabinetService.obtenirCockpit().subscribe({
      next: (c) => {
        this.cockpit.set(c);
        this.chargement.set(false);
      },
      error: () => {
        this.erreur.set('Impossible de charger le cockpit du cabinet.');
        this.chargement.set(false);
      },
    });

    this.cabinetService.obtenirChargeCollaborateurs().subscribe({
      next: (w) => this.charge.set(w),
      error: () => this.erreurCharge.set('Impossible de charger la charge par collaborateur.'),
    });

    this.cabinetService.obtenirAnalytics().subscribe({
      next: (a) => this.analytics.set(a),
      error: () => this.erreurAnalytics.set('Impossible de charger les analytics du cabinet.'),
    });
  }
}
