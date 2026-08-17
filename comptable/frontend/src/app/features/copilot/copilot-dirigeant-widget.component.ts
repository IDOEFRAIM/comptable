import { Component, Input, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs/operators';
import { CopilotService } from './copilot.service';
import { CopilotFeedback, CopilotResponseDto } from './models/copilot.model';

/**
 * "Copilot Dirigeant" (mission P1.5, section 46) : variante simplifiee du widget Copilot pour le
 * dirigeant (role CLIENT) - memes DTO/HTTP contract que {@link CopilotService}, mais sans les
 * affordances "preuves"/"% de confiance" du widget comptable (trop techniques pour un non-
 * comptable). Un composant separe plutot qu'un mode supplementaire sur `CopilotWidgetComponent`
 * pour ne pas alourdir ce dernier avec une logique d'audience qui ne concerne qu'une seule
 * persona (voir docs/P1_5_ARCHITECTURE.md).
 */
@Component({
  selector: 'app-copilot-dirigeant-widget',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './copilot-dirigeant-widget.component.html',
  styleUrl: './copilot-dirigeant-widget.component.scss',
})
export class CopilotDirigeantWidgetComponent {
  @Input({ required: true }) entrepriseId!: string;

  private readonly copilotService = inject(CopilotService);

  // Doit rester synchronise avec les mots-cles de IntentClassifier.classifier (Java) : un
  // libelle "convivial" qui ne contient pas les mots-cles exacts tombe dans la reponse de repli
  // "je ne comprends pas" - bug reel trouve en testant l'endpoint en direct (mission P1.5,
  // section 78 : confirme -> corrige -> teste, voir IntentClassifierTest.
  // suggestions_du_copilot_dirigeant_restent_reconnues_par_le_classifieur).
  readonly suggestions = [
    'Mon dossier est-il prêt ?',
    "Qu'est-ce qui bloque la clôture ?",
    'Quel est mon solde bancaire ?',
    'Quelles pièces me manquent ?',
    'Quels clients me doivent de l’argent en retard ?',
    'Que dois-je faire maintenant ?',
  ];

  readonly question = signal('');
  readonly reponse = signal<CopilotResponseDto | null>(null);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly feedbackEnvoye = signal<CopilotFeedback | null>(null);

  poser(suggestion?: string): void {
    const q = (suggestion ?? this.question()).trim();
    if (!q || this.chargement()) {
      return;
    }
    this.chargement.set(true);
    this.erreur.set(null);
    this.reponse.set(null);
    this.feedbackEnvoye.set(null);
    this.copilotService
      .demanderDirigeant(this.entrepriseId, q)
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
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
