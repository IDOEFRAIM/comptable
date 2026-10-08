import { Component, inject, input, output, signal } from '@angular/core';
import { finalize } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite/comptabilite.service';
import { SyntheseDossierDto } from './dossier-financier.model';
import { DossierFinancierService } from './dossier-financier.service';
import { SnapshotViewerComponent } from './snapshot-viewer.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { SectionHeaderComponent } from '../../shared/ui/section-header.component';
import { StatusComponent } from '../../shared/ui/status.component';

/** Ouvre un document HTML récupéré (avec jeton) dans un nouvel onglet imprimable. */
export function ouvrirHtml(html: string): void {
  const url = URL.createObjectURL(new Blob([html], { type: 'text/html;charset=utf-8' }));
  window.open(url, '_blank');
}

/**
 * Pré-finalisation et version finale. Le bouton « Finaliser » n'existe que si le backend dit {@code finalizable} (et que l'utilisateur est expert-comptable) : jamais décidé côté
 * Angular, jamais de contournement. Une fois finalisé : informations du snapshot, plus d'action de finalisation, lecture du snapshot uniquement.
 */
@Component({
  selector: 'app-onglet-finalisation',
  standalone: true,
  imports: [StatusComponent, IconComponent, SectionHeaderComponent, SnapshotViewerComponent],
  template: `
    <section class="pre-finalisation" data-zone="pre-finalisation">
      <app-section-header titre="Finalisation du dossier" />
      <ul class="checklist">
        @for (p of synthese().checklist; track p.code) {
          <li [attr.data-point]="p.code">
            <app-status [statut]="p.status" [texte]="p.label" />
            @if (detail(p.detail)) {
              <span class="ui-meta detail">{{ detail(p.detail) }}</span>
            }
          </li>
        }
      </ul>

      @if (erreur()) {
        <p class="ui-notice ui-notice--danger" data-zone="erreur-finalisation">{{ erreur() }}</p>
      }
      @if (info()) {
        <p class="ui-notice ui-notice--success">{{ info() }}</p>
      }

      @if (synthese().finalised && synthese().snapshot; as s) {
        <div class="finalise" data-zone="dossier-finalise">
          <p class="titre-final"><app-icon nom="lock" [taille]="18" /> Dossier finalisé</p>
          <dl>
            <div><dt>Date</dt><dd>{{ s.createdAt }}</dd></div>
            <div><dt>Utilisateur</dt><dd>{{ s.createdBy }}</dd></div>
          </dl>
          @if (s.liveChangedSinceFinal) {
            <p class="ui-notice ui-notice--info" data-champ="donnees-modifiees">Des données comptables ont changé depuis la finalisation. La version finale reste inchangée.</p>
          }
          <button type="button" class="ui-btn ui-btn--secondary" (click)="exporterFinal(s.id)" data-action="exporter-final"><app-icon nom="file" />Exporter le dossier final (imprimable)</button>
        </div>
      } @else {
        <div class="actions-finalisation">
          @if (synthese().exerciseStatus === 'OUVERT' && synthese().permissions.prepare) {
            <button type="button" class="ui-btn ui-btn--secondary" [disabled]="enCours()" (click)="cloturer()" data-action="cloturer">Clôturer l’exercice</button>
          }
          <button type="button" class="ui-btn ui-btn--secondary" [disabled]="enCours()" (click)="apercu()" data-action="previsualiser">Prévisualiser</button>
          @if (synthese().finalizable && synthese().permissions.finalise) {
            <button type="button" class="ui-btn ui-btn--primary" [disabled]="enCours()" (click)="confirmation.set(true)" data-action="finaliser">Finaliser le dossier</button>
          }
        </div>
        @if (!synthese().finalizable) {
          <p class="ui-meta" data-champ="non-finalisable">Finalisation bloquée : {{ synthese().blockingFindings }} point{{ synthese().blockingFindings > 1 ? 's' : '' }} bloquant{{ synthese().blockingFindings > 1 ? 's' : '' }} à traiter dans l’onglet Contrôles.</p>
        } @else if (!synthese().permissions.finalise) {
          <p class="ui-meta">La finalisation est réservée à l’expert-comptable.</p>
        }
        @if (confirmation()) {
          <div class="ui-notice ui-notice--info confirmation" data-zone="confirmation-finalisation">
            <div>
              <p><strong>La finalisation créera une version immuable du dossier financier pour cet exercice.</strong></p>
              <p>Les modifications ultérieures ne modifieront pas cette version finale.</p>
              <div class="confirmation__actions">
                <button type="button" class="ui-btn ui-btn--primary" [disabled]="enCours()" (click)="finaliser()" data-action="confirmer-finalisation">Confirmer la finalisation</button>
                <button type="button" class="ui-btn ui-btn--tertiary" (click)="confirmation.set(false)">Annuler</button>
              </div>
            </div>
          </div>
        }
      }
    </section>

    @if (synthese().snapshot; as s) {
      <app-snapshot-viewer [entrepriseId]="entrepriseId()" [snapshotId]="s.id" />
    }
  `,
  styles: [
    `
      .pre-finalisation { max-width: 760px; margin-bottom: var(--ui-s6); }
      ul.checklist { list-style: none; margin: 0 0 var(--ui-s5); padding: 0; border-top: 1px solid var(--ui-line); }
      ul.checklist li { display: flex; gap: var(--ui-s4); align-items: baseline; flex-wrap: wrap; padding: var(--ui-s2) 0; border-bottom: 1px solid var(--ui-line); }
      .actions-finalisation { display: flex; gap: var(--ui-s2); flex-wrap: wrap; align-items: center; margin-bottom: var(--ui-s3); }
      .confirmation { margin-top: var(--ui-s4); }
      .confirmation p { margin: 0 0 var(--ui-s2); }
      .confirmation__actions { display: flex; gap: var(--ui-s2); margin-top: var(--ui-s3); }
      .finalise { padding-top: var(--ui-s2); }
      .titre-final { display: flex; align-items: center; gap: var(--ui-s2); margin: 0 0 var(--ui-s3); font-size: var(--ui-fs-lg); font-weight: var(--ui-fw-semibold); }
      dl { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: var(--ui-s3) var(--ui-s5); margin: 0 0 var(--ui-s4); }
      dt { font-size: var(--ui-fs-xs); color: var(--ui-text-3); }
      dd { margin: 0; font-weight: var(--ui-fw-medium); word-break: break-all; }
    `,
  ],
})
export class OngletFinalisationComponent {
  private readonly service = inject(DossierFinancierService);
  private readonly comptabilite = inject(ComptabiliteService);

  readonly entrepriseId = input.required<string>();
  readonly exerciceId = input.required<string>();
  readonly synthese = input.required<SyntheseDossierDto>();
  readonly modifiee = output<void>();

  readonly enCours = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly info = signal<string | null>(null);
  readonly confirmation = signal(false);

  /** Détails de checklist : les statuts techniques du backend sont traduits ; un détail identique au libellé n'est pas répété. */
  detail(texte: string | null): string {
    const traduits: Record<string, string> = { READY: 'Prêt', INCOMPLETE: 'Incomplet', FINAL: 'Final', DRAFT: 'Brouillon', OUVERT: 'Ouvert', CLOTURE: 'Clôturé' };
    return texte ? (traduits[texte] ?? texte) : '';
  }

  apercu(): void {
    this.enCours.set(true);
    this.service
      .exporterBrouillon(this.entrepriseId(), this.exerciceId())
      .pipe(finalize(() => this.enCours.set(false)))
      .subscribe({ next: (html) => ouvrirHtml(html), error: (err) => this.erreur.set(err.error?.message ?? 'Prévisualisation impossible.') });
  }

  exporterFinal(snapshotId: string): void {
    this.service.exporterFinal(this.entrepriseId(), snapshotId).subscribe({
      next: (html) => ouvrirHtml(html),
      error: (err) => this.erreur.set(err.error?.message ?? 'Export impossible.'),
    });
  }

  finaliser(): void {
    this.enCours.set(true);
    this.erreur.set(null);
    this.service
      .finaliser(this.entrepriseId(), this.exerciceId())
      .pipe(finalize(() => this.enCours.set(false)))
      .subscribe({
        next: () => {
          this.confirmation.set(false);
          this.info.set('Dossier finalisé : version immuable créée.');
          this.modifiee.emit();
        },
        error: (err) => {
          this.confirmation.set(false);
          this.erreur.set(err.error?.message ?? 'Finalisation refusée.');
          this.modifiee.emit();
        },
      });
  }

  /** Clôture de l'exercice : workflow existant (confirmation irréversible ; si seuls des avertissements restent, motif d'acceptation tracé). */
  cloturer(): void {
    const s = this.synthese();
    if (!confirm(`Clôturer l'exercice ${s.debut} → ${s.fin} ?\n\nCette action est IRRÉVERSIBLE : plus aucune écriture ne pourra être saisie sur cet exercice.`)) {
      return;
    }
    this.lancerCloture();
  }

  private lancerCloture(confirmation?: { avertissementsConfirmes: boolean; motif: string }): void {
    this.enCours.set(true);
    this.erreur.set(null);
    this.comptabilite
      .cloturerExercice(this.entrepriseId(), this.exerciceId(), confirmation)
      .pipe(finalize(() => this.enCours.set(false)))
      .subscribe({
        next: () => {
          this.info.set('Exercice clôturé.');
          this.modifiee.emit();
        },
        error: (err) => {
          const message: string = err.error?.message ?? '';
          if (err.status === 409 && message.startsWith('AVERTISSEMENTS_A_CONFIRMER')) {
            const motif = prompt(`${message.replace('AVERTISSEMENTS_A_CONFIRMER : ', '')}\n\nPour clôturer malgré ces avertissements, indiquez le motif de leur acceptation (il sera enregistré) :`);
            if (motif && motif.trim()) {
              this.lancerCloture({ avertissementsConfirmes: true, motif: motif.trim() });
              return;
            }
          }
          this.erreur.set(message || 'Impossible de clôturer cet exercice.');
        },
      });
  }
}
