import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TokenStorageService } from '../../core/auth/token-storage.service';
import { EmptyStateComponent } from '../../shared/ui/empty-state.component';
import { IconComponent } from '../../shared/ui/icon.component';
import { Membre, RoleMembre } from './cabinet-workspace.model';
import { CabinetWorkspaceService } from './cabinet-workspace.service';
import { ilYA } from './cabinet-workspace.util';

const ROLES: { code: RoleMembre; libelle: string }[] = [
  { code: 'COLLABORATEUR', libelle: 'Collaborateur' },
  { code: 'EXPERT_COMPTABLE', libelle: 'Expert-comptable' },
];

/**
 * Équipe du cabinet. Lecture pour tous les membres du cabinet ; ajout, changement de rôle et (dés)activation réservés à l'expert-comptable (le backend reste l'autorité : les erreurs métier
 * qu'il renvoie sont affichées telles quelles). Un membre ajouté est disponible immédiatement dans les sélecteurs d'affectation et les filtres.
 */
@Component({
  selector: 'app-equipe',
  standalone: true,
  imports: [FormsModule, EmptyStateComponent, IconComponent],
  template: `
    <div class="page-equipe" data-zone="equipe">
      <header class="entete-page">
        <div>
          <h1 class="ui-page-title">Équipe</h1>
          <p class="ui-meta" data-champ="nb-membres">{{ resume() }}</p>
        </div>
        @if (expert) {
          <button type="button" class="ui-btn ui-btn--primary" (click)="basculerFormulaire()" data-action="ajouter-membre">Ajouter un membre</button>
        }
      </header>

      @if (expert && formulaire()) {
        <form class="ui-surface formulaire" (ngSubmit)="ajouter()" data-zone="formulaire-membre" novalidate>
          <div class="ui-field"><label class="ui-field__label" for="m-prenom">Prénom<span class="ui-required" aria-hidden="true">*</span></label>
            <input id="m-prenom" name="prenom" class="ui-input" [(ngModel)]="nouveau.prenom" required autocomplete="off" data-champ="membre-prenom" /></div>
          <div class="ui-field"><label class="ui-field__label" for="m-nom">Nom<span class="ui-required" aria-hidden="true">*</span></label>
            <input id="m-nom" name="nom" class="ui-input" [(ngModel)]="nouveau.nom" required autocomplete="off" data-champ="membre-nom" /></div>
          <div class="ui-field"><label class="ui-field__label" for="m-email">E-mail<span class="ui-required" aria-hidden="true">*</span></label>
            <input id="m-email" name="email" type="email" class="ui-input" [(ngModel)]="nouveau.email" required autocomplete="off" data-champ="membre-email" />
            @if (emailInvalide()) { <span class="ui-field__hint erreur" data-champ="erreur-email">Saisissez une adresse e-mail valide.</span> }</div>
          <div class="ui-field"><label class="ui-field__label" for="m-role">Rôle</label>
            <select id="m-role" name="role" class="ui-input" [(ngModel)]="nouveau.role" data-champ="membre-role">
              @for (r of roles; track r.code) { <option [value]="r.code">{{ r.libelle }}</option> }
            </select></div>
          <div class="actions-formulaire">
            <button type="submit" class="ui-btn ui-btn--primary" [disabled]="!valide() || enCours()" data-action="enregistrer-membre">Ajouter</button>
            <button type="button" class="ui-btn ui-btn--tertiary" (click)="formulaire.set(false)">Annuler</button>
          </div>
          <span class="ui-field__hint plein">Un mot de passe temporaire est généré et affiché une seule fois : communiquez-le au membre.</span>
        </form>
      }

      @if (erreur()) {
        <p class="ui-notice ui-notice--danger" role="alert" data-champ="erreur-equipe">{{ erreur() }}</p>
      }
      @if (lienReinitialisation(); as l) {
        <div class="ui-notice ui-notice--success" role="status" data-champ="lien-reinitialisation">
          <span>Lien de réinitialisation pour {{ l.nom }} (valable jusqu’au {{ l.expire }}, à usage unique) : <strong class="ui-mono">{{ l.lien }}</strong>
            — transmettez-le au membre ; il ne sera plus affiché.</span>
        </div>
      }
      @if (motDePasse(); as m) {
        <p class="ui-notice ui-notice--success" role="status" data-champ="mot-de-passe-temporaire">
          <span>{{ m.nom }} a été ajouté. Mot de passe temporaire : <strong class="ui-mono">{{ m.motDePasse }}</strong> — il ne sera plus affiché.</span>
        </p>
      }

      @if (membres(); as liste) {
        @if (liste.length > 1 || !expert) {
          <div class="ui-surface">
            <table class="ui-table">
              <thead><tr><th>Nom</th><th>E-mail</th><th>Rôle</th><th>Statut</th><th class="ui-num">Dossiers affectés</th><th>Dernière activité</th>@if (expert) { <th>Actions</th> }</tr></thead>
              <tbody>
                @for (m of liste; track m.id) {
                  <tr [attr.data-membre]="m.email" [class.inactif]="m.statut === 'INACTIF'">
                    <td class="nom">{{ m.prenom }} {{ m.nom }}@if (m.email.toLowerCase() === moi) { <span class="ui-muted"> (vous)</span> }</td>
                    <td class="ui-muted">{{ m.email }}</td>
                    <td>
                      @if (expert && m.email.toLowerCase() !== moi) {
                        <label class="ui-sr-only" [for]="'role-' + m.id">Rôle de {{ m.prenom }} {{ m.nom }}</label>
                        <select [id]="'role-' + m.id" class="ui-input role" [ngModel]="m.role" (ngModelChange)="changerRole(m, $event)" data-action="changer-role">
                          @for (r of roles; track r.code) { <option [value]="r.code">{{ r.libelle }}</option> }
                        </select>
                      } @else { {{ libelleRole(m.role) }} }
                    </td>
                    <td><span class="statut" [class.statut--inactif]="m.statut === 'INACTIF'"><app-icon [nom]="m.statut === 'ACTIF' ? 'check' : 'minus'" [taille]="14" />{{ m.statut === 'ACTIF' ? 'Actif' : 'Désactivé' }}</span></td>
                    <td class="ui-num">{{ m.dossiersAffectes }}</td>
                    <td class="ui-muted">{{ activite(m.derniereActivite) }}</td>
                    @if (expert) {
                      <td class="col-action">
                        @if (m.email.toLowerCase() !== moi) {
                          <button type="button" class="ui-btn ui-btn--tertiary ui-btn--sm" (click)="reinitialiser(m)" data-action="reinitialiser-mot-de-passe">Réinitialiser le mot de passe</button>
                          <button type="button" class="ui-btn ui-btn--secondary ui-btn--sm" (click)="basculerStatut(m)" [attr.data-action]="m.statut === 'ACTIF' ? 'desactiver-membre' : 'reactiver-membre'">{{ m.statut === 'ACTIF' ? 'Désactiver' : 'Réactiver' }}</button>
                        }
                      </td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
        @if (aucunCollaborateur()) {
          <app-empty-state data-champ="aucun-collaborateur" icone="inbox" titre="Aucun collaborateur pour le moment." detail="Ajoutez un membre pour commencer à répartir les dossiers." />
        }
      } @else if (chargement()) {
        <p class="ui-meta" role="status">Chargement de l’équipe…</p>
      }
    </div>
  `,
  styles: [
    `
      .page-equipe { max-width: var(--ui-page-max); margin: 0 auto; padding: var(--ui-s5) var(--ui-s5) var(--ui-s7); display: flex; flex-direction: column; gap: var(--ui-s4); }
      .entete-page { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--ui-s4); }
      .entete-page p { margin: var(--ui-s1) 0 0; }
      .formulaire { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: var(--ui-s3) var(--ui-s4); padding: var(--ui-s4); align-items: start; }
      .formulaire .ui-field { margin: 0; }
      .actions-formulaire { display: flex; gap: var(--ui-s2); align-items: center; }
      .plein { grid-column: 1 / -1; }
      .erreur { color: var(--ui-danger); }
      .nom { font-weight: var(--ui-fw-semibold); white-space: nowrap; }
      tr.inactif td { color: var(--ui-text-3); }
      .role { width: auto; min-width: 170px; padding: var(--ui-s1) var(--ui-s2); font-size: var(--ui-fs-sm); }
      .statut { display: inline-flex; align-items: center; gap: 6px; font-weight: var(--ui-fw-medium); }
      .statut app-icon { color: var(--ui-success); }
      .statut--inactif app-icon { color: var(--ui-text-3); }
      .col-action { width: 1%; white-space: nowrap; text-align: right; }
    `,
  ],
})
export class EquipeComponent implements OnInit {
  private readonly service = inject(CabinetWorkspaceService);
  private readonly tokens = inject(TokenStorageService);

  readonly expert = this.tokens.getRoles().includes('ROLE_EXPERT_COMPTABLE');
  readonly moi = (this.tokens.getEmail() ?? '').toLowerCase();
  readonly roles = ROLES;
  readonly membres = signal<Membre[] | null>(null);
  readonly formulaire = signal(false);
  readonly chargement = signal(false);
  readonly enCours = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly motDePasse = signal<{ nom: string; motDePasse: string } | null>(null);
  readonly lienReinitialisation = signal<{ nom: string; lien: string; expire: string } | null>(null);
  nouveau: { prenom: string; nom: string; email: string; role: RoleMembre } = { prenom: '', nom: '', email: '', role: 'COLLABORATEUR' };

  readonly resume = computed(() => {
    const n = this.membres()?.length ?? 0;
    return `${n} membre${n > 1 ? 's' : ''}`;
  });
  readonly aucunCollaborateur = computed(() => (this.membres() ?? []).filter((m) => m.role === 'COLLABORATEUR').length === 0 && this.membres() !== null);

  ngOnInit(): void {
    this.charger();
  }

  libelleRole(r: RoleMembre): string {
    return ROLES.find((x) => x.code === r)?.libelle ?? r;
  }

  activite(iso: string | null): string {
    return ilYA(iso);
  }

  /** Émet un lien de réinitialisation à usage unique pour un membre ; le lien n'est affiché qu'une fois. */
  reinitialiser(m: Membre): void {
    this.erreur.set(null);
    this.lienReinitialisation.set(null);
    this.service.reinitialiserMotDePasse(m.id).subscribe({
      next: (r) => this.lienReinitialisation.set({ nom: `${m.prenom} ${m.nom}`, lien: `${window.location.origin}${r.chemin}`, expire: new Date(r.expireAt).toLocaleString('fr-FR') }),
      error: (err) => this.erreur.set(err.error?.message ?? 'Le lien de réinitialisation n’a pas pu être émis.'),
    });
  }

  basculerFormulaire(): void {
    this.formulaire.set(!this.formulaire());
    this.erreur.set(null);
  }

  emailInvalide(): boolean {
    return this.nouveau.email.length > 0 && !/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(this.nouveau.email.trim());
  }

  valide(): boolean {
    return !!this.nouveau.prenom.trim() && !!this.nouveau.nom.trim() && !!this.nouveau.email.trim() && !this.emailInvalide();
  }

  ajouter(): void {
    if (!this.valide()) {
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);
    this.service.ajouterMembre({ prenom: this.nouveau.prenom.trim(), nom: this.nouveau.nom.trim(), email: this.nouveau.email.trim(), role: this.nouveau.role }).subscribe({
      next: (r) => {
        this.motDePasse.set(r.motDePasseTemporaire ? { nom: `${r.membre.prenom} ${r.membre.nom}`, motDePasse: r.motDePasseTemporaire } : null);
        this.nouveau = { prenom: '', nom: '', email: '', role: 'COLLABORATEUR' };
        this.formulaire.set(false);
        this.enCours.set(false);
        this.charger();
      },
      error: (err) => {
        this.erreur.set(err.error?.message ?? 'Le membre n’a pas pu être ajouté.');
        this.enCours.set(false);
      },
    });
  }

  changerRole(m: Membre, role: RoleMembre): void {
    if (role === m.role) {
      return;
    }
    this.modifier(m, { role });
  }

  basculerStatut(m: Membre): void {
    this.modifier(m, { statut: m.statut === 'ACTIF' ? 'INACTIF' : 'ACTIF' });
  }

  private modifier(m: Membre, corps: { role?: RoleMembre; statut?: 'ACTIF' | 'INACTIF' }): void {
    this.erreur.set(null);
    this.service.modifierMembre(m.id, corps).subscribe({
      next: () => this.charger(),
      error: (err) => {
        this.erreur.set(err.error?.message ?? 'Modification impossible.');
        this.charger();
      },
    });
  }

  private charger(): void {
    this.chargement.set(true);
    this.service.equipe().subscribe({
      next: (m) => {
        this.membres.set(m);
        this.chargement.set(false);
      },
      error: (err) => {
        this.erreur.set(err.error?.message ?? 'Impossible de charger l’équipe.');
        this.chargement.set(false);
      },
    });
  }
}
