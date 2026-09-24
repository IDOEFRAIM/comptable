import { DOCUMENT } from '@angular/common';
import { Component, OnDestroy, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TokenStorageService } from '../../core/auth/token-storage.service';

@Component({
  selector: 'app-entreprise-workspace',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './entreprise-workspace.component.html',
  styleUrl: './entreprise-workspace.component.scss',
})
export class EntrepriseWorkspaceComponent implements OnDestroy {
  private readonly tokenStorage = inject(TokenStorageService);
  private readonly document = inject(DOCUMENT);

  /** Le tableau de bord, les tiers, les ecritures, les rapports et la fiscalite sont
   * reserves au cabinet : un CLIENT n'y a pas acces cote API, inutile de lui montrer l'onglet. */
  readonly estMembreCabinet = this.tokenStorage
    .getRoles()
    .some((role) => role === 'ROLE_EXPERT_COMPTABLE' || role === 'ROLE_COLLABORATEUR');

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
