import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite.service';
import { TiersDto, TypeTiers } from '../models/comptabilite.model';

@Component({
  selector: 'app-tiers-list',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './tiers-list.component.html',
  styleUrl: './tiers-list.component.scss',
})
export class TiersListComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly tiers = signal<TiersDto[]>([]);
  readonly chargement = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly afficherFormulaire = signal(false);
  readonly envoiEnCours = signal(false);

  readonly form = this.fb.nonNullable.group({
    type: this.fb.nonNullable.control<TypeTiers>('FOURNISSEUR', Validators.required),
    nom: ['', Validators.required],
    numeroContribuable: [''],
  });

  ngOnInit(): void {
    this.rafraichir();
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.comptabiliteService
      .listerTiers(this.entrepriseId())
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (tiers) => this.tiers.set(tiers),
        error: () => this.erreur.set('Impossible de charger les tiers.'),
      });
  }

  creer(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.envoiEnCours.set(true);
    const { type, nom, numeroContribuable } = this.form.getRawValue();
    this.comptabiliteService
      .creerTiers(this.entrepriseId(), { type, nom, numeroContribuable: numeroContribuable || undefined })
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (tiers) => {
          this.tiers.update((liste) => [...liste, tiers]);
          this.form.reset({ type: 'FOURNISSEUR', nom: '', numeroContribuable: '' });
          this.afficherFormulaire.set(false);
        },
        error: () => this.erreur.set('Impossible de créer ce tiers.'),
      });
  }
}
