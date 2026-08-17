import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { LIBELLES_SEVERITE_FINDING } from '../dashboard/models/dashboard.model';
import { ConformiteService } from './conformite.service';
import {
  ComplianceCenterDto,
  LIBELLES_STATUT_OBLIGATION,
  ObligationFiscaleDto,
  TaxHealthDto,
} from './models/conformite.model';

/**
 * Compliance Center (mission P1.5, section 52) : agrege obligations + anomalies TAX_REVIEW +
 * tax health, tous deja calcules cote Java (fiscalite/conformite/ComplianceCenterService,
 * deja teste). Vue lecture seule uniquement - les actions (deposer/payer/generer echeancier)
 * restent sur l'ecran Fiscalite existant, jamais dupliquees ici.
 */
@Component({
  selector: 'app-conformite',
  standalone: true,
  imports: [DatePipe, DecimalPipe, RouterLink],
  templateUrl: './conformite.component.html',
  styleUrl: './conformite.component.scss',
})
export class ConformiteComponent implements OnInit {
  private readonly conformiteService = inject(ConformiteService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly libellesStatut = LIBELLES_STATUT_OBLIGATION;
  readonly libellesSeverite = LIBELLES_SEVERITE_FINDING;

  readonly centre = signal<ComplianceCenterDto | null>(null);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);

  readonly taxHealth = signal<TaxHealthDto | null>(null);
  readonly chargementTaxHealth = signal(false);
  readonly erreurTaxHealth = signal<string | null>(null);

  ngOnInit(): void {
    this.rafraichir();
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.erreur.set(null);
    this.conformiteService
      .obtenir(this.entrepriseId())
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (centre) => this.centre.set(centre),
        error: () => this.erreur.set('Impossible de charger le centre de conformité.'),
      });

    this.chargementTaxHealth.set(true);
    this.erreurTaxHealth.set(null);
    this.conformiteService
      .obtenirTaxHealth(this.entrepriseId())
      .pipe(finalize(() => this.chargementTaxHealth.set(false)))
      .subscribe({
        next: (taxHealth) => this.taxHealth.set(taxHealth),
        error: () => this.erreurTaxHealth.set('Impossible de charger le score fiscal.'),
      });
  }

  estEnRetard(obligation: ObligationFiscaleDto): boolean {
    return obligation.statut !== 'PAYEE' && obligation.dateEcheance < this.aujourdhui();
  }

  private aujourdhui(): string {
    return new Date().toISOString().slice(0, 10);
  }

  classeStatut(obligation: ObligationFiscaleDto): string {
    if (this.estEnRetard(obligation)) {
      return 'badge badge-erreur';
    }
    return obligation.statut === 'PAYEE' ? 'badge badge-succes' : 'badge badge-attente';
  }
}
