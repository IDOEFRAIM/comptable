import { Component, computed, input } from '@angular/core';
import { IconComponent } from './icon.component';

type Ton = 'ok' | 'info' | 'warning' | 'danger' | 'neutral';

interface Apparence {
  icone: string;
  texte: string;
  ton: Ton;
}

/** Libellés et tons uniques de tous les statuts de l'application : une seule source, jamais un statut décrit à deux endroits. */
const APPARENCES: Record<string, Apparence> = {
  READY: { icone: 'check', texte: 'Prêt', ton: 'ok' },
  FINAL: { icone: 'lock', texte: 'Final', ton: 'ok' },
  OK: { icone: 'check', texte: 'OK', ton: 'ok' },
  CLOTURE: { icone: 'lock', texte: 'Clôturé', ton: 'ok' },
  INCOMPLETE: { icone: 'alert-circle', texte: 'Incomplet', ton: 'danger' },
  BLOCKED: { icone: 'alert-circle', texte: 'Bloquant', ton: 'danger' },
  BLOCKING: { icone: 'alert-circle', texte: 'Bloquant', ton: 'danger' },
  WARNING: { icone: 'alert-triangle', texte: 'Avertissement', ton: 'warning' },
  DRAFT: { icone: 'edit', texte: 'Brouillon', ton: 'neutral' },
  OUVERT: { icone: 'circle', texte: 'Ouvert', ton: 'neutral' },
  MANUAL_REQUIRED: { icone: 'edit', texte: 'À renseigner', ton: 'warning' },
  TO_DETERMINE: { icone: 'circle', texte: 'À décider', ton: 'warning' },
  NOT_APPLICABLE_CANDIDATE: { icone: 'circle', texte: 'Non applicable proposé', ton: 'warning' },
  NOT_APPLICABLE: { icone: 'minus', texte: 'Non applicable', ton: 'neutral' },
  APPLICABLE: { icone: 'circle', texte: 'Applicable', ton: 'info' },
  // période déclarative (TVA)
  OPEN: { icone: 'circle', texte: 'Ouverte', ton: 'neutral' },
  PREPARING: { icone: 'edit', texte: 'En préparation', ton: 'info' },
  READY_FOR_REVIEW: { icone: 'check-circle', texte: 'Prête pour revue', ton: 'info' },
  REVIEWED: { icone: 'check', texte: 'Revue', ton: 'ok' },
  // statuts opérationnels d'un dossier du cabinet (le libellé exact vient du backend via `texte`)
  A_DEMARRER: { icone: 'circle', texte: 'À démarrer', ton: 'neutral' },
  EN_COURS: { icone: 'circle', texte: 'En cours', ton: 'info' },
  ATTENTION_REQUISE: { icone: 'alert-triangle', texte: 'Attention requise', ton: 'warning' },
  EN_ATTENTE_CLIENT: { icone: 'clock', texte: 'En attente du client', ton: 'neutral' },
  PRET_POUR_REVUE: { icone: 'check-circle', texte: 'Prêt pour revue', ton: 'info' },
  EN_REVUE: { icone: 'eye', texte: 'En revue', ton: 'info' },
  PRET_A_CLOTURER: { icone: 'check', texte: 'Prêt à clôturer', ton: 'ok' },
  FINALISE: { icone: 'lock', texte: 'Finalisé', ton: 'ok' },
};

/**
 * Composant de statut unique : une icône + un texte (jamais la couleur seule), sans fond ni contour. La couleur n'accompagne que l'icône ;
 * le texte reste anthracite pour ne pas faire « crier » l'écran. `texte` permet de préciser le libellé sans changer le statut.
 */
@Component({
  selector: 'app-status',
  standalone: true,
  imports: [IconComponent],
  template: `<span class="ui-status ui-status--{{ apparence().ton }}" [attr.data-statut]="statut()"><app-icon [nom]="apparence().icone" [taille]="14" />{{ texte() ?? apparence().texte }}@if (texte()) {<span class="ui-sr-only"> ({{ apparence().texte }})</span>}</span>`,
  styles: [`
    .ui-status { display: inline-flex; align-items: center; gap: 6px; font-size: var(--ui-fs-sm); font-weight: var(--ui-fw-medium); color: var(--ui-text); white-space: nowrap; }
    .ui-status--ok app-icon { color: var(--ui-success); }
    .ui-status--info app-icon { color: var(--ui-primary); }
    .ui-status--warning app-icon { color: var(--ui-warning); }
    .ui-status--danger app-icon { color: var(--ui-danger); }
    .ui-status--neutral { color: var(--ui-text-2); }
    .ui-status--neutral app-icon { color: var(--ui-text-3); }
  `],
})
export class StatusComponent {
  readonly statut = input.required<string>();
  readonly texte = input<string | null>(null);
  readonly apparence = computed<Apparence>(() => APPARENCES[this.statut()] ?? { icone: 'circle', texte: this.statut(), ton: 'neutral' });
}
