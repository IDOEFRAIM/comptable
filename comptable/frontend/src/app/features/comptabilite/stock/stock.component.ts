import { Component, inject, OnInit, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite.service';
import {
  ArticleDto,
  CompteComptableDto,
  LIBELLES_TYPE_MOUVEMENT,
  MouvementStockDto,
  TypeMouvementStock,
} from '../models/comptabilite.model';

@Component({
  selector: 'app-stock',
  standalone: true,
  imports: [ReactiveFormsModule, DecimalPipe],
  templateUrl: './stock.component.html',
  styleUrl: './stock.component.scss',
})
export class StockComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);

  readonly libellesTypeMouvement = LIBELLES_TYPE_MOUVEMENT;

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly comptes = signal<CompteComptableDto[]>([]);
  readonly articles = signal<ArticleDto[]>([]);
  readonly articleSelectionne = signal<ArticleDto | null>(null);
  readonly mouvements = signal<MouvementStockDto[]>([]);
  readonly chargement = signal(false);
  readonly chargementMouvements = signal(false);
  readonly erreur = signal<string | null>(null);
  readonly afficherFormulaireArticle = signal(false);
  readonly envoiEnCours = signal(false);
  readonly mouvementEnCours = signal(false);

  readonly form = this.fb.nonNullable.group({
    codeArticle: ['', Validators.required],
    designation: ['', Validators.required],
    categorie: [''],
    uniteMesure: ['unite', Validators.required],
    prixVenteHt: ['', Validators.required],
    tauxTva: ['18', Validators.required],
    compteStockId: ['', Validators.required],
    compteAchatId: ['', Validators.required],
    compteVenteId: ['', Validators.required],
    compteVariationId: ['', Validators.required],
  });

  readonly formMouvement = this.fb.nonNullable.group({
    type: this.fb.nonNullable.control<TypeMouvementStock>('ENTREE', Validators.required),
    quantite: ['', Validators.required],
    prixUnitaireHt: [''],
    libelle: [''],
  });

  get comptesStock() {
    return this.comptes().filter((c) => c.numero.startsWith('3'));
  }

  get comptesAchat() {
    return this.comptes().filter((c) => c.nature === 'CHARGE');
  }

  get comptesVente() {
    return this.comptes().filter((c) => c.nature === 'PRODUIT');
  }

  get comptesVariation() {
    return this.comptes().filter((c) => c.numero.startsWith('60') || c.numero.startsWith('70'));
  }

  ngOnInit(): void {
    this.comptabiliteService.listerComptes(this.entrepriseId()).subscribe((comptes) => this.comptes.set(comptes));
    this.rafraichirArticles();
  }

  rafraichirArticles(): void {
    this.chargement.set(true);
    this.comptabiliteService
      .listerArticles(this.entrepriseId())
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (articles) => {
          this.articles.set(articles);
          const selectionne = this.articleSelectionne();
          if (selectionne) {
            const misAJour = articles.find((a) => a.id === selectionne.id) ?? null;
            this.articleSelectionne.set(misAJour);
          }
        },
        error: () => this.erreur.set('Impossible de charger le catalogue.'),
      });
  }

  creerArticle(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    const v = this.form.getRawValue();
    this.comptabiliteService
      .creerArticle(this.entrepriseId(), {
        codeArticle: v.codeArticle,
        designation: v.designation,
        categorie: v.categorie || undefined,
        uniteMesure: v.uniteMesure,
        prixVenteHt: Number(v.prixVenteHt),
        tauxTva: Number(v.tauxTva),
        compteStockId: v.compteStockId,
        compteAchatId: v.compteAchatId,
        compteVenteId: v.compteVenteId,
        compteVariationId: v.compteVariationId,
      })
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (article) => {
          this.articles.update((liste) => [...liste, article]);
          this.form.reset({
            codeArticle: '', designation: '', categorie: '', uniteMesure: 'unite',
            prixVenteHt: '', tauxTva: '18', compteStockId: '', compteAchatId: '', compteVenteId: '', compteVariationId: '',
          });
          this.afficherFormulaireArticle.set(false);
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de créer cet article.'),
      });
  }

  selectionnerArticle(article: ArticleDto): void {
    this.articleSelectionne.set(article);
    this.chargerMouvements(article.id);
  }

  private chargerMouvements(articleId: string): void {
    this.chargementMouvements.set(true);
    this.comptabiliteService
      .listerMouvementsStock(this.entrepriseId(), articleId)
      .pipe(finalize(() => this.chargementMouvements.set(false)))
      .subscribe({
        next: (mouvements) => this.mouvements.set(mouvements),
        error: () => this.erreur.set('Impossible de charger les mouvements de cet article.'),
      });
  }

  enregistrerMouvement(): void {
    const article = this.articleSelectionne();
    if (!article || this.formMouvement.invalid) {
      this.formMouvement.markAllAsTouched();
      return;
    }
    const v = this.formMouvement.getRawValue();
    this.mouvementEnCours.set(true);
    this.erreur.set(null);
    this.comptabiliteService
      .enregistrerMouvementStock(this.entrepriseId(), {
        articleId: article.id,
        type: v.type,
        quantite: Number(v.quantite),
        prixUnitaireHt: v.prixUnitaireHt ? Number(v.prixUnitaireHt) : undefined,
        libelle: v.libelle || undefined,
      })
      .pipe(finalize(() => this.mouvementEnCours.set(false)))
      .subscribe({
        next: () => {
          this.formMouvement.reset({ type: 'ENTREE', quantite: '', prixUnitaireHt: '', libelle: '' });
          this.rafraichirArticles();
          this.chargerMouvements(article.id);
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible d\'enregistrer ce mouvement.'),
      });
  }
}
