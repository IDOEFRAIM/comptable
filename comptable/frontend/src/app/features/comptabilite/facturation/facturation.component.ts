import { Component, inject, OnInit, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite.service';
import {
  ArticleDto,
  CompteComptableDto,
  CreerFactureAvoirRequest,
  FactureAvoirDto,
  FactureDto,
  LIBELLES_STATUT_FACTURE,
  LIBELLES_STATUT_FACTURE_AVOIR,
  TiersDto,
  TypeFacture,
} from '../models/comptabilite.model';

function aujourdhui(): string {
  return new Date().toISOString().slice(0, 10);
}

@Component({
  selector: 'app-facturation',
  standalone: true,
  imports: [ReactiveFormsModule, DecimalPipe],
  templateUrl: './facturation.component.html',
  styleUrl: './facturation.component.scss',
})
export class FacturationComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), {
    initialValue: '',
  });

  readonly libellesStatut = LIBELLES_STATUT_FACTURE;
  readonly libellesStatutAvoir = LIBELLES_STATUT_FACTURE_AVOIR;
  readonly type = signal<TypeFacture>('DEVIS');
  readonly afficherAvoirs = signal(false);
  readonly documents = signal<FactureDto[]>([]);
  readonly documentOuvert = signal<string | null>(null);
  readonly clients = signal<TiersDto[]>([]);
  readonly articles = signal<ArticleDto[]>([]);
  readonly comptesProduits = signal<CompteComptableDto[]>([]);
  readonly compteProduitChoisi = signal<Record<string, string>>({});
  readonly chargement = signal(false);
  readonly envoiEnCours = signal(false);
  readonly actionEnCours = signal<string | null>(null);
  readonly erreur = signal<string | null>(null);
  readonly afficherFormulaire = signal(false);

  readonly avoirs = signal<FactureAvoirDto[]>([]);
  readonly avoirOuvert = signal<string | null>(null);
  readonly chargementAvoirs = signal(false);
  readonly actionAvoirEnCours = signal<string | null>(null);
  readonly erreurAvoir = signal<string | null>(null);
  readonly succesAvoir = signal<string | null>(null);
  readonly avoirEnCoursPourFacture = signal<string | null>(null);
  readonly envoiAvoirEnCours = signal(false);

  readonly form = this.fb.nonNullable.group({
    tiersId: ['', Validators.required],
    dateEmission: [aujourdhui(), Validators.required],
    dateEcheance: [''],
    objet: [''],
    lignes: this.fb.array([this.creerLigne(), this.creerLigne()]),
  });

  readonly formAvoir = this.fb.nonNullable.group({
    motif: [''],
    retourStock: [false],
    total: [true],
    lignes: this.fb.array<ReturnType<FacturationComponent['creerLigneAvoir']>>([]),
  });

  get lignes(): FormArray {
    return this.form.controls.lignes;
  }

  get lignesAvoir(): FormArray {
    return this.formAvoir.controls.lignes;
  }

  ngOnInit(): void {
    this.comptabiliteService.listerTiers(this.entrepriseId(), 'CLIENT').subscribe((clients) => this.clients.set(clients));
    this.comptabiliteService.listerArticles(this.entrepriseId()).subscribe((articles) => this.articles.set(articles));
    this.comptabiliteService.listerComptes(this.entrepriseId()).subscribe((comptes) => {
      this.comptesProduits.set(comptes.filter((c) => c.numero.startsWith('7')));
    });
    this.rafraichir();
  }

  private creerLigne() {
    return this.fb.nonNullable.group({
      designation: ['', Validators.required],
      quantite: [1, [Validators.required, Validators.min(0.01)]],
      prixUnitaire: [0, [Validators.required, Validators.min(0)]],
      tauxTva: [18, [Validators.required, Validators.min(0)]],
      articleId: [''],
    });
  }

  private creerLigneAvoir(ligneFactureId: string, designation: string, quantiteMax: number) {
    return this.fb.nonNullable.group({
      ligneFactureId,
      designation,
      quantiteMax,
      selectionnee: false,
      quantite: [quantiteMax, [Validators.min(0.01), Validators.max(quantiteMax)]],
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

  changerType(type: TypeFacture): void {
    this.afficherAvoirs.set(false);
    this.type.set(type);
    this.documentOuvert.set(null);
    this.rafraichir();
  }

  afficherOngletAvoirs(): void {
    this.afficherAvoirs.set(true);
    this.documentOuvert.set(null);
    this.rafraichirAvoirs();
  }

  rafraichir(): void {
    this.chargement.set(true);
    this.comptabiliteService
      .listerFactures(this.entrepriseId(), this.type())
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({
        next: (docs) => this.documents.set(docs),
        error: () => this.erreur.set('Impossible de charger la liste.'),
      });
  }

  rafraichirAvoirs(): void {
    this.chargementAvoirs.set(true);
    this.comptabiliteService
      .listerAvoirs(this.entrepriseId())
      .pipe(finalize(() => this.chargementAvoirs.set(false)))
      .subscribe({
        next: (avoirs) => this.avoirs.set(avoirs),
        error: () => this.erreurAvoir.set('Impossible de charger les avoirs.'),
      });
  }

  basculer(doc: FactureDto): void {
    this.documentOuvert.set(this.documentOuvert() === doc.id ? null : doc.id);
    this.avoirEnCoursPourFacture.set(null);
  }

  basculerAvoir(avoir: FactureAvoirDto): void {
    this.avoirOuvert.set(this.avoirOuvert() === avoir.id ? null : avoir.id);
  }

  totalLigneHt(index: number): number {
    const l = this.lignes.at(index).getRawValue();
    return Number(l.quantite) * Number(l.prixUnitaire);
  }

  totalGeneralHt(): number {
    return this.lignes.controls.reduce((total, _, i) => total + this.totalLigneHt(i), 0);
  }

  creer(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.envoiEnCours.set(true);
    this.erreur.set(null);
    const valeurs = this.form.getRawValue();
    this.comptabiliteService
      .creerFacture(this.entrepriseId(), this.type(), {
        tiersId: valeurs.tiersId,
        dateEmission: valeurs.dateEmission,
        dateEcheance: valeurs.dateEcheance || undefined,
        objet: valeurs.objet || undefined,
        lignes: valeurs.lignes.map((l) => ({
          designation: l.designation,
          quantite: Number(l.quantite),
          prixUnitaire: Number(l.prixUnitaire),
          tauxTva: Number(l.tauxTva),
          articleId: l.articleId || undefined,
        })),
      })
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (doc) => {
          this.documents.update((liste) => [doc, ...liste]);
          this.form.reset({ tiersId: '', dateEmission: aujourdhui(), dateEcheance: '', objet: '' });
          while (this.lignes.length > 2) {
            this.lignes.removeAt(0);
          }
          this.afficherFormulaire.set(false);
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de créer ce document.'),
      });
  }

  envoyer(doc: FactureDto): void {
    this.executer(doc.id, this.comptabiliteService.envoyerFacture(this.entrepriseId(), doc.id));
  }

  accepterDevis(doc: FactureDto): void {
    this.executer(doc.id, this.comptabiliteService.accepterDevis(this.entrepriseId(), doc.id));
  }

  refuserDevis(doc: FactureDto): void {
    this.executer(doc.id, this.comptabiliteService.refuserDevis(this.entrepriseId(), doc.id));
  }

  convertir(doc: FactureDto): void {
    this.actionEnCours.set(doc.id);
    this.erreur.set(null);
    this.comptabiliteService
      .convertirEnFacture(this.entrepriseId(), doc.id)
      .pipe(finalize(() => this.actionEnCours.set(null)))
      .subscribe({
        next: () => this.rafraichir(),
        error: (err) => this.erreur.set(err.error?.message ?? 'Conversion impossible.'),
      });
  }

  choisirCompteProduit(docId: string, compteId: string): void {
    this.compteProduitChoisi.update((m) => ({ ...m, [docId]: compteId }));
  }

  validerFacture(doc: FactureDto): void {
    const compteProduitId = this.compteProduitChoisi()[doc.id];
    if (!compteProduitId) {
      this.erreur.set('Choisissez un compte de produit avant de valider.');
      return;
    }
    this.executer(doc.id, this.comptabiliteService.validerFacture(this.entrepriseId(), doc.id, compteProduitId));
  }

  annuler(doc: FactureDto): void {
    if (!confirm(`Annuler la facture ${doc.numero} ? Les articles de stock vendus seront réintégrés.`)) {
      return;
    }
    this.executer(doc.id, this.comptabiliteService.annulerFacture(this.entrepriseId(), doc.id));
  }

  ouvrirFormulaireAvoir(doc: FactureDto): void {
    this.avoirEnCoursPourFacture.set(doc.id);
    this.erreurAvoir.set(null);
    this.succesAvoir.set(null);
    const lignesArray = this.fb.array(doc.lignes.map((l) => this.creerLigneAvoir(l.id, l.designation, l.quantite)));
    this.formAvoir.setControl('lignes', lignesArray);
    this.formAvoir.patchValue({ motif: '', retourStock: false, total: true });
  }

  fermerFormulaireAvoir(): void {
    this.avoirEnCoursPourFacture.set(null);
  }

  creerAvoir(doc: FactureDto): void {
    const valeurs = this.formAvoir.getRawValue();
    const request: CreerFactureAvoirRequest = {
      motif: valeurs.motif || undefined,
      retourStock: valeurs.retourStock,
      total: valeurs.total,
    };
    if (!valeurs.total) {
      const lignesSelectionnees = valeurs.lignes.filter((l) => l.selectionnee);
      if (lignesSelectionnees.length === 0) {
        this.erreurAvoir.set('Sélectionnez au moins une ligne pour un avoir partiel.');
        return;
      }
      request.lignes = lignesSelectionnees.map((l) => ({ ligneFactureId: l.ligneFactureId, quantite: Number(l.quantite) }));
    }
    this.envoiAvoirEnCours.set(true);
    this.erreurAvoir.set(null);
    this.comptabiliteService
      .creerAvoir(this.entrepriseId(), doc.id, request)
      .pipe(finalize(() => this.envoiAvoirEnCours.set(false)))
      .subscribe({
        next: () => {
          this.avoirEnCoursPourFacture.set(null);
          this.succesAvoir.set("Avoir créé en brouillon. Validez-le depuis l'onglet Avoirs pour générer l'écriture comptable.");
        },
        error: (err) => this.erreurAvoir.set(err.error?.message ?? 'Impossible de créer cet avoir.'),
      });
  }

  validerAvoirAction(avoir: FactureAvoirDto): void {
    this.actionAvoirEnCours.set(avoir.id);
    this.erreurAvoir.set(null);
    this.succesAvoir.set(null);
    this.comptabiliteService
      .validerAvoir(this.entrepriseId(), avoir.id)
      .pipe(finalize(() => this.actionAvoirEnCours.set(null)))
      .subscribe({
        next: () => {
          this.succesAvoir.set(`Avoir ${avoir.numero} validé : écriture d'extourne comptabilisée.`);
          this.rafraichirAvoirs();
        },
        error: (err) => this.erreurAvoir.set(err.error?.message ?? 'Impossible de valider cet avoir.'),
      });
  }

  telechargerPdf(avoir: FactureAvoirDto): void {
    this.comptabiliteService.telechargerAvoirPdf(this.entrepriseId(), avoir.id).subscribe((blob) => {
      const url = URL.createObjectURL(blob);
      const lien = document.createElement('a');
      lien.href = url;
      lien.download = `${avoir.numero}.pdf`;
      lien.click();
      URL.revokeObjectURL(url);
    });
  }

  private executer(docId: string, observable: ReturnType<ComptabiliteService['envoyerFacture']>): void {
    this.actionEnCours.set(docId);
    this.erreur.set(null);
    observable.pipe(finalize(() => this.actionEnCours.set(null))).subscribe({
      next: () => this.rafraichir(),
      error: (err) => this.erreur.set(err.error?.message ?? 'Action impossible.'),
    });
  }
}
