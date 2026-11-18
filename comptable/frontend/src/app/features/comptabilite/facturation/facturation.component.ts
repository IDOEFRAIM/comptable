import { Component, effect, inject, OnInit, signal } from '@angular/core';
import { BrouillonLocalService } from '../../../core/forms/brouillon-local.service';
import { FileAttenteService } from '../../../core/pwa/file-attente.service';
import { HorsLigneService } from '../../../core/pwa/hors-ligne.service';
import { DatePipe, DecimalPipe } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
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
  imports: [ReactiveFormsModule, DecimalPipe, DatePipe, RouterLink],
  templateUrl: './facturation.component.html',
  styleUrl: './facturation.component.scss',
})
export class FacturationComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly comptabiliteService = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);
  readonly horsLigne = inject(HorsLigneService);
  private readonly fileAttente = inject(FileAttenteService);
  private readonly brouillon = inject(BrouillonLocalService);

  /** Moment du brouillon retrouvé à l'ouverture du formulaire ; null si la saisie est neuve. */
  readonly brouillonRestaure = signal<number | null>(null);
  private restauration = false;

  constructor() {
    // la saisie en cours est gardée sur l'appareil à chaque modification : une coupure ne la fait plus perdre
    this.form.valueChanges.subscribe(() => this.memoriser());
    // après l'envoi des documents créés hors ligne, la liste affiche les vrais documents, avec leur numéro officiel
    effect(() => {
      if (this.fileAttente.synchroniseeLe() > 0 && this.entrepriseId()) {
        this.rafraichir();
        this.comptabiliteService.listerTiers(this.entrepriseId(), 'CLIENT').subscribe((clients) => this.clients.set(clients));
      }
    }, { allowSignalWrites: true }); // rafraichir() met à jour des signaux (indicateur de chargement)
  }

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
  /** Ce qu'il manque à la fiche de l'entreprise (IFU, RCCM, adresse) pour que ses documents soient présentables ; vide = complète. */
  readonly ficheManquants = signal<string[]>([]);

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
    this.comptabiliteService.ficheEntreprise(this.entrepriseId()).subscribe({ next: (f) => this.ficheManquants.set(f.manquants ?? []), error: () => undefined });
    this.rafraichir();
    this.restaurer();
  }

  private formulaire(): string {
    return `facture-${this.type()}`;
  }

  private estVide(): boolean {
    const v = this.form.getRawValue();
    return !v.tiersId && !v.objet && !v.dateEcheance && v.lignes.every((l) => !l.designation && !Number(l.prixUnitaire));
  }

  private memoriser(): void {
    if (this.restauration || !this.entrepriseId()) {
      return;
    }
    if (this.estVide()) {
      this.brouillon.effacer(this.formulaire(), this.entrepriseId());
      return;
    }
    this.brouillon.sauver(this.formulaire(), this.entrepriseId(), this.form.getRawValue());
  }

  /** Remet dans le formulaire la saisie laissée en cours (coupure, fermeture accidentelle) pour ce type de document. */
  private restaurer(): void {
    const retrouve = this.brouillon.lire<ReturnType<typeof this.form.getRawValue>>(this.formulaire(), this.entrepriseId());
    if (!retrouve || !retrouve.valeurs.lignes?.length) {
      this.brouillonRestaure.set(null);
      return;
    }
    this.restauration = true;
    try {
      while (this.lignes.length < retrouve.valeurs.lignes.length) {
        this.lignes.push(this.creerLigne());
      }
      while (this.lignes.length > retrouve.valeurs.lignes.length) {
        this.lignes.removeAt(this.lignes.length - 1);
      }
      this.form.setValue(retrouve.valeurs);
    } finally {
      this.restauration = false;
    }
    this.brouillonRestaure.set(retrouve.le);
    this.afficherFormulaire.set(true);
  }

  /** Vide le formulaire sans toucher au brouillon enregistré (changement de devis à facture). */
  private recommencerSansEffacer(): void {
    this.restauration = true;
    this.form.reset({ tiersId: '', dateEmission: aujourdhui(), dateEcheance: '', objet: '' });
    while (this.lignes.length > 2) {
      this.lignes.removeAt(0);
    }
    while (this.lignes.length < 2) {
      this.lignes.push(this.creerLigne());
    }
    this.restauration = false;
    this.brouillonRestaure.set(null);
  }

  /** Efface la saisie en cours et remet le formulaire à neuf. */
  recommencer(): void {
    this.restauration = true;
    this.form.reset({ tiersId: '', dateEmission: aujourdhui(), dateEcheance: '', objet: '' });
    while (this.lignes.length > 2) {
      this.lignes.removeAt(0);
    }
    while (this.lignes.length < 2) {
      this.lignes.push(this.creerLigne());
    }
    this.restauration = false;
    this.brouillon.effacer(this.formulaire(), this.entrepriseId());
    this.brouillonRestaure.set(null);
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
    this.recommencerSansEffacer();
    this.restaurer();
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
      .creerFacture(
        this.entrepriseId(),
        this.type(),
        {
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
        },
        this.clients().find((c) => c.id === valeurs.tiersId)?.nom,
      )
      .pipe(finalize(() => this.envoiEnCours.set(false)))
      .subscribe({
        next: (doc) => {
          this.documents.update((liste) => [doc, ...liste]);
          this.brouillon.effacer(this.formulaire(), this.entrepriseId());
          this.brouillonRestaure.set(null);
          this.restauration = true;
          this.form.reset({ tiersId: '', dateEmission: aujourdhui(), dateEcheance: '', objet: '' });
          while (this.lignes.length > 2) {
            this.lignes.removeAt(0);
          }
          this.restauration = false;
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

  /** Le devis ou la facture en PDF, à envoyer au client (nécessite une connexion : le PDF est produit par le serveur). */
  telechargerDocument(doc: FactureDto): void {
    this.actionEnCours.set(doc.id);
    this.erreur.set(null);
    this.comptabiliteService.telechargerDocumentPdf(this.entrepriseId(), doc.id).pipe(finalize(() => this.actionEnCours.set(null))).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const lien = document.createElement('a');
        lien.href = url;
        lien.download = `${doc.numero}.pdf`;
        lien.click();
        URL.revokeObjectURL(url);
      },
      error: () => this.erreur.set('Le PDF n’a pas pu être produit. Vérifiez votre connexion et réessayez.'),
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
