import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { BrouillonLocalService } from '../../core/forms/brouillon-local.service';
import { FileAttenteService } from '../../core/pwa/file-attente.service';
import { ElementFile } from '../../core/pwa/file-attente.store';
import { HorsLigneService } from '../../core/pwa/hors-ligne.service';
import { conseilPourRefus, reprendreSaisie } from '../../core/pwa/reprise-saisie';

const TYPES: Record<ElementFile['type'], string> = { DOCUMENT: 'Pièce', ECRITURE: 'Écriture', OPERATION: 'Saisie rapide', TIERS: 'Client / fournisseur' };

/**
 * « À résoudre » : tout ce que le serveur a refusé après une saisie sans Internet, avec la raison en clair et ce qu'il reste à faire. Une saisie refusée n'est jamais perdue : le comptable la reprend dans son
 * formulaire pour la corriger, la renvoie telle quelle, ou l'abandonne.
 */
@Component({
  selector: 'app-a-resoudre',
  standalone: true,
  imports: [DatePipe, RouterLink],
  template: `
    <div class="page">
      <header>
        <h1>À résoudre</h1>
        <p class="intro">Saisies faites sans Internet que le serveur n’a pas pu enregistrer. Aucune n’est perdue : reprenez-les pour les corriger.</p>
      </header>

      @if (refuses().length === 0) {
        <p class="vide" data-zone="rien-a-resoudre">Rien à résoudre. Tout ce que vous avez saisi est parti ou attend simplement le retour d’Internet.</p>
      }

      @for (e of refuses(); track e.id) {
        <article class="carte" data-zone="saisie-refusee">
          <div class="tete-carte">
            <span class="type">{{ type(e) }}</span>
            <strong class="libelle">{{ e.libelle }}</strong>
          </div>
          <p class="contexte">
            Dossier <strong>{{ dossier(e.entrepriseId) }}</strong> · saisi le {{ e.creeLe | date: 'dd/MM/yyyy à HH:mm' }}
          </p>
          <p class="raison" data-zone="raison-refus"><span>Pourquoi :</span> {{ e.erreur || 'Le serveur a refusé cette saisie.' }}</p>
          <p class="conseil" data-zone="conseil-refus"><span>Que faire :</span> {{ conseil(e) }}</p>
          <div class="actions">
            @if (reprenable(e)) {
              <button type="button" class="principal" (click)="reprendre(e)" data-action="reprendre-saisie">Reprendre la saisie</button>
            }
            <button type="button" (click)="reessayer(e)" [disabled]="!horsLigne.enLigne() || file.synchronisation()" data-action="reessayer-refus">Renvoyer tel quel</button>
            <button type="button" class="danger" (click)="abandonner(e)" data-action="abandonner-refus">Abandonner</button>
          </div>
        </article>
      }

      @if (enAttente().length > 0) {
        <section class="attente" data-zone="saisies-en-attente">
          <h2>En attente d’envoi ({{ enAttente().length }})</h2>
          <ul>
            @for (e of enAttente(); track e.id) {
              <li>{{ e.libelle }} <span class="dossier">— {{ dossier(e.entrepriseId) }}</span></li>
            }
          </ul>
          @if (horsLigne.enLigne()) {
            <button type="button" (click)="file.synchroniser()" [disabled]="file.synchronisation()" data-action="envoyer-maintenant">{{ file.synchronisation() ? 'Envoi en cours…' : 'Envoyer maintenant' }}</button>
          } @else {
            <p class="intro">Elles partiront automatiquement au retour d’Internet.</p>
          }
        </section>
      }

      <a routerLink="/entreprises" class="retour">← Retour aux dossiers</a>
    </div>
  `,
  styles: [
    `
      .page { max-width: 820px; margin: 0 auto; padding: 1.5rem 1rem 3rem; display: flex; flex-direction: column; gap: 1rem; }
      h1 { margin: 0; font-size: 1.4rem; }
      h2 { margin: 0 0 0.5rem; font-size: 1.05rem; }
      .intro { margin: 0.25rem 0 0; color: var(--text-secondary, #475467); font-size: 0.875rem; }
      .vide { padding: 2rem 1rem; text-align: center; color: var(--text-tertiary, #667085); background: var(--color-success-bg, #ecf6ef); border-radius: 10px; }
      .carte { background: #fff; border: 1px solid var(--color-danger-border, #fda29b); border-left: 4px solid var(--color-danger, #b42318); border-radius: 10px; padding: 1rem 1.1rem; display: flex; flex-direction: column; gap: 0.4rem; }
      .tete-carte { display: flex; gap: 0.6rem; align-items: center; flex-wrap: wrap; }
      .type { background: #fee4e2; color: #b42318; border-radius: 999px; padding: 0.05rem 0.6rem; font-size: 0.75rem; font-weight: 600; }
      .libelle { font-size: 0.95rem; }
      .contexte { margin: 0; font-size: 0.8125rem; color: var(--text-tertiary, #667085); }
      .raison, .conseil { margin: 0; font-size: 0.875rem; line-height: 1.45; }
      .raison span, .conseil span { font-weight: 600; }
      .raison { color: #b42318; }
      .actions { display: flex; gap: 0.5rem; flex-wrap: wrap; margin-top: 0.4rem; }
      button { border: 1px solid var(--color-primary, #14662f); background: #fff; color: var(--color-primary, #14662f); border-radius: 8px; padding: 0.45rem 0.9rem; font-size: 0.8125rem; font-weight: 600; cursor: pointer; }
      button.principal { background: var(--color-primary, #14662f); color: #fff; }
      button.danger { border-color: #b42318; color: #b42318; }
      button:disabled { opacity: 0.55; cursor: default; }
      .attente { background: #eff8ff; border: 1px solid #b2ddff; border-radius: 10px; padding: 0.9rem 1.1rem; }
      .attente ul { margin: 0 0 0.6rem; padding-left: 1.1rem; font-size: 0.875rem; }
      .dossier { color: var(--text-tertiary, #667085); }
      .retour { font-size: 0.875rem; }
    `,
  ],
})
export class AResoudreComponent {
  readonly file = inject(FileAttenteService);
  readonly horsLigne = inject(HorsLigneService);
  private readonly brouillon = inject(BrouillonLocalService);
  private readonly router = inject(Router);
  private readonly http = inject(HttpClient);

  readonly refuses = this.file.refuses;
  readonly enAttente = this.file.enAttente;
  private readonly noms = signal<Record<string, string>>({});

  constructor() {
    // noms des dossiers : la liste est gardée sur l'appareil, donc disponible aussi sans Internet
    this.http.get<{ id: string; raisonSociale: string }[]>('/api/entreprises').subscribe({
      next: (liste) => this.noms.set(Object.fromEntries(liste.map((d) => [d.id, d.raisonSociale]))),
      error: () => undefined,
    });
  }

  type(e: ElementFile): string {
    return TYPES[e.type];
  }

  dossier(id: string): string {
    return this.noms()[id] ?? 'dossier';
  }

  conseil(e: ElementFile): string {
    return conseilPourRefus(e);
  }

  reprenable(e: ElementFile): boolean {
    return reprendreSaisie(e) !== null;
  }

  reprendre(e: ElementFile): void {
    const reprise = reprendreSaisie(e);
    if (!reprise) {
      return;
    }
    const enCours = this.brouillon.lire(reprise.formulaire, e.entrepriseId);
    if (enCours && !confirm('Une saisie est déjà en cours dans ce formulaire. La remplacer par celle à corriger ?')) {
      return;
    }
    this.brouillon.sauver(reprise.formulaire, e.entrepriseId, reprise.valeurs);
    // la saisie vit maintenant dans le formulaire (gardée sur l'appareil à chaque modification) : elle quitte la liste des refus
    void this.file.supprimer(e.id).then(() => this.router.navigate(reprise.route));
  }

  reessayer(e: ElementFile): void {
    void this.file.reessayer(e.id);
  }

  abandonner(e: ElementFile): void {
    if (confirm('Abandonner cette saisie ? Elle ne sera jamais enregistrée sur le serveur.')) {
      void this.file.supprimer(e.id);
    }
  }
}
