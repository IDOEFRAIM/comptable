import { Component, ElementRef, HostListener, inject, input, signal } from '@angular/core';
import { AideContextuelleService } from './aide-contextuelle.service';

/**
 * Bouton "?" reutilisable affichant une infobulle d'aide pedagogique au clic (Sprint 8).
 * Usage : <app-aide-contextuelle cle="inbox-validation" /> a cote d'un titre de page ou d'un badge.
 * Au clic plutot qu'au survol : plus fiable sur ecran tactile/tablette, frequent chez les
 * collaborateurs de cabinet en deplacement chez le client.
 */
@Component({
  selector: 'app-aide-contextuelle',
  standalone: true,
  templateUrl: './aide-contextuelle.component.html',
  styleUrl: './aide-contextuelle.component.scss',
})
export class AideContextuelleComponent {
  private readonly aideService = inject(AideContextuelleService);
  private readonly hote = inject(ElementRef<HTMLElement>);

  readonly cle = input.required<string>();
  readonly ouvert = signal(false);

  get contenu() {
    return this.aideService.contenu(this.cle());
  }

  basculer(event: Event): void {
    event.stopPropagation();
    this.ouvert.update((v) => !v);
  }

  fermer(): void {
    this.ouvert.set(false);
  }

  /** Ferme l'infobulle au clic en dehors du composant (bouton ou panneau). */
  @HostListener('document:click', ['$event'])
  surClicDocument(event: Event): void {
    if (this.ouvert() && !this.hote.nativeElement.contains(event.target as Node)) {
      this.ouvert.set(false);
    }
  }

  @HostListener('document:keydown.escape')
  surEchap(): void {
    this.ouvert.set(false);
  }
}
