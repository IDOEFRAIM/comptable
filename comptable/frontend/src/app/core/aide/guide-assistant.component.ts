import { Component, computed, effect, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map, startWith } from 'rxjs';
import { EntrepriseService } from '../../features/entreprises/entreprise.service';
import { EntrepriseDto } from '../../features/entreprises/models/entreprise.model';
import { TokenStorageService } from '../auth/token-storage.service';
import { rechercher, suggestions } from './guide-recherche';
import { GUIDES_PAGES, GuideEtape, GuidePage } from './guides-pages';
import { GuideTache, PAGES_CLIENT } from './guides-taches';

/**
 * Assistant « Guide » flottant : explique la page courante et répond à « Que voulez-vous faire ? » par une réponse
 * claire (où, comment, ce qu'il ne faut pas confondre) ET un bouton qui emmène directement à la bonne page. Contenu
 * statique (guides-pages.ts, guides-taches.ts) : pas d'IA générative, donc jamais d'indication inexacte.
 *
 * Hors d'un dossier (liste des entreprises, tableau de bord du cabinet…), il faut savoir DANS QUEL dossier emmener
 * l'utilisateur : un seul dossier → choisi d'office ; plusieurs → sélecteur, et le dernier dossier ouvert est
 * mémorisé pour la prochaine fois.
 */
@Component({
  selector: 'app-guide-assistant',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './guide-assistant.component.html',
  styleUrl: './guide-assistant.component.scss',
})
export class GuideAssistantComponent {
  private readonly router = inject(Router);
  private readonly entrepriseService = inject(EntrepriseService);
  private readonly tokens = inject(TokenStorageService);

  readonly ouvert = signal(false);
  readonly question = signal('');

  /** Membre du cabinet : voit tout le menu ; un client ne voit que Documents et Pièces demandées. */
  readonly membreCabinet = this.tokens.getRoles().some((r) => r === 'ROLE_EXPERT_COMPTABLE' || r === 'ROLE_COLLABORATEUR');

  readonly entreprises = signal<EntrepriseDto[]>([]);
  readonly entreprisesChargees = signal(false);
  private readonly dossierMemorise = signal<string | null>(this.lireMemoire());
  /** Étapes dépliées ou repliées par l'utilisateur (clé = id de la tâche). Par défaut : dépliées s'il n'y a qu'UNE réponse, repliées sinon (on voit ainsi toutes les réponses d'un coup d'œil). */
  private readonly etatEtapes = signal<Record<string, boolean>>({});

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => this.router.url),
      startWith(this.router.url),
    ),
    { initialValue: this.router.url },
  );

  /** Dossier dans lequel l'utilisateur se trouve, absent hors d'un dossier. */
  private readonly dossierDeLUrl = computed(() => /\/entreprises\/([^/?#]+)/.exec(this.url())?.[1] ?? null);
  readonly dansUnDossier = computed(() => this.dossierDeLUrl() !== null);

  /** Dossier vers lequel les boutons emmènent : celui de l'URL, sinon le dossier mémorisé (s'il existe encore), sinon l'unique dossier. */
  readonly entrepriseId = computed<string | null>(() => {
    const courant = this.dossierDeLUrl();
    if (courant) {
      return courant;
    }
    const liste = this.entreprises();
    const memorise = this.dossierMemorise();
    if (memorise && liste.some((e) => e.id === memorise)) {
      return memorise;
    }
    return liste.length === 1 ? liste[0].id : null;
  });

  /** Plusieurs dossiers et aucun choisi : le sélecteur est nécessaire pour que les boutons fonctionnent. */
  readonly choixDossierRequis = computed(() => !this.dansUnDossier() && this.entreprisesChargees() && this.entreprises().length > 1);
  readonly aucunDossier = computed(() => !this.dansUnDossier() && this.entreprisesChargees() && this.entreprises().length === 0);
  readonly nomDossier = computed(() => this.entreprises().find((e) => e.id === this.entrepriseId())?.raisonSociale ?? null);

  readonly guideCourant = computed<GuidePage | null>(() => {
    const m = /\/entreprises\/[^/]+\/([^?#]*)/.exec(this.url());
    if (!m) return null;
    const chemin = m[1].replace(/\/$/, '');
    return (
      [...GUIDES_PAGES]
        .sort((a, b) => b.onglet.length - a.onglet.length)
        .find((g) => chemin === g.onglet || chemin.startsWith(g.onglet + '/')) ?? null
    );
  });

  readonly resultats = computed<GuideTache[]>(() => rechercher(this.question(), this.membreCabinet));
  readonly recherche = computed(() => this.question().trim().length >= 3);
  readonly propositions = computed<GuideTache[]>(() => suggestions(this.membreCabinet));

  constructor() {
    // se souvenir du dernier dossier ouvert, pour emmener l'utilisateur au bon endroit même depuis la liste des entreprises
    effect(
      () => {
        const id = this.dossierDeLUrl();
        if (id) {
          this.dossierMemorise.set(id);
          this.ecrireMemoire(id);
        }
      },
      { allowSignalWrites: true },
    );
  }

  /** Une page est-elle atteignable par cet utilisateur ? (un client n'a que Documents et Pièces demandées) */
  pageAccessible(onglet: string): boolean {
    return this.membreCabinet || PAGES_CLIENT.includes(onglet);
  }

  lien(onglet: string): string[] | null {
    const id = this.entrepriseId();
    return id && this.pageAccessible(onglet) ? ['/entreprises', id, ...onglet.split('/')] : null;
  }

  lienEtape(etape: GuideEtape): string[] | null {
    return etape.onglet ? this.lien(etape.onglet) : null;
  }

  estDeplie(tache: GuideTache): boolean {
    return this.etatEtapes()[tache.id] ?? this.resultats().length === 1;
  }

  basculerEtapes(tache: GuideTache): void {
    const etat = !this.estDeplie(tache);
    this.etatEtapes.update((e) => ({ ...e, [tache.id]: etat }));
  }

  basculer(): void {
    this.ouvert.update((v) => !v);
    if (this.ouvert()) {
      this.chargerDossiers();
    }
  }

  fermer(): void {
    this.ouvert.set(false);
  }

  saisir(event: Event): void {
    this.question.set((event.target as HTMLInputElement).value);
    this.etatEtapes.set({});
  }

  utiliser(tache: GuideTache): void {
    this.question.set(tache.question);
    this.etatEtapes.set({});
  }

  effacer(): void {
    this.question.set('');
    this.etatEtapes.set({});
  }

  choisirDossier(event: Event): void {
    const id = (event.target as HTMLSelectElement).value || null;
    this.dossierMemorise.set(id);
    if (id) {
      this.ecrireMemoire(id);
    }
  }

  /** Les dossiers ne sont chargés que si on en a besoin (hors dossier) et une seule fois. */
  private chargerDossiers(): void {
    if (this.dansUnDossier() || this.entreprisesChargees()) {
      return;
    }
    this.entrepriseService.lister().subscribe({
      next: (liste) => {
        this.entreprises.set(liste);
        this.entreprisesChargees.set(true);
      },
      error: () => {
        // hors ligne ou erreur : on n'affiche ni sélecteur ni faux message « aucun dossier », seulement des réponses sans bouton
        this.entreprisesChargees.set(false);
      },
    });
  }

  private cleMemoire(): string {
    return `warimara.guide.dossier.${this.tokens.getEmail() ?? ''}`;
  }

  private lireMemoire(): string | null {
    try {
      return localStorage.getItem(this.cleMemoire());
    } catch {
      return null;
    }
  }

  private ecrireMemoire(id: string): void {
    try {
      localStorage.setItem(this.cleMemoire(), id);
    } catch {
      // stockage indisponible : le dernier dossier ne sera simplement pas mémorisé
    }
  }
}
