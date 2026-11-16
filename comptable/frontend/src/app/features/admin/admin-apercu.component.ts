import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AdminService, ApercuAdmin } from './admin.service';
import { STYLES_ADMIN } from './admin-styles';

/** Aperçu de la plateforme : l'état des comptes en un coup d'œil, avec un raccourci vers ce qui demande une action. */
@Component({
  selector: 'app-admin-apercu',
  standalone: true,
  imports: [RouterLink],
  template: `
    <div class="page">
      <header>
        <h1>Administration de la plateforme</h1>
        <p class="intro">Régulez les comptes et suivez l’activité de tous les cabinets. Chaque action est tracée dans le journal d’audit.</p>
      </header>
      @if (erreur()) {
        <p class="erreur" role="alert">{{ erreur() }}</p>
      }
      @if (apercu(); as a) {
        <div class="cartes" data-zone="apercu-admin">
          <a class="carte" routerLink="/admin/utilisateurs"><strong>{{ a.cabinets }}</strong><span>cabinets</span></a>
          <a class="carte" routerLink="/admin/utilisateurs"><strong>{{ a.utilisateurs }}</strong><span>comptes ({{ a.actifs }} actifs)</span></a>
          <a class="carte" [class.attention]="a.suspendus > 0" routerLink="/admin/utilisateurs" [queryParams]="{ statut: 'INACTIF' }" data-zone="carte-suspendus"><strong>{{ a.suspendus }}</strong><span>suspendus</span></a>
          <a class="carte" [class.attention]="a.verrouilles > 0" routerLink="/admin/utilisateurs" [queryParams]="{ statut: 'VERROUILLE' }" data-zone="carte-verrouilles"><strong>{{ a.verrouilles }}</strong><span>verrouillés</span></a>
          <a class="carte" routerLink="/admin/utilisateurs"><strong>{{ a.jamaisConnectes }}</strong><span>jamais connectés</span></a>
          <a class="carte" routerLink="/admin/audit"><strong>{{ a.administrateurs }}</strong><span>administrateurs · voir le journal</span></a>
        </div>
      } @else if (!erreur()) {
        <p class="vide">Chargement…</p>
      }
    </div>
  `,
  styles: [
    STYLES_ADMIN,
    `
      .cartes { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 0.8rem; }
      .carte { display: flex; flex-direction: column; gap: 0.2rem; background: #fff; border: 1px solid var(--border-subtle, #eaecf0); border-radius: 10px; padding: 1rem 1.1rem; text-decoration: none; color: inherit; }
      .carte strong { font-size: 1.7rem; }
      .carte span { color: var(--text-secondary, #475467); font-size: 0.8125rem; }
      .carte:hover { border-color: var(--color-primary, #14662f); }
      .carte.attention { border-color: #f2b705; background: #fffaeb; }
    `,
  ],
})
export class AdminApercuComponent {
  private readonly admin = inject(AdminService);
  readonly apercu = signal<ApercuAdmin | null>(null);
  readonly erreur = signal<string | null>(null);

  constructor() {
    this.admin.apercu().subscribe({
      next: (a) => this.apercu.set(a),
      error: () => this.erreur.set('Impossible de charger l’aperçu. Vérifiez votre connexion.'),
    });
  }
}
