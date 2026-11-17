import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AdminService, EvenementAudit, PageAdmin } from './admin.service';
import { STYLES_ADMIN } from './admin-styles';

const TAILLE = 50;

/** Libellés lisibles des actions d'administration ; les autres actions s'affichent telles qu'enregistrées. */
export const LIBELLES_ACTION: Record<string, string> = {
  ADMIN_AMORCE: 'Premier administrateur créé',
  ADMIN_UTILISATEUR_SUSPENDU: 'Compte suspendu',
  ADMIN_UTILISATEUR_REACTIVE: 'Compte réactivé',
  ADMIN_UTILISATEUR_DEVERROUILLE: 'Compte déverrouillé',
  ADMIN_SESSIONS_REVOQUEES: 'Sessions révoquées',
  ADMIN_MOT_DE_PASSE_REINITIALISE: 'Mot de passe réinitialisé',
};

/** Journal d'audit global : ce qui s'est passé dans tous les cabinets, du plus récent au plus ancien. Lecture seule. */
@Component({
  selector: 'app-admin-audit',
  standalone: true,
  imports: [DatePipe, FormsModule],
  template: `
    <div class="page">
      <header>
        <h1>Journal d’audit</h1>
        <p class="intro">Actions tracées sur toute la plateforme. Lecture seule : rien ne s’efface ni ne se modifie ici.</p>
      </header>

      <form class="filtres" (ngSubmit)="rechercher()">
        <label>Action contient<input type="search" name="action" [(ngModel)]="action" placeholder="Ex. : ADMIN_ ou MOT_DE_PASSE" data-zone="filtre-action" /></label>
        <label>Du<input type="date" name="depuis" [(ngModel)]="depuis" /></label>
        <label>Au<input type="date" name="jusqu" [(ngModel)]="jusqu" /></label>
        <button type="submit" class="principal">Filtrer</button>
        @if (cabinetId) {
          <span class="badge info" data-zone="filtre-cabinet">Un seul cabinet <button type="button" class="petit" (click)="retirerCabinet()" data-action="retirer-cabinet">✕</button></span>
        }
      </form>

      @if (erreur()) {
        <p class="erreur" role="alert">{{ erreur() }}</p>
      }

      @if (page(); as p) {
        @if (p.elements.length === 0) {
          <p class="vide" data-zone="aucun-evenement">Aucun événement ne correspond à ces filtres.</p>
        } @else {
          <div class="tableau">
            <table data-zone="table-audit">
              <thead><tr><th>Date</th><th>Action</th><th>Par</th><th>Cabinet</th><th>Détail</th></tr></thead>
              <tbody>
                @for (e of p.elements; track e.id) {
                  <tr>
                    <td>{{ e.date | date: 'dd/MM/yyyy HH:mm:ss' }}</td>
                    <td><strong>{{ libelle(e.action) }}</strong><br /><span class="secondaire">{{ e.action }}</span></td>
                    <td>{{ e.acteur ?? 'Système' }}</td>
                    <td>{{ e.cabinet ?? '—' }}</td>
                    <td>{{ detail(e) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <div class="pagination">
            <span>{{ p.total }} événement(s)</span>
            <button type="button" class="petit" [disabled]="p.page === 0" (click)="charger(p.page - 1)">‹ Plus récents</button>
            <span>page {{ p.page + 1 }} / {{ pages(p) }}</span>
            <button type="button" class="petit" [disabled]="p.page + 1 >= pages(p)" (click)="charger(p.page + 1)">Plus anciens ›</button>
          </div>
        }
      } @else if (!erreur()) {
        <p class="vide">Chargement…</p>
      }
    </div>
  `,
  styles: [STYLES_ADMIN],
})
export class AdminAuditComponent {
  private readonly admin = inject(AdminService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  action = '';
  depuis = '';
  jusqu = '';
  cabinetId = this.route.snapshot.queryParamMap.get('cabinetId') ?? '';

  readonly page = signal<PageAdmin<EvenementAudit> | null>(null);
  readonly erreur = signal<string | null>(null);

  constructor() {
    this.charger(0);
  }

  pages(p: PageAdmin<EvenementAudit>): number {
    return Math.max(1, Math.ceil(p.total / p.taille));
  }

  rechercher(): void {
    this.charger(0);
  }

  retirerCabinet(): void {
    this.cabinetId = '';
    void this.router.navigate([], { queryParams: { cabinetId: null }, queryParamsHandling: 'merge' });
    this.charger(0);
  }

  charger(page: number): void {
    this.erreur.set(null);
    this.admin.audit({ cabinetId: this.cabinetId, action: this.action.trim(), depuis: this.depuis, jusqu: this.jusqu, page, taille: TAILLE }).subscribe({
      next: (p) => this.page.set(p),
      error: (e) => this.erreur.set(e?.error?.message ?? 'Impossible de charger le journal.'),
    });
  }

  libelle(action: string): string {
    return LIBELLES_ACTION[action] ?? action;
  }

  /** « motif : … · cible : … » : les détails utiles, sans le bruit technique. */
  detail(e: EvenementAudit): string {
    const d = e.details ?? {};
    const parties: string[] = [];
    if (typeof d['motif'] === 'string') {
      parties.push(`motif : ${d['motif']}`);
    }
    if (typeof d['cible'] === 'string') {
      parties.push(`compte : ${d['cible']}`);
    }
    if (typeof d['email'] === 'string' && typeof d['cible'] !== 'string') {
      parties.push(`compte : ${d['email']}`);
    }
    if (parties.length === 0 && e.entiteId) {
      parties.push(`${e.entite} ${e.entiteId.slice(0, 8)}…`);
    }
    return parties.join(' · ') || '—';
  }
}
