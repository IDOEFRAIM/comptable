import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { FinanceService } from './finance.service';
import {
  CashPositionDto,
  FinancialSnapshotDto,
  LIBELLES_CANAL,
  PrevisionTresorerieDto,
} from './models/finance.model';

function debutDuMoisCourant(): string {
  const maintenant = new Date();
  return new Date(maintenant.getFullYear(), maintenant.getMonth(), 1).toISOString().slice(0, 10);
}

function aujourdhui(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Ecran Trésorerie (mission P1.5, section 50) : la Cash Position (finance/CashPositionService)
 * et le Cash Forecast (finance/CashForecastService) sont deja construits et testes cote Java -
 * cet ecran ne fait que les afficher. Aucun palier PREDICTED n'existe (voir
 * PrevisionTresorerieDto) : seuls KNOWN et EXPECTED sont deterministes, jamais une prediction
 * fabriquee sans historique fiable.
 */
@Component({
  selector: 'app-tresorerie',
  standalone: true,
  imports: [DatePipe, DecimalPipe, RouterLink],
  templateUrl: './tresorerie.component.html',
  styleUrl: './tresorerie.component.scss',
})
export class TresorerieComponent implements OnInit {
  private readonly financeService = inject(FinanceService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly libellesCanal = LIBELLES_CANAL;

  readonly position = signal<CashPositionDto | null>(null);
  readonly chargementPosition = signal(false);
  readonly erreurPosition = signal<string | null>(null);

  readonly prevision = signal<PrevisionTresorerieDto[]>([]);
  readonly chargementPrevision = signal(false);
  readonly erreurPrevision = signal<string | null>(null);

  readonly snapshot = signal<FinancialSnapshotDto | null>(null);
  readonly chargementSnapshot = signal(false);
  readonly erreurSnapshot = signal<string | null>(null);

  readonly periodeDebut = signal(debutDuMoisCourant());
  readonly periodeFin = signal(aujourdhui());

  ngOnInit(): void {
    this.rafraichirPosition();
    this.rafraichirPrevision();
    this.rafraichirSnapshot();
  }

  rafraichirPosition(): void {
    this.chargementPosition.set(true);
    this.erreurPosition.set(null);
    this.financeService
      .obtenirPosition(this.entrepriseId())
      .pipe(finalize(() => this.chargementPosition.set(false)))
      .subscribe({
        next: (position) => this.position.set(position),
        error: () => this.erreurPosition.set('Impossible de charger la position de trésorerie.'),
      });
  }

  rafraichirPrevision(): void {
    this.chargementPrevision.set(true);
    this.erreurPrevision.set(null);
    this.financeService
      .obtenirPrevision(this.entrepriseId())
      .pipe(finalize(() => this.chargementPrevision.set(false)))
      .subscribe({
        next: (prevision) => this.prevision.set(prevision),
        error: () => this.erreurPrevision.set('Impossible de charger les prévisions de trésorerie.'),
      });
  }

  rafraichirSnapshot(): void {
    if (!this.periodeDebut() || !this.periodeFin()) {
      return;
    }
    this.chargementSnapshot.set(true);
    this.erreurSnapshot.set(null);
    this.financeService
      .obtenirSnapshot(this.entrepriseId(), this.periodeDebut(), this.periodeFin())
      .pipe(finalize(() => this.chargementSnapshot.set(false)))
      .subscribe({
        next: (snapshot) => this.snapshot.set(snapshot),
        error: () => this.erreurSnapshot.set('Impossible de charger le snapshot financier.'),
      });
  }

  changerPeriodeDebut(valeur: string): void {
    this.periodeDebut.set(valeur);
  }

  changerPeriodeFin(valeur: string): void {
    this.periodeFin.set(valeur);
  }
}
