import { Component, Input, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs/operators';
import { CopilotFeedback, CopilotResponseDto } from './models/copilot.model';
import { CopilotService } from './copilot.service';

/**
 * "Evidence-First AI Copilot" (mission P1, sections 14-18, 42) : jeu ferme de questions
 * structurées, jamais un chat libre - les suggestions affichées SONT les seules questions
 * garanties d'obtenir une réponse (voir IntentClassifier/CabinetIntentClassifier côté Java).
 * Réutilisable telle quelle au niveau comptable (une entreprise) ou cabinet (toutes les
 * entreprises du tenant).
 */
@Component({
  selector: 'app-copilot-widget',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './copilot-widget.component.html',
  styleUrl: './copilot-widget.component.scss',
})
export class CopilotWidgetComponent {
  @Input() portee: 'entreprise' | 'cabinet' = 'entreprise';
  @Input() entrepriseId: string | null = null;

  private readonly copilotService = inject(CopilotService);

  readonly question = signal('');
  readonly reponse = signal<CopilotResponseDto | null>(null);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly feedbackEnvoye = signal<CopilotFeedback | null>(null);

  readonly suggestions = computed(() =>
    this.portee === 'entreprise'
      ? [
          "Pourquoi ce dossier n'est-il pas prêt ?",
          "Qu'est-ce qui bloque la clôture ?",
          'Quelles sont les pièces manquantes ?',
          'Que dois-je traiter maintenant ?',
        ]
      : [
          'Quels clients sont bloqués ?',
          'Quel collaborateur a le plus gros backlog ?',
          'Quels clients ont les anomalies les plus importantes ?',
        ],
  );

  poser(suggestion?: string): void {
    const q = (suggestion ?? this.question()).trim();
    if (!q || this.chargement()) {
      return;
    }
    this.chargement.set(true);
    this.erreur.set(null);
    this.reponse.set(null);
    this.feedbackEnvoye.set(null);

    const requete =
      this.portee === 'entreprise' && this.entrepriseId
        ? this.copilotService.demanderEntreprise(this.entrepriseId, q)
        : this.copilotService.demanderCabinet(q);

    requete.pipe(finalize(() => this.chargement.set(false))).subscribe({
      next: (reponse) => {
        this.reponse.set(reponse);
        this.question.set('');
      },
      error: () => this.erreur.set('Impossible d’obtenir une réponse pour le moment.'),
    });
  }

  envoyerFeedback(feedback: CopilotFeedback): void {
    const queryId = this.reponse()?.queryId;
    if (!queryId || this.feedbackEnvoye()) {
      return;
    }
    this.feedbackEnvoye.set(feedback);
    this.copilotService.envoyerFeedback(queryId, feedback, null).subscribe({
      error: () => this.feedbackEnvoye.set(null),
    });
  }
}
