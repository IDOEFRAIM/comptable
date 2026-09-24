import { Component, inject, OnInit, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite.service';
import {
  ArticleDto,
  CommandeAchatDto,
  FactureFournisseurDto,
  LIBELLES_STATUT_COMMANDE_ACHAT,
  TiersDto,
} from '../models/comptabilite.model';

function aujourdhui(): string {
  return new Date().toISOString().slice(0, 10);
}

@Component({
  selector: 'app-achats',
  standalone: true,
  imports: [ReactiveFormsModule, DecimalPipe],
  templateUrl: './achats.component.html',
  styleUrl: './achats.component.scss',
})
export class AchatsComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);

  readonly libellesStatut = LIBELLES_STATUT_COMMANDE_ACHAT;

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly fournisseurs = signal<TiersDto[]>([]);
  readonly articles = signal<ArticleDto[]>([]);
  readonly commandes = signal<CommandeAchatDto[]>([]);
  readonly commandeOuverte = signal<string | null>(null);
  readonly chargement = signal(false);
  readonly envoiEnCours = signal(false);
  readonly actionEnCours = signal<string | null>(null);
  readonly erreur = signal<string | null>(null);
  readonly succes = signal<string | null>(null);
  readonly afficherFormulaire = signal(false);

  /** Facture generee pour une commande, gardee en memoire pour cette session afin d'afficher les
   * comptes reels et le bouton "Avoir fournisseur" juste apres generation - pas de nouvel ecran de
   * detail facture-fournisseur dans cette mission (voir DOCUMENTATION.md). */
  readonly dernieresFactures = signal<Record<string, FactureFournisseurDto>>({});
  readonly avoirsGeneres = signal<Record<string, boolean>>({});

  readonly form = this.fb.nonNullable.group({
    fournisseurId: ['', Validators.required],
    dateCommande: [aujourdhui(), Validators.required],
    lignes: this.fb.array([this.creerLigne()]),
  });

  get lignes(): FormArray {
    return this.form.controls.lignes;
  }

  ngOnInit(): void {
    this.comptabiliteService.listerTiers(this.entrepriseId(), 'FOURNISSEUR').subscribe((f) => this.fournisseurs.set(f));
    this.comptabiliteService.listerArticles(this.entrepriseId()).subscribe((a) => this.articles.set(a));
    this.rafraichir();
  }

  private creerLigne() {
    return this.fb.nonNullable.group({
      articleId: ['', Validators.required],
      quantite: [1, [Validators.required, Validators.min(0.001)]],
      prixUnitaireHt: [0, [Validators.required, Validators.min(0)]],
    });
  }

  ajouterLigne(): void {
    this.lignes.push(this.creerLigne());
  }

  supprimerLigne(index: number): void {
    if (this.lignes.length <= 1) {
      return;
    }
    this.lignes.removeAt(index);
  }

  totalLigneHt(index: number): number {
    const l = this.lignes.at(index).getRawValue();
    return Number(l.quantite) * Number(l.prixUnitaireHt);
  }

  totalGeneralHt(): number {
    return this.lignes.controls.reduce((total, _, i) => total + this.totalLigneHt(i), 0);
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.comptabiliteService
      .listerCommandesAchat(this.entrepriseId())
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (commandes) => this.commandes.set(commandes),
        error: () => this.erreur.set('Impossible de charger les commandes.'),
      });
  }

  basculer(commande: CommandeAchatDto): void {
    this.commandeOuverte.set(this.commandeOuverte() === commande.id ? null : commande.id);
  }

  creer(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    this.succes.set(null);
    const valeurs = this.form.getRawValue();
    this.comptabiliteService
      .creerCommandeAchat(this.entrepriseId(), {
        fournisseurId: valeurs.fournisseurId,
        dateCommande: valeurs.dateCommande,
        lignes: valeurs.lignes.map((l) => ({
          articleId: l.articleId,
          quantite: Number(l.quantite),
          prixUnitaireHt: Number(l.prixUnitaireHt),
        })),
      })
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (commande) => {
          this.commandes.update((liste) => [commande, ...liste]);
          this.form.reset({ fournisseurId: '', dateCommande: aujourdhui() });
          while (this.lignes.length > 1) {
            this.lignes.removeAt(0);
          }
          this.afficherFormulaire.set(false);
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de créer ce bon de commande.'),
      });
  }

  validerReception(commande: CommandeAchatDto): void {
    this.actionEnCours.set(commande.id);
    this.erreur.set(null);
    this.succes.set(null);
    this.comptabiliteService
      .validerReceptionCommande(this.entrepriseId(), commande.id)
      .pipe(finalize(() => this.actionEnCours.set(null)))
      .subscribe({
        next: () => {
          this.succes.set(
            `Réception de ${commande.numero} validée : stock mis à jour et écritures comptables générées.`,
          );
          this.rafraichir();
          this.comptabiliteService.listerArticles(this.entrepriseId()).subscribe((a) => this.articles.set(a));
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de valider la réception.'),
      });
  }

  genererFactureFournisseur(commande: CommandeAchatDto): void {
    this.actionEnCours.set(commande.id);
    this.erreur.set(null);
    this.succes.set(null);
    this.comptabiliteService
      .genererFactureFournisseur(this.entrepriseId(), commande.id)
      .pipe(finalize(() => this.actionEnCours.set(null)))
      .subscribe({
        next: (facture) => {
          this.succes.set(`Facture fournisseur ${facture.numero} générée (${facture.montantTtc} FCFA TTC).`);
          this.dernieresFactures.update((m) => ({ ...m, [commande.id]: facture }));
          this.rafraichir();
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de générer la facture fournisseur.'),
      });
  }

  creerAvoirFournisseur(commande: CommandeAchatDto): void {
    const facture = this.dernieresFactures()[commande.id];
    if (!facture) {
      return;
    }
    this.actionEnCours.set(commande.id);
    this.erreur.set(null);
    this.succes.set(null);
    this.comptabiliteService
      .creerAvoirFournisseur(this.entrepriseId(), facture.id, { retourStock: true })
      .pipe(finalize(() => this.actionEnCours.set(null)))
      .subscribe({
        next: (avoir) => {
          this.succes.set(`Avoir fournisseur ${avoir.numero} généré (${avoir.montantTtc} FCFA TTC) : stock réintégré.`);
          this.avoirsGeneres.update((m) => ({ ...m, [commande.id]: true }));
          this.rafraichir();
          this.comptabiliteService.listerArticles(this.entrepriseId()).subscribe((a) => this.articles.set(a));
        },
        error: (err) => this.erreur.set(err.error?.message ?? "Impossible de générer l'avoir fournisseur."),
      });
  }
}
