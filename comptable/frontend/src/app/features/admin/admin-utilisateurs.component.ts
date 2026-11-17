import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ActionCompte, AdminService, MOTIF_MIN, PageAdmin, UtilisateurAdmin } from './admin.service';
import { STYLES_ADMIN } from './admin-styles';

const TAILLE = 25;

interface DescriptionAction {
  titre: string;
  consigne: string;
  bouton: string;
  danger: boolean;
}

export const ACTIONS: Record<ActionCompte, DescriptionAction> = {
  suspendre: { titre: 'Suspendre ce compte', consigne: 'Le compte ne peut plus se connecter et ses sessions ouvertes sont coupées tout de suite. Ses données restent intactes.', bouton: 'Suspendre', danger: true },
  reactiver: { titre: 'Réactiver ce compte', consigne: 'Le compte peut de nouveau se connecter.', bouton: 'Réactiver', danger: false },
  deverrouiller: { titre: 'Déverrouiller ce compte', consigne: 'Remet à zéro les tentatives de connexion échouées : l’utilisateur peut réessayer tout de suite.', bouton: 'Déverrouiller', danger: false },
  'revoquer-sessions': { titre: 'Révoquer les sessions', consigne: 'Déconnecte l’utilisateur de tous ses appareils. Il pourra se reconnecter avec son mot de passe.', bouton: 'Révoquer', danger: true },
  'reinitialiser-mot-de-passe': {
    titre: 'Réinitialiser le mot de passe',
    consigne: 'Un mot de passe temporaire est créé et affiché une seule fois. L’utilisateur devra en choisir un nouveau à sa prochaine connexion ; ses sessions ouvertes sont coupées.',
    bouton: 'Réinitialiser',
    danger: true,
  },
};

/** Tous les comptes de la plateforme : recherche, statut, et les actions de régulation (chacune avec un motif obligatoire, tracée dans le journal d'audit). */
@Component({
  selector: 'app-admin-utilisateurs',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink],
  template: `
    <div class="page">
      <header>
        <h1>Utilisateurs</h1>
        <p class="intro">Comptes de tous les cabinets. Un compte administrateur ne se gère pas depuis cet écran.</p>
      </header>

      <form class="filtres" (ngSubmit)="rechercher()">
        <label>Recherche<input type="search" name="q" [(ngModel)]="q" placeholder="E-mail, nom ou cabinet" data-zone="recherche-utilisateur" /></label>
        <label>Statut
          <select name="statut" [(ngModel)]="statut" (change)="rechercher()" data-zone="filtre-statut">
            <option value="">Tous</option>
            <option value="ACTIF">Actifs</option>
            <option value="INACTIF">Suspendus</option>
            <option value="VERROUILLE">Verrouillés</option>
          </select>
        </label>
        <button type="submit" class="principal">Rechercher</button>
      </form>

      @if (erreur()) {
        <p class="erreur" role="alert">{{ erreur() }}</p>
      }

      @if (resultat(); as r) {
        <div class="panneau alerte" data-zone="mot-de-passe-temporaire">
          <strong>Mot de passe temporaire de {{ r.utilisateur.email }}</strong>
          <span class="mdp" data-zone="mot-de-passe">{{ r.motDePasseTemporaire }}</span>
          <span class="secondaire">Transmettez-le à l’utilisateur par un canal sûr. Il ne sera plus jamais affiché : s’il est perdu, réinitialisez de nouveau.</span>
          <div class="actions">
            <button type="button" (click)="copier(r.motDePasseTemporaire)" data-action="copier-mot-de-passe">{{ copie() ? 'Copié ✓' : 'Copier' }}</button>
            <button type="button" (click)="resultat.set(null)" data-action="fermer-mot-de-passe">J’ai noté, fermer</button>
          </div>
        </div>
      }

      @if (action(); as a) {
        <form class="panneau" (ngSubmit)="confirmer()" data-zone="confirmation-action">
          <strong>{{ a.description.titre }}</strong>
          <span>{{ a.cible.prenom }} {{ a.cible.nom }} · {{ a.cible.email }}</span>
          <span class="secondaire">{{ a.description.consigne }}</span>
          <label>Motif (obligatoire, tracé dans le journal)
            <textarea name="motif" rows="2" [(ngModel)]="motif" placeholder="Ex. : demande du cabinet par téléphone" data-zone="motif-action"></textarea>
          </label>
          @if (erreurAction()) {
            <p class="erreur" role="alert">{{ erreurAction() }}</p>
          }
          <div class="actions">
            <button type="submit" [class.danger]="a.description.danger" [class.principal]="!a.description.danger" [disabled]="enCours() || motifTropCourt()" data-action="confirmer-action">{{ enCours() ? 'En cours…' : a.description.bouton }}</button>
            <button type="button" (click)="annuler()" data-action="annuler-action">Annuler</button>
          </div>
        </form>
      }

      @if (page(); as p) {
        @if (p.elements.length === 0) {
          <p class="vide" data-zone="aucun-utilisateur">Aucun compte ne correspond à cette recherche.</p>
        } @else {
          <div class="tableau">
            <table data-zone="table-utilisateurs">
              <thead>
                <tr><th>Utilisateur</th><th>Cabinet</th><th>Rôle</th><th>État</th><th>Dernière connexion</th><th>Actions</th></tr>
              </thead>
              <tbody>
                @for (u of p.elements; track u.id) {
                  <tr>
                    <td><strong>{{ u.prenom }} {{ u.nom }}</strong><br /><span class="secondaire">{{ u.email }}</span></td>
                    <td>{{ u.cabinet ?? '—' }}</td>
                    <td>{{ libelleRole(u) }}</td>
                    <td>
                      @if (u.statut !== 'ACTIF') {
                        <span class="badge suspendu">Suspendu</span>
                      } @else {
                        <span class="badge">Actif</span>
                      }
                      @if (u.verrouille) {
                        <span class="badge verrouille" [title]="'Jusqu’à ' + (u.verrouilleJusqua | date: 'HH:mm')">Verrouillé</span>
                      }
                      @if (u.motDePasseAChanger) {
                        <span class="badge info">Mot de passe à changer</span>
                      }
                    </td>
                    <td>{{ u.derniereConnexion ? (u.derniereConnexion | date: 'dd/MM/yyyy HH:mm') : 'Jamais' }}</td>
                    <td>
                      @if (estAdmin(u)) {
                        <span class="secondaire">Compte administrateur</span>
                      } @else {
                        <div class="actions">
                          @if (u.statut === 'ACTIF') {
                            <button type="button" class="petit danger" (click)="ouvrir(u, 'suspendre')" data-action="suspendre">Suspendre</button>
                          } @else {
                            <button type="button" class="petit" (click)="ouvrir(u, 'reactiver')" data-action="reactiver">Réactiver</button>
                          }
                          @if (u.verrouille) {
                            <button type="button" class="petit" (click)="ouvrir(u, 'deverrouiller')" data-action="deverrouiller">Déverrouiller</button>
                          }
                          <button type="button" class="petit" (click)="ouvrir(u, 'reinitialiser-mot-de-passe')" data-action="reinitialiser">Mot de passe</button>
                          <button type="button" class="petit" (click)="ouvrir(u, 'revoquer-sessions')" data-action="revoquer">Sessions</button>
                          @if (u.cabinetId) {
                            <a class="petit" routerLink="/admin/audit" [queryParams]="{ cabinetId: u.cabinetId }">Journal</a>
                          }
                        </div>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <div class="pagination">
            <span>{{ p.total }} compte(s)</span>
            <button type="button" class="petit" [disabled]="p.page === 0" (click)="aller(p.page - 1)">‹ Précédent</button>
            <span>page {{ p.page + 1 }} / {{ pages(p) }}</span>
            <button type="button" class="petit" [disabled]="p.page + 1 >= pages(p)" (click)="aller(p.page + 1)">Suivant ›</button>
          </div>
        }
      } @else if (!erreur()) {
        <p class="vide">Chargement…</p>
      }
    </div>
  `,
  styles: [STYLES_ADMIN, `a.petit { font-size: 0.75rem; align-self: center; }`],
})
export class AdminUtilisateursComponent {
  private readonly admin = inject(AdminService);
  private readonly route = inject(ActivatedRoute);

  q = this.route.snapshot.queryParamMap.get('q') ?? '';
  statut = this.route.snapshot.queryParamMap.get('statut') ?? '';
  motif = '';

  readonly page = signal<PageAdmin<UtilisateurAdmin> | null>(null);
  readonly erreur = signal<string | null>(null);
  readonly action = signal<{ cible: UtilisateurAdmin; type: ActionCompte; description: DescriptionAction } | null>(null);
  readonly erreurAction = signal<string | null>(null);
  readonly enCours = signal(false);
  readonly resultat = signal<{ utilisateur: UtilisateurAdmin; motDePasseTemporaire: string } | null>(null);
  readonly copie = signal(false);

  constructor() {
    this.charger(0);
  }

  pages(p: PageAdmin<UtilisateurAdmin>): number {
    return Math.max(1, Math.ceil(p.total / p.taille));
  }

  rechercher(): void {
    this.charger(0);
  }

  aller(page: number): void {
    this.charger(page);
  }

  private charger(page: number): void {
    this.erreur.set(null);
    this.admin.utilisateurs({ q: this.q.trim(), statut: this.statut, page, taille: TAILLE }).subscribe({
      next: (p) => this.page.set(p),
      error: (e) => this.erreur.set(e?.error?.message ?? 'Impossible de charger les comptes.'),
    });
  }

  estAdmin(u: UtilisateurAdmin): boolean {
    return u.roles.includes('SUPER_ADMIN');
  }

  libelleRole(u: UtilisateurAdmin): string {
    const libelles: Record<string, string> = { EXPERT_COMPTABLE: 'Expert-comptable', COLLABORATEUR: 'Collaborateur', CLIENT: 'Client', SUPER_ADMIN: 'Administrateur' };
    return u.roles.map((r) => libelles[r] ?? r).join(', ') || '—';
  }

  ouvrir(cible: UtilisateurAdmin, type: ActionCompte): void {
    this.resultat.set(null);
    this.motif = '';
    this.erreurAction.set(null);
    this.action.set({ cible, type, description: ACTIONS[type] });
  }

  annuler(): void {
    this.action.set(null);
  }

  motifTropCourt(): boolean {
    return this.motif.trim().length < MOTIF_MIN;
  }

  confirmer(): void {
    const a = this.action();
    if (!a || this.motifTropCourt()) {
      return;
    }
    this.enCours.set(true);
    this.erreurAction.set(null);
    const motif = this.motif.trim();
    const fin = () => this.enCours.set(false);
    const erreur = (e: { error?: { message?: string } }) => {
      this.erreurAction.set(e?.error?.message ?? 'Action impossible.');
      fin();
    };
    if (a.type === 'reinitialiser-mot-de-passe') {
      this.admin.reinitialiser(a.cible.id, motif).subscribe({
        next: (r) => {
          this.resultat.set(r);
          this.copie.set(false);
          this.apresAction(r.utilisateur);
          fin();
        },
        error: erreur,
      });
      return;
    }
    this.admin.agir(a.cible.id, a.type, motif).subscribe({
      next: (u) => {
        this.apresAction(u);
        fin();
      },
      error: erreur,
    });
  }

  /** La ligne modifiée est remplacée sur place : pas de rechargement, la recherche et la page restent. */
  private apresAction(u: UtilisateurAdmin): void {
    this.action.set(null);
    this.page.update((p) => (p ? { ...p, elements: p.elements.map((x) => (x.id === u.id ? u : x)) } : p));
  }

  async copier(texte: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(texte);
      this.copie.set(true);
    } catch {
      this.copie.set(false); // presse-papiers refusé : le mot de passe reste sélectionnable à l'écran
    }
  }
}
