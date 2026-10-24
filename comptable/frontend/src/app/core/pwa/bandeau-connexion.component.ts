import { Component, inject, signal } from '@angular/core';
import { FileAttenteService } from './file-attente.service';
import { HorsLigneService } from './hors-ligne.service';

/** Bandeau d'état : mode hors ligne, saisies en attente d'envoi (avec leur détail) et nouvelle version prête. */
@Component({
  selector: 'app-bandeau-connexion',
  standalone: true,
  template: `
    @if (!horsLigne.enLigne()) {
      <div class="bandeau bandeau-hors-ligne" role="status" aria-live="polite" data-zone="bandeau-hors-ligne">
        <strong>Mode hors ligne.</strong> Vous consultez les dernières données enregistrées sur cet appareil. Vous pouvez encore déposer des pièces et saisir des écritures en brouillon : elles seront envoyées
        automatiquement dès le retour d’Internet. Le reste des modifications redevient possible une fois reconnecté.
      </div>
    }
    @if (file.confirmation(); as message) {
      <div class="bandeau bandeau-ok" role="status" aria-live="polite" data-zone="bandeau-confirmation-file">{{ message }}</div>
    }
    @if (file.elements().length > 0) {
      <div class="bandeau bandeau-file" role="status" aria-live="polite" data-zone="bandeau-file-attente">
        <span>
          @if (file.enAttente().length > 0) {
            <strong>{{ file.enAttente().length }}</strong> élément(s) en attente d’envoi
          }
          @if (file.refuses().length > 0) {
            <strong class="refus">{{ file.refuses().length }}</strong> refusé(s) par le serveur
          }
        </span>
        <button type="button" (click)="detailOuvert.set(!detailOuvert())" data-action="detail-file-attente">{{ detailOuvert() ? 'Masquer' : 'Détail' }}</button>
        @if (file.enAttente().length > 0 && horsLigne.enLigne()) {
          <button type="button" [disabled]="file.synchronisation()" (click)="file.synchroniser()" data-action="synchroniser">
            {{ file.synchronisation() ? 'Envoi en cours…' : 'Envoyer maintenant' }}
          </button>
        }
      </div>
      @if (detailOuvert()) {
        <ul class="liste-file" data-zone="liste-file-attente">
          @for (e of file.elements(); track e.id) {
            <li>
              <span class="etat etat-{{ e.statut }}">{{ e.statut === 'REJETE' ? 'Refusé' : e.statut === 'EN_COURS' ? 'Envoi…' : 'En attente' }}</span>
              <span class="libelle">{{ e.libelle }}</span>
              @if (e.erreur) {
                <span class="erreur">{{ e.erreur }}</span>
              }
              @if (e.statut === 'REJETE') {
                <button type="button" (click)="file.reessayer(e.id)" data-action="reessayer">Réessayer</button>
              }
              @if (e.statut !== 'EN_COURS') {
                <button type="button" (click)="supprimer(e.id)" data-action="supprimer-file">Supprimer</button>
              }
            </li>
          }
        </ul>
      }
    }
    @if (file.rapport(); as rapport) {
      <div class="bandeau bandeau-ok" role="status" data-zone="bandeau-rapport-synchronisation">
        {{ rapport }}
        <button type="button" (click)="file.rapport.set(null)">OK</button>
      </div>
    }
    @if (horsLigne.miseAJourDisponible()) {
      <div class="bandeau bandeau-maj" role="status" aria-live="polite" data-zone="bandeau-mise-a-jour">
        Une nouvelle version de Warimara est prête.
        <button type="button" (click)="horsLigne.appliquerMiseAJour()" data-action="appliquer-mise-a-jour">Mettre à jour</button>
      </div>
    }
  `,
  styles: [
    `
      .bandeau { padding: 0.5rem 1.25rem; font-size: 0.8125rem; line-height: 1.4; display: flex; gap: 0.75rem; align-items: center; justify-content: center; flex-wrap: wrap; }
      .bandeau-hors-ligne { background: #fff4d6; color: #5c3b00; border-bottom: 1px solid #f2b705; }
      .bandeau-file { background: #eff8ff; color: #0b3b6e; border-bottom: 1px solid #b2ddff; }
      .bandeau-ok { background: #ecf6ef; color: #0b3f1d; border-bottom: 1px solid #b9ddc5; }
      .bandeau-maj { background: var(--color-primary-light, #ecf6ef); color: var(--color-primary-active, #0b3f1d); border-bottom: 1px solid var(--color-primary-border, #b9ddc5); }
      .bandeau button, .liste-file button { border: 1px solid var(--color-primary, #14662f); background: var(--color-primary, #14662f); color: #fff; border-radius: 6px; padding: 0.2rem 0.7rem; font-size: 0.8125rem; cursor: pointer; }
      .bandeau button:disabled { opacity: 0.6; cursor: default; }
      .refus { color: #b42318; }
      .liste-file { list-style: none; margin: 0; padding: 0.5rem 1.25rem; background: #f5faff; border-bottom: 1px solid #b2ddff; font-size: 0.8125rem; }
      .liste-file li { display: flex; gap: 0.6rem; align-items: center; flex-wrap: wrap; padding: 0.25rem 0; }
      .liste-file .libelle { flex: 1 1 14rem; }
      .liste-file .erreur { color: #b42318; flex: 1 1 100%; }
      .liste-file button { background: #fff; color: var(--color-primary, #14662f); }
      .etat { border-radius: 999px; padding: 0.05rem 0.55rem; font-size: 0.75rem; background: #fff4d6; color: #5c3b00; }
      .etat-REJETE { background: #fee4e2; color: #b42318; }
      .etat-EN_COURS { background: #d1e9ff; color: #0b3b6e; }
    `,
  ],
})
export class BandeauConnexionComponent {
  readonly horsLigne = inject(HorsLigneService);
  readonly file = inject(FileAttenteService);
  readonly detailOuvert = signal(false);

  supprimer(id: string): void {
    if (confirm('Supprimer cet élément de la file d’attente ? Il ne sera jamais envoyé au serveur.')) {
      void this.file.supprimer(id);
    }
  }
}
