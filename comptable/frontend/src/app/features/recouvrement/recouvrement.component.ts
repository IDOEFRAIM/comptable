import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { RecouvrementService } from './recouvrement.service';
import { CollectionRiskDto, LIBELLES_RISQUE_RECOUVREMENT } from './models/recouvrement.model';

/**
 * Ecran Recouvrement (mission P1.5, section 51) : liste des clients a risque
 * (recouvrement/CollectionRiskService, deja construit et teste cote Java). L'action
 * recommandee reste un texte informatif pour le collaborateur - aucun envoi automatique de
 * relance de paiement depuis cet ecran (a ne pas confondre avec les relances de pieces
 * manquantes, un flux WhatsApp distinct sous l'onglet "Pièces demandées").
 */
@Component({
  selector: 'app-recouvrement',
  standalone: true,
  imports: [DecimalPipe],
  templateUrl: './recouvrement.component.html',
  styleUrl: './recouvrement.component.scss',
})
export class RecouvrementComponent implements OnInit {
  private readonly recouvrementService = inject(RecouvrementService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly libellesRisque = LIBELLES_RISQUE_RECOUVREMENT;

  readonly risques = signal<CollectionRiskDto[]>([]);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);

  ngOnInit(): void {
    this.rafraichir();
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.erreur.set(null);
    this.recouvrementService
      .listerRisques(this.entrepriseId())
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (risques) => this.risques.set(risques),
        error: () => this.erreur.set('Impossible de charger les risques de recouvrement.'),
      });
  }

  classeRisque(niveau: string): string {
    switch (niveau) {
      case 'HIGH':
        return 'badge badge-erreur';
      case 'MEDIUM':
        return 'badge badge-attente';
      default:
        return 'badge badge-succes';
    }
  }
}
