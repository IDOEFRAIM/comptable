import { Component, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map, startWith } from 'rxjs';
import { GUIDES_PAGES, GuidePage } from './guides-pages';

const sansAccents = (s: string): string =>
  s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

/**
 * Assistant « Guide » flottant : explique la page courante et oriente vers la bonne page
 * selon ce que l'utilisateur veut faire. Contenu statique (voir guides-pages.ts) : pas d'IA
 * generative, donc jamais d'indication inexacte.
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

  readonly ouvert = signal(false);
  readonly question = signal('');

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => this.router.url),
      startWith(this.router.url),
    ),
    { initialValue: this.router.url },
  );

  /** Identifiant du dossier courant, absent hors d'un dossier (ex. liste des cabinets). */
  readonly entrepriseId = computed(() => /\/entreprises\/([^/?#]+)/.exec(this.url())?.[1] ?? null);

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

  readonly resultats = computed<GuidePage[]>(() => {
    const mots = sansAccents(this.question()).split(/\s+/).filter((m) => m.length >= 3);
    if (mots.length === 0) return [];
    return GUIDES_PAGES.map((g) => {
      const base = sansAccents(g.titre + ' ' + g.motsCles.join(' '));
      return { g, score: mots.filter((m) => base.includes(m)).length };
    })
      .filter((r) => r.score > 0)
      .sort((a, b) => b.score - a.score)
      .slice(0, 4)
      .map((r) => r.g);
  });

  lien(onglet: string): string[] | null {
    const id = this.entrepriseId();
    return id ? ['/entreprises', id, ...onglet.split('/')] : null;
  }

  basculer(): void {
    this.ouvert.update((v) => !v);
  }

  saisir(event: Event): void {
    this.question.set((event.target as HTMLInputElement).value);
  }
}
