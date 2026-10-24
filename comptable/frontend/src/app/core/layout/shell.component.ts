import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Subscription, catchError, interval, of, startWith, switchMap } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { TokenStorageService } from '../auth/token-storage.service';
import { GuideAssistantComponent } from '../aide/guide-assistant.component';
import { BandeauConnexionComponent } from '../pwa/bandeau-connexion.component';
import { HorsLigneService } from '../pwa/hors-ligne.service';
import { NotificationService } from '../../features/notifications/notification.service';
import { NotificationDto } from '../../features/notifications/models/notification.model';

const INTERVALLE_RAFRAICHISSEMENT_MS = 30_000;

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, BandeauConnexionComponent, GuideAssistantComponent],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent implements OnInit, OnDestroy {
  private readonly authService = inject(AuthService);
  private readonly tokenStorage = inject(TokenStorageService);
  private readonly notificationService = inject(NotificationService);
  private readonly router = inject(Router);
  readonly horsLigne = inject(HorsLigneService);

  private abonnementCompteur?: Subscription;

  readonly email = this.tokenStorage.getEmail();
  readonly estMembreCabinet = this.tokenStorage
    .getRoles()
    .some((role) => role === 'ROLE_EXPERT_COMPTABLE' || role === 'ROLE_COLLABORATEUR');
  readonly compteurNonLues = signal(0);
  readonly notifications = signal<NotificationDto[]>([]);
  readonly dropdownOuvert = signal(false);

  ngOnInit(): void {
    this.abonnementCompteur = interval(INTERVALLE_RAFRAICHISSEMENT_MS)
      .pipe(
        startWith(0),
        // hors ligne ou serveur indisponible : on garde le dernier compteur connu et le sondage continue
        switchMap(() => this.notificationService.compterNonLues().pipe(catchError(() => of({ nonLues: this.compteurNonLues() })))),
      )
      .subscribe((reponse) => this.compteurNonLues.set(reponse.nonLues));
  }

  ngOnDestroy(): void {
    this.abonnementCompteur?.unsubscribe();
  }

  basculerDropdown(): void {
    this.dropdownOuvert.set(!this.dropdownOuvert());
    if (this.dropdownOuvert()) {
      this.notificationService.lister().subscribe((notifications) => this.notifications.set(notifications));
    }
  }

  marquerLue(notification: NotificationDto): void {
    if (notification.statut === 'LUE') {
      return;
    }
    this.notificationService.marquerLue(notification.id).subscribe(() => {
      this.notifications.update((liste) =>
        liste.map((n) => (n.id === notification.id ? { ...n, statut: 'LUE' as const } : n)),
      );
      this.compteurNonLues.update((n) => Math.max(0, n - 1));
    });
  }

  marquerToutesLues(): void {
    this.notificationService.marquerToutesLues().subscribe(() => {
      this.notifications.update((liste) => liste.map((n) => ({ ...n, statut: 'LUE' as const })));
      this.compteurNonLues.set(0);
    });
  }

  seDeconnecter(): void {
    this.authService.logout();
    this.router.navigateByUrl('/login');
  }
}
