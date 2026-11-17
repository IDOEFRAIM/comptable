import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { AdminService, EtatService, SantePlateforme } from './admin.service';
import { STYLES_ADMIN } from './admin-styles';

const LIBELLE_ETAT: Record<EtatService['etat'], string> = { OK: 'En service', DEGRADE: 'Dégradé', HORS_SERVICE: 'Hors service' };
const LIBELLE_SAUVEGARDE: Record<string, string> = { OK: 'Récente', ANCIENNE: 'Trop ancienne', ABSENTE: 'Introuvable', NON_SURVEILLEE: 'Non surveillée' };

/** État de la plateforme : serveur, base, microservice IA, sauvegarde, ressources. Chaque mesure est faite à l'ouverture de la page (bouton Actualiser) ; rien n'est affiché « OK » sans avoir été vérifié. */
@Component({
  selector: 'app-admin-sante',
  standalone: true,
  imports: [DatePipe, DecimalPipe],
  template: `
    <div class="page">
      <header>
        <h1>Santé de la plateforme</h1>
        <p class="intro">Mesures prises à l’instant. Les saisies hors ligne en attente vivent sur les appareils des comptables : le serveur ne peut pas les voir avant leur envoi.</p>
      </header>

      <div class="actions">
        <button type="button" class="principal" (click)="charger()" [disabled]="chargement()" data-action="actualiser">{{ chargement() ? 'Mesure…' : 'Actualiser' }}</button>
        @if (sante(); as s) {
          <span class="secondaire">Mesuré le {{ s.mesureLe | date: 'dd/MM/yyyy HH:mm:ss' }}</span>
        }
      </div>

      @if (erreur()) {
        <p class="erreur" role="alert">{{ erreur() }}</p>
      }

      @if (sante(); as s) {
        <div class="grille" data-zone="services">
          @for (v of s.services; track v.nom) {
            <div class="carte" [class.alerte]="v.etat !== 'OK'" [attr.data-etat]="v.etat">
              <span class="secondaire">{{ v.nom }}</span>
              <strong>{{ libelle(v) }}</strong>
              <span class="secondaire">{{ v.detail }}@if (v.latenceMs !== null) { ({{ v.latenceMs }} ms)}</span>
            </div>
          }
          <div class="carte" [class.alerte]="s.sauvegarde.etat !== 'OK'" [attr.data-etat]="s.sauvegarde.etat" data-zone="sauvegarde">
            <span class="secondaire">Sauvegarde de la base</span>
            <strong>{{ libelleSauvegarde(s.sauvegarde.etat) }}</strong>
            <span class="secondaire">
              {{ s.sauvegarde.detail }}
              @if (s.sauvegarde.derniere) { Dernière : {{ s.sauvegarde.derniere | date: 'dd/MM/yyyy HH:mm' }}. }
            </span>
          </div>
        </div>

        <h2>Ressources du serveur</h2>
        <div class="grille" data-zone="ressources">
          <div class="carte"><span class="secondaire">Mémoire de l’application</span><strong>{{ s.memoireUtiliseeMo | number }} / {{ s.memoireMaxMo | number }} Mo</strong></div>
          <div class="carte" [class.alerte]="disqueCritique(s)"><span class="secondaire">Disque libre</span><strong>{{ s.disqueLibreMo | number }} / {{ s.disqueTotalMo | number }} Mo</strong></div>
          <div class="carte"><span class="secondaire">Version de la base</span><strong>V{{ s.versionBase }}</strong></div>
          <div class="carte"><span class="secondaire">Serveur démarré depuis</span><strong>{{ duree(s.demarreDepuisMinutes) }}</strong></div>
        </div>
      }
    </div>
  `,
  styles: [
    STYLES_ADMIN,
    `
      h2 { margin: 0.5rem 0 0; font-size: 1.05rem; }
      .grille { display: grid; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); gap: 0.75rem; }
      .carte { background: #fff; border: 1px solid var(--border-subtle, #eaecf0); border-radius: 10px; padding: 0.8rem 1rem; display: flex; flex-direction: column; gap: 0.25rem; }
      .carte strong { font-size: 1.15rem; }
      .carte.alerte { border-color: #f2b705; background: #fffaeb; }
      .carte[data-etat='HORS_SERVICE'] { border-color: #b42318; background: #fef3f2; }
    `,
  ],
})
export class AdminSanteComponent implements OnInit {
  private readonly admin = inject(AdminService);

  readonly sante = signal<SantePlateforme | null>(null);
  readonly erreur = signal<string | null>(null);
  readonly chargement = signal(false);

  ngOnInit(): void {
    this.charger();
  }

  charger(): void {
    this.chargement.set(true);
    this.erreur.set(null);
    this.admin.sante().subscribe({
      next: (s) => {
        this.sante.set(s);
        this.chargement.set(false);
      },
      error: (e) => {
        this.chargement.set(false);
        this.erreur.set(e.error?.message ?? 'Impossible de mesurer la santé de la plateforme.');
      },
    });
  }

  libelle(v: EtatService): string {
    return LIBELLE_ETAT[v.etat];
  }

  libelleSauvegarde(etat: string): string {
    return LIBELLE_SAUVEGARDE[etat] ?? etat;
  }

  disqueCritique(s: SantePlateforme): boolean {
    return s.disqueTotalMo > 0 && s.disqueLibreMo / s.disqueTotalMo < 0.15;
  }

  duree(minutes: number): string {
    if (minutes < 60) {
      return `${minutes} min`;
    }
    if (minutes < 2880) {
      return `${Math.floor(minutes / 60)} h`;
    }
    return `${Math.floor(minutes / 1440)} jours`;
  }
}
