import { Component, inject, OnInit, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite.service';
import { EcritureComptableDto } from '../models/comptabilite.model';

@Component({
  selector: 'app-ecriture-list',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './ecriture-list.component.html',
  styleUrl: './ecriture-list.component.scss',
})
export class EcritureListComponent implements OnInit {
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly ecritures = signal<EcritureComptableDto[]>([]);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly ecritureOuverte = signal<string | null>(null);
  readonly actionEnCours = signal<string | null>(null);

  ngOnInit(): void {
    this.rafraichir();
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.comptabiliteService
      .listerEcritures(this.entrepriseId())
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (ecritures) => this.ecritures.set(ecritures),
        error: () => this.erreur.set('Impossible de charger les écritures.'),
      });
  }

  basculer(ecriture: EcritureComptableDto): void {
    this.ecritureOuverte.set(this.ecritureOuverte() === ecriture.id ? null : ecriture.id);
  }

  valider(ecriture: EcritureComptableDto): void {
    this.actionEnCours.set(ecriture.id);
    this.erreur.set(null);
    this.comptabiliteService
      .validerEcriture(this.entrepriseId(), ecriture.id)
      .pipe(finalize(() => this.actionEnCours.set(null)))
      .subscribe({
        next: (validee) => {
          this.ecritures.update((liste) => liste.map((e) => (e.id === validee.id ? validee : e)));
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Validation impossible.'),
      });
  }

  supprimer(ecriture: EcritureComptableDto): void {
    if (!confirm(`Supprimer le brouillon ${ecriture.numeroPiece} ? Cette action est irréversible.`)) {
      return;
    }
    this.actionEnCours.set(ecriture.id);
    this.erreur.set(null);
    this.comptabiliteService
      .supprimerEcriture(this.entrepriseId(), ecriture.id)
      .pipe(finalize(() => this.actionEnCours.set(null)))
      .subscribe({
        next: () => this.ecritures.update((liste) => liste.filter((e) => e.id !== ecriture.id)),
        error: (err) => this.erreur.set(err.error?.message ?? 'Suppression impossible.'),
      });
  }
}
