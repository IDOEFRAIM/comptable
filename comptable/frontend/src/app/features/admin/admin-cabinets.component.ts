import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AdminService, CabinetAdmin, CreationCabinet, MOTIF_MIN, PLANS, PageAdmin } from './admin.service';
import { STYLES_ADMIN } from './admin-styles';

const TAILLE = 25;

type Panneau = { type: 'suspendre' | 'reactiver'; cabinet: CabinetAdmin } | { type: 'abonnement'; cabinet: CabinetAdmin } | { type: 'creation' };

/** Les cabinets clients : ce qu'ils consomment, leurs limites, et la régulation (suspension, abonnement, création), chaque action avec un motif et tracée dans le journal d'audit. */
@Component({
  selector: 'app-admin-cabinets',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink],
  template: `
    <div class="page">
      <header>
        <h1>Cabinets</h1>
        <p class="intro">Abonnement, limites et accès de chaque cabinet. Suspendre un cabinet coupe l’accès de tous ses comptes sans toucher à leurs données.</p>
      </header>

      <form class="filtres" (ngSubmit)="charger(0)">
        <label>Recherche<input type="search" name="q" [(ngModel)]="q" placeholder="Nom du cabinet" data-zone="recherche-cabinet" /></label>
        <label>Statut
          <select name="statut" [(ngModel)]="statut" (change)="charger(0)" data-zone="filtre-statut-cabinet">
            <option value="">Tous</option>
            <option value="ACTIF">Actifs</option>
            <option value="SUSPENDU">Suspendus</option>
            <option value="ESSAI_EXPIRE">Essai expiré</option>
          </select>
        </label>
        <button type="submit" class="principal">Rechercher</button>
        <button type="button" (click)="ouvrirCreation()" data-action="nouveau-cabinet">+ Nouveau cabinet</button>
      </form>

      @if (erreur()) {
        <p class="erreur" role="alert">{{ erreur() }}</p>
      }

      @if (cree(); as c) {
        <div class="panneau alerte" data-zone="cabinet-cree">
          <strong>Cabinet « {{ c.cabinet.raisonSociale }} » créé</strong>
          <span>Expert-comptable : {{ c.emailExpert }}</span>
          <span class="mdp" data-zone="mot-de-passe">{{ c.motDePasseTemporaire }}</span>
          <span class="secondaire">Mot de passe temporaire, affiché une seule fois : transmettez-le par un canal sûr. L’expert devra en choisir un nouveau à sa première connexion.</span>
          <div class="actions">
            <button type="button" (click)="copier(c.motDePasseTemporaire)" data-action="copier-mot-de-passe">{{ copie() ? 'Copié ✓' : 'Copier' }}</button>
            <button type="button" (click)="cree.set(null)" data-action="fermer-cabinet-cree">J’ai noté, fermer</button>
          </div>
        </div>
      }

      @if (panneau(); as p) {
        @if (p.type === 'creation') {
          <form class="panneau" (ngSubmit)="creer()" data-zone="formulaire-creation">
            <strong>Nouveau cabinet</strong>
            <div class="grille">
              <label>Nom du cabinet<input name="raisonSociale" [(ngModel)]="nouveau.raisonSociale" data-zone="champ-raison-sociale" /></label>
              <label>Pays (2 lettres)<input name="pays" maxlength="2" [(ngModel)]="nouveau.pays" placeholder="BF" /></label>
              <label>E-mail de l’expert<input type="email" name="emailExpert" [(ngModel)]="nouveau.emailExpert" /></label>
              <label>Nom<input name="nom" [(ngModel)]="nouveau.nom" /></label>
              <label>Prénom<input name="prenom" [(ngModel)]="nouveau.prenom" /></label>
              <label>Téléphone (facultatif)<input name="telephone" [(ngModel)]="nouveau.telephone" /></label>
              <label>Plan<select name="plan" [(ngModel)]="nouveau.plan">@for (pl of plans; track pl) { <option [value]="pl">{{ pl }}</option> }</select></label>
              <label>Fin d’essai<input type="date" name="finEssai" [(ngModel)]="finEssaiSaisie" /><span class="secondaire">Vide : 30 jours si le plan est ESSAI</span></label>
              <label>Limite de membres<input type="number" min="1" name="quotaUtilisateurs" [(ngModel)]="nouveau.quotaUtilisateurs" placeholder="Illimité" /></label>
              <label>Limite de dossiers<input type="number" min="1" name="quotaDossiers" [(ngModel)]="nouveau.quotaDossiers" placeholder="Illimité" /></label>
            </div>
            @if (erreurPanneau()) {
              <p class="erreur" role="alert">{{ erreurPanneau() }}</p>
            }
            <div class="actions">
              <button type="submit" class="principal" [disabled]="enCours()" data-action="confirmer-creation">{{ enCours() ? 'Création…' : 'Créer le cabinet' }}</button>
              <button type="button" (click)="fermer()">Annuler</button>
            </div>
          </form>
        } @else if (p.type === 'abonnement') {
          <form class="panneau" (ngSubmit)="enregistrerAbonnement()" data-zone="formulaire-abonnement">
            <strong>Abonnement de {{ p.cabinet.raisonSociale }}</strong>
            <div class="grille">
              <label>Plan<select name="plan" [(ngModel)]="abo.plan" data-zone="champ-plan">@for (pl of plans; track pl) { <option [value]="pl">{{ pl }}</option> }</select></label>
              <label>Fin d’essai<input type="date" name="finEssai" [(ngModel)]="finEssaiSaisie" /></label>
              <label>Limite de membres actifs<input type="number" min="1" name="quotaUtilisateurs" [(ngModel)]="abo.quotaUtilisateurs" placeholder="Illimité" data-zone="champ-quota-utilisateurs" /></label>
              <label>Limite de dossiers actifs<input type="number" min="1" name="quotaDossiers" [(ngModel)]="abo.quotaDossiers" placeholder="Illimité" data-zone="champ-quota-dossiers" /></label>
            </div>
            <span class="secondaire">Une limite vide = pas de limite. Baisser une limite sous l’existant ne coupe rien : cela bloque seulement les ajouts suivants.</span>
            <label>Motif (obligatoire, tracé dans le journal)<textarea name="motif" rows="2" [(ngModel)]="motif" data-zone="motif-action"></textarea></label>
            @if (erreurPanneau()) {
              <p class="erreur" role="alert">{{ erreurPanneau() }}</p>
            }
            <div class="actions">
              <button type="submit" class="principal" [disabled]="enCours() || motifTropCourt()" data-action="confirmer-abonnement">{{ enCours() ? 'En cours…' : 'Enregistrer' }}</button>
              <button type="button" (click)="fermer()">Annuler</button>
            </div>
          </form>
        } @else {
          <form class="panneau" (ngSubmit)="confirmerEtat()" data-zone="confirmation-action">
            <strong>{{ p.type === 'suspendre' ? 'Suspendre' : 'Réactiver' }} le cabinet « {{ p.cabinet.raisonSociale }} »</strong>
            <span class="secondaire">
              {{ p.type === 'suspendre' ? 'Tous les comptes du cabinet sont déconnectés tout de suite et ne peuvent plus se connecter. Les données restent intactes.' : 'Les comptes du cabinet peuvent de nouveau se connecter.' }}
            </span>
            <label>Motif (obligatoire, tracé dans le journal)<textarea name="motif" rows="2" [(ngModel)]="motif" data-zone="motif-action"></textarea></label>
            @if (erreurPanneau()) {
              <p class="erreur" role="alert">{{ erreurPanneau() }}</p>
            }
            <div class="actions">
              <button type="submit" [class.danger]="p.type === 'suspendre'" [class.principal]="p.type === 'reactiver'" [disabled]="enCours() || motifTropCourt()" data-action="confirmer-action">{{ enCours() ? 'En cours…' : p.type === 'suspendre' ? 'Suspendre' : 'Réactiver' }}</button>
              <button type="button" (click)="fermer()">Annuler</button>
            </div>
          </form>
        }
      }

      @if (page(); as p) {
        @if (p.elements.length === 0) {
          <p class="vide" data-zone="aucun-cabinet">Aucun cabinet ne correspond à cette recherche.</p>
        } @else {
          <div class="tableau">
            <table data-zone="table-cabinets">
              <thead><tr><th>Cabinet</th><th>Abonnement</th><th>Utilisation</th><th>État</th><th>Dernière activité</th><th>Actions</th></tr></thead>
              <tbody>
                @for (c of p.elements; track c.id) {
                  <tr>
                    <td><strong>{{ c.raisonSociale }}</strong><br /><span class="secondaire">{{ c.slug }} · {{ c.pays }}</span></td>
                    <td>
                      <span class="badge info">{{ c.plan }}</span>
                      @if (c.essaiExpire) {
                        <span class="badge verrouille" data-zone="essai-expire">Essai expiré</span>
                      }
                      @if (c.finEssai) {
                        <br /><span class="secondaire">fin d’essai : {{ c.finEssai | date: 'dd/MM/yyyy' }}</span>
                      }
                    </td>
                    <td>
                      <span [class.limite]="atteint(c.membresActifs, c.quotaUtilisateurs)">{{ c.membresActifs }} / {{ c.quotaUtilisateurs ?? '∞' }} membres</span><br />
                      <span [class.limite]="atteint(c.dossiersActifs, c.quotaDossiers)">{{ c.dossiersActifs }} / {{ c.quotaDossiers ?? '∞' }} dossiers</span>
                    </td>
                    <td>
                      @if (c.statut === 'ACTIF') {
                        <span class="badge">Actif</span>
                      } @else {
                        <span class="badge suspendu">Suspendu</span>
                        @if (c.motifSuspension) {
                          <br /><span class="secondaire">{{ c.motifSuspension }}</span>
                        }
                      }
                    </td>
                    <td>{{ c.derniereActivite ? (c.derniereActivite | date: 'dd/MM/yyyy HH:mm') : '—' }}</td>
                    <td>
                      <div class="actions">
                        @if (c.statut === 'ACTIF') {
                          <button type="button" class="petit danger" (click)="ouvrirEtat(c, 'suspendre')" data-action="suspendre-cabinet">Suspendre</button>
                        } @else {
                          <button type="button" class="petit" (click)="ouvrirEtat(c, 'reactiver')" data-action="reactiver-cabinet">Réactiver</button>
                        }
                        <button type="button" class="petit" (click)="ouvrirAbonnement(c)" data-action="abonnement">Abonnement</button>
                        <a class="petit" routerLink="/admin/utilisateurs" [queryParams]="{ q: c.raisonSociale }">Comptes</a>
                        <a class="petit" routerLink="/admin/audit" [queryParams]="{ cabinetId: c.id }">Journal</a>
                      </div>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <div class="pagination">
            <span>{{ p.total }} cabinet(s)</span>
            <button type="button" class="petit" [disabled]="p.page === 0" (click)="charger(p.page - 1)">‹ Précédent</button>
            <span>page {{ p.page + 1 }} / {{ pages(p) }}</span>
            <button type="button" class="petit" [disabled]="p.page + 1 >= pages(p)" (click)="charger(p.page + 1)">Suivant ›</button>
          </div>
        }
      } @else if (!erreur()) {
        <p class="vide">Chargement…</p>
      }
    </div>
  `,
  styles: [
    STYLES_ADMIN,
    `
      a.petit { font-size: 0.75rem; align-self: center; }
      .grille { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 0.7rem; }
      .grille label, .panneau label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8125rem; color: var(--text-secondary, #475467); }
      .limite { color: #b42318; font-weight: 600; }
    `,
  ],
})
export class AdminCabinetsComponent {
  private readonly admin = inject(AdminService);
  private readonly route = inject(ActivatedRoute);

  readonly plans = PLANS;
  q = '';
  statut = this.route.snapshot.queryParamMap.get('statut') ?? '';
  motif = '';
  finEssaiSaisie = '';
  abo: { plan: string; quotaUtilisateurs: number | null; quotaDossiers: number | null } = { plan: 'ESSAI', quotaUtilisateurs: null, quotaDossiers: null };
  nouveau = this.vierge();

  readonly page = signal<PageAdmin<CabinetAdmin> | null>(null);
  readonly erreur = signal<string | null>(null);
  readonly panneau = signal<Panneau | null>(null);
  readonly erreurPanneau = signal<string | null>(null);
  readonly enCours = signal(false);
  readonly cree = signal<CreationCabinet | null>(null);
  readonly copie = signal(false);

  constructor() {
    this.charger(0);
  }

  private vierge() {
    return { raisonSociale: '', pays: '', emailExpert: '', nom: '', prenom: '', telephone: '', plan: 'ESSAI', quotaUtilisateurs: null as number | null, quotaDossiers: null as number | null };
  }

  pages(p: PageAdmin<CabinetAdmin>): number {
    return Math.max(1, Math.ceil(p.total / p.taille));
  }

  /** Limite atteinte : la consommation rejoint la limite (une limite vide ne s'atteint jamais). */
  atteint(utilise: number, limite: number | null): boolean {
    return limite !== null && utilise >= limite;
  }

  charger(page: number): void {
    this.erreur.set(null);
    this.admin.cabinets({ q: this.q.trim(), statut: this.statut, page, taille: TAILLE }).subscribe({
      next: (p) => this.page.set(p),
      error: (e) => this.erreur.set(e?.error?.message ?? 'Impossible de charger les cabinets.'),
    });
  }

  motifTropCourt(): boolean {
    return this.motif.trim().length < MOTIF_MIN;
  }

  private ouvrir(panneau: Panneau): void {
    this.cree.set(null);
    this.motif = '';
    this.erreurPanneau.set(null);
    this.panneau.set(panneau);
  }

  fermer(): void {
    this.panneau.set(null);
  }

  ouvrirEtat(cabinet: CabinetAdmin, type: 'suspendre' | 'reactiver'): void {
    this.ouvrir({ type, cabinet });
  }

  ouvrirAbonnement(cabinet: CabinetAdmin): void {
    this.abo = { plan: cabinet.plan, quotaUtilisateurs: cabinet.quotaUtilisateurs, quotaDossiers: cabinet.quotaDossiers };
    this.finEssaiSaisie = cabinet.finEssai ?? '';
    this.ouvrir({ type: 'abonnement', cabinet });
  }

  ouvrirCreation(): void {
    this.nouveau = this.vierge();
    this.finEssaiSaisie = '';
    this.ouvrir({ type: 'creation' });
  }

  confirmerEtat(): void {
    const p = this.panneau();
    if (!p || p.type === 'abonnement' || p.type === 'creation' || this.motifTropCourt()) {
      return;
    }
    this.appeler(this.admin.agirCabinet(p.cabinet.id, p.type, this.motif.trim()), (c) => this.remplacer(c));
  }

  enregistrerAbonnement(): void {
    const p = this.panneau();
    if (!p || p.type !== 'abonnement' || this.motifTropCourt()) {
      return;
    }
    this.appeler(
      this.admin.modifierAbonnement(p.cabinet.id, { plan: this.abo.plan, finEssai: this.finEssaiSaisie || null, quotaUtilisateurs: this.abo.quotaUtilisateurs || null, quotaDossiers: this.abo.quotaDossiers || null, motif: this.motif.trim() }),
      (c) => this.remplacer(c),
    );
  }

  creer(): void {
    const n = this.nouveau;
    this.appeler(
      this.admin.creerCabinet({ ...n, telephone: n.telephone || undefined, finEssai: this.finEssaiSaisie || null, quotaUtilisateurs: n.quotaUtilisateurs || null, quotaDossiers: n.quotaDossiers || null }),
      (resultat) => {
        this.cree.set(resultat);
        this.copie.set(false);
        this.charger(0);
      },
    );
  }

  private appeler<T>(appel: import('rxjs').Observable<T>, succes: (r: T) => void): void {
    this.enCours.set(true);
    this.erreurPanneau.set(null);
    appel.subscribe({
      next: (r) => {
        succes(r);
        this.panneau.set(null);
        this.enCours.set(false);
      },
      error: (e) => {
        this.erreurPanneau.set(e?.error?.message ?? 'Action impossible.');
        this.enCours.set(false);
      },
    });
  }

  /** La ligne modifiée est remplacée sur place : la recherche et la page restent. */
  private remplacer(c: CabinetAdmin): void {
    this.page.update((p) => (p ? { ...p, elements: p.elements.map((x) => (x.id === c.id ? c : x)) } : p));
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
