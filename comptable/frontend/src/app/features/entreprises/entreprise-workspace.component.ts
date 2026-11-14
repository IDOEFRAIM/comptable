import { DOCUMENT, DatePipe } from '@angular/common';
import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { catchError, filter, map, of, startWith } from 'rxjs';
import { TokenStorageService } from '../../core/auth/token-storage.service';
import { DossierHorsLigneService } from '../../core/pwa/dossier-hors-ligne.service';
import { HorsLigneService } from '../../core/pwa/hors-ligne.service';
import { IconComponent } from '../../shared/ui/icon.component';
import { EntrepriseService } from './entreprise.service';
import { EntrepriseDto } from './models/entreprise.model';
import { disponibiliteHorsLigne, libellePage, menuPour } from './navigation-dossier';

@Component({
  selector: 'app-entreprise-workspace',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, DatePipe, IconComponent],
  templateUrl: './entreprise-workspace.component.html',
  styleUrl: './entreprise-workspace.component.scss',
})
export class EntrepriseWorkspaceComponent implements OnDestroy {
  private readonly tokenStorage = inject(TokenStorageService);
  private readonly document = inject(DOCUMENT);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  readonly horsLigne = inject(HorsLigneService);
  readonly telechargement = inject(DossierHorsLigneService);

  readonly entrepriseId = this.route.snapshot.paramMap.get('entrepriseId') ?? '';

  /** Les dossiers accessibles : nom du dossier ouvert et sélecteur pour passer d'un dossier à l'autre. */
  readonly entreprises = toSignal(
    inject(EntrepriseService).lister().pipe(catchError(() => of([] as EntrepriseDto[]))),
    { initialValue: [] as EntrepriseDto[] },
  );
  readonly entrepriseCourante = computed(() => this.entreprises().find((e) => e.id === this.entrepriseId) ?? null);

  /** Page ouverte dans le dossier (premier segment après l'id), pour le fil d'Ariane. */
  private readonly segmentCourant = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      startWith(null),
      map(() => this.router.url.split('?')[0].split('/')[3] ?? ''),
    ),
    { initialValue: '' },
  );
  readonly pageCourante = computed(() => libellePage(this.segmentCourant()));

  /** Change de dossier en restant sur la même page (Écritures de A → Écritures de B). Le passage par une URL
   * neutre recrée les écrans : sinon Angular réutiliserait ceux du dossier précédent, avec ses données. */
  changerDossier(id: string): void {
    if (!id || id === this.entrepriseId) {
      return;
    }
    const page = this.segmentCourant() || '';
    const cible = ['/entreprises', id, ...(page && libellePage(page) ? [page] : [])];
    this.router.navigateByUrl('/entreprises', { skipLocationChange: true }).then(() => this.router.navigate(cible));
  }
  readonly dateTelechargement = signal<number | null>(this.entrepriseId ? this.telechargement.derniereDate(this.entrepriseId) : null);
  readonly messageTelechargement = signal<string | null>(null);

  /** Garde les pages de lecture de ce dossier sur l'appareil, pour les consulter sans Internet. */
  async telechargerPourHorsLigne(): Promise<void> {
    this.messageTelechargement.set(null);
    const bilan = await this.telechargement.telecharger(this.entrepriseId);
    this.dateTelechargement.set(bilan.reussies > 0 ? bilan.date : this.dateTelechargement());
    this.messageTelechargement.set(
      bilan.reussies === 0
        ? 'Téléchargement impossible : vérifiez votre connexion.'
        : `Dossier téléchargé (${bilan.reussies} page(s) gardées${bilan.echecs > 0 ? `, ${bilan.echecs} indisponible(s)` : ''}). Il reste consultable sans Internet.`,
    );
  }

  /** Le tableau de bord, les tiers, les ecritures, les rapports et la fiscalite sont
   * reserves au cabinet : un CLIENT n'y a pas acces cote API, inutile de lui montrer l'onglet. */
  readonly estMembreCabinet = this.tokenStorage
    .getRoles()
    .some((role) => role === 'ROLE_EXPERT_COMPTABLE' || role === 'ROLE_COLLABORATEUR');

  readonly menu = menuPour(this.estMembreCabinet);
  readonly disponibilite = disponibiliteHorsLigne;

  /** Application déjà installée sur l'ordinateur (fenêtre propre, sans barre d'adresse) : inutile de proposer l'installation. */
  readonly installee = typeof window !== 'undefined' && window.matchMedia?.('(display-mode: standalone)').matches === true;
  readonly guideOuvert = signal(false);

  /** Menu de navigation en panneau coulissant sous 960px (voir styleUrl) - ferme par defaut. */
  readonly menuMobileOuvert = signal(false);
  private positionScrollAvantOuverture = 0;

  basculerMenuMobile(): void {
    this.menuMobileOuvert.update((ouvert) => !ouvert);
    if (this.menuMobileOuvert()) {
      this.verrouillerScrollPage();
    } else {
      this.deverrouillerScrollPage();
    }
  }

  fermerMenuMobile(): void {
    if (!this.menuMobileOuvert()) {
      return;
    }
    this.menuMobileOuvert.set(false);
    this.deverrouillerScrollPage();
  }

  ngOnDestroy(): void {
    this.deverrouillerScrollPage();
  }

  // `overflow: hidden` seul sur <body> ne bloque pas le defilement tactile sur Safari iOS - le
  // panneau reste ouvert mais un geste demarre dessus continue de faire defiler la page en
  // dessous. La technique fiable consiste a figer le corps de la page en position fixed a son
  // decalage de scroll actuel, puis a le relacher et restaurer ce decalage a la fermeture.
  private verrouillerScrollPage(): void {
    this.positionScrollAvantOuverture = window.scrollY;
    const style = this.document.body.style;
    style.position = 'fixed';
    style.top = `-${this.positionScrollAvantOuverture}px`;
    style.left = '0';
    style.right = '0';
    this.document.body.classList.add('menu-mobile-ouvert');
  }

  private deverrouillerScrollPage(): void {
    const style = this.document.body.style;
    style.position = '';
    style.top = '';
    style.left = '';
    style.right = '';
    this.document.body.classList.remove('menu-mobile-ouvert');
    window.scrollTo(0, this.positionScrollAvantOuverture);
  }
}
