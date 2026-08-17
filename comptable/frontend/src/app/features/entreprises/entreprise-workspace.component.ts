import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TokenStorageService } from '../../core/auth/token-storage.service';

@Component({
  selector: 'app-entreprise-workspace',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './entreprise-workspace.component.html',
  styleUrl: './entreprise-workspace.component.scss',
})
export class EntrepriseWorkspaceComponent {
  private readonly tokenStorage = inject(TokenStorageService);

  /** Le tableau de bord, les tiers, les ecritures, les rapports et la fiscalite sont
   * reserves au cabinet : un CLIENT n'y a pas acces cote API, inutile de lui montrer l'onglet. */
  readonly estMembreCabinet = this.tokenStorage
    .getRoles()
    .some((role) => role === 'ROLE_EXPERT_COMPTABLE' || role === 'ROLE_COLLABORATEUR');
}
