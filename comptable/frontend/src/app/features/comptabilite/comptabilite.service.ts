import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AiMatchProposalDto,
  ArticleDto,
  BalanceAgeeDto,
  BalanceDto,
  CommandeAchatDto,
  CompteComptableDto,
  ComptabiliserDocumentRequest,
  CreerArticleRequest,
  CreerCommandeAchatRequest,
  CreerDepotRequest,
  CreerEcritureRequest,
  CreerFactureAvoirRequest,
  CreerMouvementStockRequest,
  CreerTiersRequest,
  DepotDto,
  EcritureComptableDto,
  CreerFactureRequest,
  ComptabiliserAcquisitionRequest,
  LierAcquisitionRequest,
  CandidatAcquisitionDto,
  CreerImmobilisationRequest,
  SortirImmobilisationRequest,
  ExerciceComptableDto,
  FactureAvoirDto,
  FactureDto,
  FactureFournisseurDto,
  AvoirFournisseurDto,
  CreerAvoirFournisseurRequest,
  GenererDotationsResultDto,
  GrandLivreDto,
  ImmobilisationDto,
  JournalDto,
  LettrageResultDto,
  LettrerLignesRequest,
  LigneReleveDto,
  ModifierArticleRequest,
  MouvementStockDto,
  ReleveBancaireDto,
  TiersDto,
  TypeFacture,
  TypeTiers,
} from './models/comptabilite.model';
import {
  CreerInventaireRequest,
  InventaireDto,
  SaisirLigneInventaireRequest,
} from './models/inventaire.model';
import {
  CreerRegularisationRequest,
  RegularisationDto,
} from './models/regularisation.model';
import {
  CompteComptableArbreDto,
  CreerSubdivisionCompteRequest,
  ImporterReferenceRequest,
  ImporterReferenceResultDto,
  ModifierCompteRequest,
  PlanComptableReferenceDto,
} from './models/plan-comptable.model';
import {
  AccountingOperationProposalDto,
  AchatOperationRequest,
  AvoirOperationRequest,
  ReglementOperationRequest,
  RejeterPropositionRequest,
  VenteOperationRequest,
} from './models/operations.model';
import {
  AccountingAccountMappingDto,
  AccountPurpose,
  CreerAccountingAccountMappingRequest,
  ModifierAccountingAccountMappingRequest,
  ResolvedAccountDto,
  TestResolutionRequest,
} from './models/parametrage-comptable.model';

const BASE_URL = '/api/entreprises';
const REFERENTIEL_URL = '/api/plan-comptable-reference';

@Injectable({ providedIn: 'root' })
export class ComptabiliteService {
  private readonly http = inject(HttpClient);

  listerTiers(entrepriseId: string, type?: TypeTiers): Observable<TiersDto[]> {
    const params = type ? new HttpParams().set('type', type) : undefined;
    return this.http.get<TiersDto[]>(`${BASE_URL}/${entrepriseId}/tiers`, { params });
  }

  creerTiers(entrepriseId: string, request: CreerTiersRequest): Observable<TiersDto> {
    return this.http.post<TiersDto>(`${BASE_URL}/${entrepriseId}/tiers`, request);
  }

  listerComptes(entrepriseId: string): Observable<CompteComptableDto[]> {
    return this.http.get<CompteComptableDto[]>(`${BASE_URL}/${entrepriseId}/comptes`);
  }

  listerJournaux(entrepriseId: string): Observable<JournalDto[]> {
    return this.http.get<JournalDto[]>(`${BASE_URL}/${entrepriseId}/journaux`);
  }

  listerExercices(entrepriseId: string): Observable<ExerciceComptableDto[]> {
    return this.http.get<ExerciceComptableDto[]>(`${BASE_URL}/${entrepriseId}/exercices`);
  }

  // --- Plan comptable (fondation SYSCOHADA) -----------------------------------------------

  listerPlanComptable(entrepriseId: string): Observable<CompteComptableDto[]> {
    return this.http.get<CompteComptableDto[]>(`${BASE_URL}/${entrepriseId}/plan-comptable`);
  }

  arbrePlanComptable(entrepriseId: string): Observable<CompteComptableArbreDto[]> {
    return this.http.get<CompteComptableArbreDto[]>(`${BASE_URL}/${entrepriseId}/plan-comptable/arbre`);
  }

  importerReference(entrepriseId: string, request: ImporterReferenceRequest): Observable<ImporterReferenceResultDto> {
    return this.http.post<ImporterReferenceResultDto>(
      `${BASE_URL}/${entrepriseId}/plan-comptable/importer-reference`,
      request,
    );
  }

  creerSubdivisionCompte(entrepriseId: string, request: CreerSubdivisionCompteRequest): Observable<CompteComptableDto> {
    return this.http.post<CompteComptableDto>(`${BASE_URL}/${entrepriseId}/plan-comptable/comptes`, request);
  }

  modifierCompte(entrepriseId: string, compteId: string, request: ModifierCompteRequest): Observable<CompteComptableDto> {
    return this.http.put<CompteComptableDto>(`${BASE_URL}/${entrepriseId}/plan-comptable/comptes/${compteId}`, request);
  }

  supprimerCompte(entrepriseId: string, compteId: string): Observable<void> {
    return this.http.delete<void>(`${BASE_URL}/${entrepriseId}/plan-comptable/comptes/${compteId}`);
  }

  listerReferentielGlobal(classe?: number): Observable<PlanComptableReferenceDto[]> {
    const params = classe != null ? new HttpParams().set('classe', classe) : undefined;
    return this.http.get<PlanComptableReferenceDto[]>(REFERENTIEL_URL, { params });
  }

  // --- Moteur d'operations courantes (proposition avant comptabilisation) -----------------

  proposerAchat(entrepriseId: string, request: AchatOperationRequest): Observable<AccountingOperationProposalDto> {
    return this.http.post<AccountingOperationProposalDto>(
      `${BASE_URL}/${entrepriseId}/operations/achat/proposition`,
      request,
    );
  }

  proposerVente(entrepriseId: string, request: VenteOperationRequest): Observable<AccountingOperationProposalDto> {
    return this.http.post<AccountingOperationProposalDto>(
      `${BASE_URL}/${entrepriseId}/operations/vente/proposition`,
      request,
    );
  }

  proposerReglement(
    entrepriseId: string,
    request: ReglementOperationRequest,
  ): Observable<AccountingOperationProposalDto> {
    return this.http.post<AccountingOperationProposalDto>(
      `${BASE_URL}/${entrepriseId}/operations/reglement/proposition`,
      request,
    );
  }

  proposerAvoir(entrepriseId: string, request: AvoirOperationRequest): Observable<AccountingOperationProposalDto> {
    return this.http.post<AccountingOperationProposalDto>(
      `${BASE_URL}/${entrepriseId}/operations/avoir/proposition`,
      request,
    );
  }

  obtenirProposition(entrepriseId: string, propositionId: string): Observable<AccountingOperationProposalDto> {
    return this.http.get<AccountingOperationProposalDto>(
      `${BASE_URL}/${entrepriseId}/operations/propositions/${propositionId}`,
    );
  }

  validerProposition(entrepriseId: string, propositionId: string): Observable<AccountingOperationProposalDto> {
    return this.http.post<AccountingOperationProposalDto>(
      `${BASE_URL}/${entrepriseId}/operations/propositions/${propositionId}/valider`,
      {},
    );
  }

  rejeterProposition(
    entrepriseId: string,
    propositionId: string,
    request: RejeterPropositionRequest,
  ): Observable<AccountingOperationProposalDto> {
    return this.http.post<AccountingOperationProposalDto>(
      `${BASE_URL}/${entrepriseId}/operations/propositions/${propositionId}/rejeter`,
      request,
    );
  }

  cloturerExercice(
    entrepriseId: string,
    exerciceId: string,
    confirmation?: { avertissementsConfirmes: boolean; motif: string },
  ): Observable<ExerciceComptableDto> {
    return this.http.post<ExerciceComptableDto>(`${BASE_URL}/${entrepriseId}/exercices/${exerciceId}/cloturer`, confirmation ?? {});
  }

  lettrer(entrepriseId: string, request: LettrerLignesRequest): Observable<LettrageResultDto> {
    return this.http.post<LettrageResultDto>(`${BASE_URL}/${entrepriseId}/lettrage`, request);
  }

  delettrer(entrepriseId: string, compteId: string, code: string): Observable<void> {
    const params = new HttpParams().set('compteId', compteId).set('code', code);
    return this.http.delete<void>(`${BASE_URL}/${entrepriseId}/lettrage`, { params });
  }

  lettrerAutomatiquement(entrepriseId: string, compteId: string): Observable<LettrageResultDto[]> {
    const params = new HttpParams().set('compteId', compteId);
    return this.http.post<LettrageResultDto[]>(`${BASE_URL}/${entrepriseId}/lettrage/automatique`, {}, { params });
  }

  importerReleve(entrepriseId: string, formData: FormData): Observable<ReleveBancaireDto> {
    return this.http.post<ReleveBancaireDto>(`${BASE_URL}/${entrepriseId}/rapprochements`, formData);
  }

  listerReleves(entrepriseId: string): Observable<ReleveBancaireDto[]> {
    return this.http.get<ReleveBancaireDto[]>(`${BASE_URL}/${entrepriseId}/rapprochements`);
  }

  obtenirReleve(entrepriseId: string, releveId: string): Observable<ReleveBancaireDto> {
    return this.http.get<ReleveBancaireDto>(`${BASE_URL}/${entrepriseId}/rapprochements/${releveId}`);
  }

  pointerLigne(entrepriseId: string, releveId: string, ligneReleveId: string, ligneEcritureId: string): Observable<LigneReleveDto> {
    const params = new HttpParams().set('ligneEcritureId', ligneEcritureId);
    return this.http.post<LigneReleveDto>(
      `${BASE_URL}/${entrepriseId}/rapprochements/${releveId}/lignes/${ligneReleveId}/pointer`,
      {},
      { params },
    );
  }

  depointerLigne(entrepriseId: string, releveId: string, ligneReleveId: string): Observable<void> {
    return this.http.post<void>(
      `${BASE_URL}/${entrepriseId}/rapprochements/${releveId}/lignes/${ligneReleveId}/depointer`,
      {},
    );
  }

  /** 404 si aucune proposition IA (PROPOSED) n'existe pour cette ligne - cas normal quand le
   * Universal Matching Engine est désactivé côté Core ou n'a rien trouvé, laissé à l'appelant. */
  obtenirPropositionIA(entrepriseId: string, releveId: string, ligneReleveId: string): Observable<AiMatchProposalDto> {
    return this.http.get<AiMatchProposalDto>(
      `${BASE_URL}/${entrepriseId}/rapprochements/${releveId}/lignes/${ligneReleveId}/proposition-ia`,
    );
  }

  accepterPropositionIA(entrepriseId: string, propositionId: string): Observable<AiMatchProposalDto> {
    return this.http.post<AiMatchProposalDto>(
      `${BASE_URL}/${entrepriseId}/rapprochements/propositions-ia/${propositionId}/accepter`,
      {},
    );
  }

  rejeterPropositionIA(entrepriseId: string, propositionId: string): Observable<AiMatchProposalDto> {
    return this.http.post<AiMatchProposalDto>(
      `${BASE_URL}/${entrepriseId}/rapprochements/propositions-ia/${propositionId}/rejeter`,
      {},
    );
  }

  listerImmobilisations(entrepriseId: string): Observable<ImmobilisationDto[]> {
    return this.http.get<ImmobilisationDto[]>(`${BASE_URL}/${entrepriseId}/immobilisations`);
  }

  creerImmobilisation(entrepriseId: string, request: CreerImmobilisationRequest): Observable<ImmobilisationDto> {
    return this.http.post<ImmobilisationDto>(`${BASE_URL}/${entrepriseId}/immobilisations`, request);
  }

  comptabiliserAcquisition(
    entrepriseId: string,
    immobilisationId: string,
    request: ComptabiliserAcquisitionRequest,
  ): Observable<ImmobilisationDto> {
    return this.http.post<ImmobilisationDto>(
      `${BASE_URL}/${entrepriseId}/immobilisations/${immobilisationId}/comptabiliser-acquisition`,
      request,
    );
  }

  candidatsAcquisition(entrepriseId: string, immobilisationId: string): Observable<CandidatAcquisitionDto[]> {
    return this.http.get<CandidatAcquisitionDto[]>(
      `${BASE_URL}/${entrepriseId}/immobilisations/${immobilisationId}/candidats-acquisition`,
    );
  }

  lierAcquisition(
    entrepriseId: string,
    immobilisationId: string,
    request: LierAcquisitionRequest,
  ): Observable<ImmobilisationDto> {
    return this.http.post<ImmobilisationDto>(
      `${BASE_URL}/${entrepriseId}/immobilisations/${immobilisationId}/lier-acquisition`,
      request,
    );
  }

  mettreImmobilisationEnService(
    entrepriseId: string,
    immobilisationId: string,
    dateMiseEnService: string,
  ): Observable<ImmobilisationDto> {
    return this.http.post<ImmobilisationDto>(
      `${BASE_URL}/${entrepriseId}/immobilisations/${immobilisationId}/mise-en-service`,
      { dateMiseEnService },
    );
  }

  sortirImmobilisation(
    entrepriseId: string,
    immobilisationId: string,
    request: SortirImmobilisationRequest,
  ): Observable<ImmobilisationDto> {
    return this.http.post<ImmobilisationDto>(
      `${BASE_URL}/${entrepriseId}/immobilisations/${immobilisationId}/sortie`,
      request,
    );
  }

  genererDotations(entrepriseId: string, annee: number): Observable<GenererDotationsResultDto> {
    const params = new HttpParams().set('annee', annee);
    return this.http.post<GenererDotationsResultDto>(
      `${BASE_URL}/${entrepriseId}/immobilisations/generer-dotations`,
      {},
      { params },
    );
  }

  listerFactures(entrepriseId: string, type: TypeFacture): Observable<FactureDto[]> {
    const params = new HttpParams().set('type', type);
    return this.http.get<FactureDto[]>(`${BASE_URL}/${entrepriseId}/factures`, { params });
  }

  creerFacture(entrepriseId: string, type: TypeFacture, request: CreerFactureRequest): Observable<FactureDto> {
    const params = new HttpParams().set('type', type);
    return this.http.post<FactureDto>(`${BASE_URL}/${entrepriseId}/factures`, request, { params });
  }

  envoyerFacture(entrepriseId: string, factureId: string): Observable<FactureDto> {
    return this.http.post<FactureDto>(`${BASE_URL}/${entrepriseId}/factures/${factureId}/envoyer`, {});
  }

  accepterDevis(entrepriseId: string, devisId: string): Observable<FactureDto> {
    return this.http.post<FactureDto>(`${BASE_URL}/${entrepriseId}/factures/${devisId}/accepter`, {});
  }

  refuserDevis(entrepriseId: string, devisId: string): Observable<FactureDto> {
    return this.http.post<FactureDto>(`${BASE_URL}/${entrepriseId}/factures/${devisId}/refuser`, {});
  }

  convertirEnFacture(entrepriseId: string, devisId: string): Observable<FactureDto> {
    return this.http.post<FactureDto>(`${BASE_URL}/${entrepriseId}/factures/${devisId}/convertir`, {});
  }

  validerFacture(entrepriseId: string, factureId: string, compteProduitId: string): Observable<FactureDto> {
    const params = new HttpParams().set('compteProduitId', compteProduitId);
    return this.http.post<FactureDto>(`${BASE_URL}/${entrepriseId}/factures/${factureId}/valider`, {}, { params });
  }

  annulerFacture(entrepriseId: string, factureId: string): Observable<FactureDto> {
    return this.http.post<FactureDto>(`${BASE_URL}/${entrepriseId}/factures/${factureId}/annuler`, {});
  }

  creerAvoir(entrepriseId: string, factureId: string, request: CreerFactureAvoirRequest): Observable<FactureAvoirDto> {
    return this.http.post<FactureAvoirDto>(`${BASE_URL}/${entrepriseId}/factures/${factureId}/creer-avoir`, request);
  }

  listerAvoirs(entrepriseId: string): Observable<FactureAvoirDto[]> {
    return this.http.get<FactureAvoirDto[]>(`${BASE_URL}/${entrepriseId}/factures-avoir`);
  }

  validerAvoir(entrepriseId: string, avoirId: string): Observable<FactureAvoirDto> {
    return this.http.post<FactureAvoirDto>(`${BASE_URL}/${entrepriseId}/factures-avoir/${avoirId}/valider`, {});
  }

  telechargerAvoirPdf(entrepriseId: string, avoirId: string): Observable<Blob> {
    return this.http.get(`${BASE_URL}/${entrepriseId}/factures-avoir/${avoirId}/pdf`, { responseType: 'blob' });
  }

  listerEcritures(entrepriseId: string): Observable<EcritureComptableDto[]> {
    return this.http.get<EcritureComptableDto[]>(`${BASE_URL}/${entrepriseId}/ecritures`);
  }

  creerEcriture(entrepriseId: string, request: CreerEcritureRequest): Observable<EcritureComptableDto> {
    return this.http.post<EcritureComptableDto>(`${BASE_URL}/${entrepriseId}/ecritures`, request);
  }

  validerEcriture(entrepriseId: string, ecritureId: string): Observable<EcritureComptableDto> {
    return this.http.post<EcritureComptableDto>(`${BASE_URL}/${entrepriseId}/ecritures/${ecritureId}/valider`, {});
  }

  supprimerEcriture(entrepriseId: string, ecritureId: string): Observable<void> {
    return this.http.delete<void>(`${BASE_URL}/${entrepriseId}/ecritures/${ecritureId}`);
  }

  comptabiliserDocument(
    entrepriseId: string,
    documentId: string,
    request: ComptabiliserDocumentRequest,
  ): Observable<EcritureComptableDto> {
    return this.http.post<EcritureComptableDto>(
      `${BASE_URL}/${entrepriseId}/documents/${documentId}/comptabiliser`,
      request,
    );
  }

  balance(entrepriseId: string, dateDebut: string, dateFin: string): Observable<BalanceDto> {
    const params = new HttpParams().set('dateDebut', dateDebut).set('dateFin', dateFin);
    return this.http.get<BalanceDto>(`${BASE_URL}/${entrepriseId}/rapports/balance`, { params });
  }

  grandLivre(entrepriseId: string, compteId: string, dateDebut: string, dateFin: string): Observable<GrandLivreDto> {
    const params = new HttpParams().set('compteId', compteId).set('dateDebut', dateDebut).set('dateFin', dateFin);
    return this.http.get<GrandLivreDto>(`${BASE_URL}/${entrepriseId}/rapports/grand-livre`, { params });
  }

  journalOperations(
    entrepriseId: string,
    journalId: string,
    dateDebut: string,
    dateFin: string,
  ): Observable<EcritureComptableDto[]> {
    const params = new HttpParams().set('dateDebut', dateDebut).set('dateFin', dateFin);
    return this.http.get<EcritureComptableDto[]>(`${BASE_URL}/${entrepriseId}/rapports/journaux/${journalId}`, {
      params,
    });
  }

  balanceAgee(entrepriseId: string, dateArrete?: string): Observable<BalanceAgeeDto> {
    const params = dateArrete ? new HttpParams().set('dateArrete', dateArrete) : undefined;
    return this.http.get<BalanceAgeeDto>(`${BASE_URL}/${entrepriseId}/rapports/balance-agee`, { params });
  }

  exporterFec(entrepriseId: string, exerciceId: string): Observable<Blob> {
    return this.http.get(`${BASE_URL}/${entrepriseId}/rapports/fec/${exerciceId}`, { responseType: 'blob' });
  }

  listerArticles(entrepriseId: string): Observable<ArticleDto[]> {
    return this.http.get<ArticleDto[]>(`${BASE_URL}/${entrepriseId}/articles`);
  }

  creerArticle(entrepriseId: string, request: CreerArticleRequest): Observable<ArticleDto> {
    return this.http.post<ArticleDto>(`${BASE_URL}/${entrepriseId}/articles`, request);
  }

  listerMouvementsStock(entrepriseId: string, articleId: string): Observable<MouvementStockDto[]> {
    const params = new HttpParams().set('articleId', articleId);
    return this.http.get<MouvementStockDto[]>(`${BASE_URL}/${entrepriseId}/mouvements-stock`, { params });
  }

  enregistrerMouvementStock(entrepriseId: string, request: CreerMouvementStockRequest): Observable<MouvementStockDto> {
    return this.http.post<MouvementStockDto>(`${BASE_URL}/${entrepriseId}/mouvements-stock`, request);
  }

  obtenirArticle(entrepriseId: string, articleId: string): Observable<ArticleDto> {
    return this.http.get<ArticleDto>(`${BASE_URL}/${entrepriseId}/articles/${articleId}`);
  }

  modifierArticle(entrepriseId: string, articleId: string, request: ModifierArticleRequest): Observable<ArticleDto> {
    return this.http.put<ArticleDto>(`${BASE_URL}/${entrepriseId}/articles/${articleId}`, request);
  }

  listerDepots(entrepriseId: string): Observable<DepotDto[]> {
    return this.http.get<DepotDto[]>(`${BASE_URL}/${entrepriseId}/depots`);
  }

  creerDepot(entrepriseId: string, request: CreerDepotRequest): Observable<DepotDto> {
    return this.http.post<DepotDto>(`${BASE_URL}/${entrepriseId}/depots`, request);
  }

  // --- Inventaire physique (mission stock, Etape 3) ----------------------------------------

  listerInventaires(entrepriseId: string): Observable<InventaireDto[]> {
    return this.http.get<InventaireDto[]>(`${BASE_URL}/${entrepriseId}/inventaires`);
  }

  creerInventaire(entrepriseId: string, request: CreerInventaireRequest): Observable<InventaireDto> {
    return this.http.post<InventaireDto>(`${BASE_URL}/${entrepriseId}/inventaires`, request);
  }

  obtenirInventaire(entrepriseId: string, inventaireId: string): Observable<InventaireDto> {
    return this.http.get<InventaireDto>(`${BASE_URL}/${entrepriseId}/inventaires/${inventaireId}`);
  }

  saisirLigneInventaire(entrepriseId: string, inventaireId: string, request: SaisirLigneInventaireRequest): Observable<InventaireDto> {
    return this.http.post<InventaireDto>(`${BASE_URL}/${entrepriseId}/inventaires/${inventaireId}/lignes`, request);
  }

  validerInventaire(entrepriseId: string, inventaireId: string): Observable<InventaireDto> {
    return this.http.post<InventaireDto>(`${BASE_URL}/${entrepriseId}/inventaires/${inventaireId}/valider`, {});
  }

  cloturerInventaire(entrepriseId: string, inventaireId: string): Observable<InventaireDto> {
    return this.http.post<InventaireDto>(`${BASE_URL}/${entrepriseId}/inventaires/${inventaireId}/cloturer`, {});
  }

  // --- Regularisations de fin de periode (mission cloture Etape 4) ------------------------

  listerRegularisations(entrepriseId: string): Observable<RegularisationDto[]> {
    return this.http.get<RegularisationDto[]>(`${BASE_URL}/${entrepriseId}/regularisations`);
  }

  creerRegularisation(entrepriseId: string, request: CreerRegularisationRequest): Observable<RegularisationDto> {
    return this.http.post<RegularisationDto>(`${BASE_URL}/${entrepriseId}/regularisations`, request);
  }

  proposerRegularisation(entrepriseId: string, regularisationId: string): Observable<RegularisationDto> {
    return this.http.post<RegularisationDto>(`${BASE_URL}/${entrepriseId}/regularisations/${regularisationId}/proposition`, {});
  }

  listerCommandesAchat(entrepriseId: string): Observable<CommandeAchatDto[]> {
    return this.http.get<CommandeAchatDto[]>(`${BASE_URL}/${entrepriseId}/commandes-achat`);
  }

  creerCommandeAchat(entrepriseId: string, request: CreerCommandeAchatRequest): Observable<CommandeAchatDto> {
    return this.http.post<CommandeAchatDto>(`${BASE_URL}/${entrepriseId}/commandes-achat`, request);
  }

  validerReceptionCommande(entrepriseId: string, commandeId: string): Observable<CommandeAchatDto> {
    return this.http.post<CommandeAchatDto>(`${BASE_URL}/${entrepriseId}/commandes-achat/${commandeId}/valider-reception`, {});
  }

  genererFactureFournisseur(entrepriseId: string, commandeId: string): Observable<FactureFournisseurDto> {
    return this.http.post<FactureFournisseurDto>(
      `${BASE_URL}/${entrepriseId}/commandes-achat/${commandeId}/generer-facture-fournisseur`,
      {},
    );
  }

  creerAvoirFournisseur(
    entrepriseId: string,
    factureId: string,
    request: CreerAvoirFournisseurRequest,
  ): Observable<AvoirFournisseurDto> {
    return this.http.post<AvoirFournisseurDto>(`${BASE_URL}/${entrepriseId}/avoirs-fournisseur/factures/${factureId}`, request);
  }

  listerAccountingAccountMappings(entrepriseId: string, purpose?: AccountPurpose): Observable<AccountingAccountMappingDto[]> {
    let params = new HttpParams();
    if (purpose) {
      params = params.set('purpose', purpose);
    }
    return this.http.get<AccountingAccountMappingDto[]>(`${BASE_URL}/${entrepriseId}/parametres-comptables/mappings`, { params });
  }

  creerAccountingAccountMapping(
    entrepriseId: string,
    request: CreerAccountingAccountMappingRequest,
  ): Observable<AccountingAccountMappingDto> {
    return this.http.post<AccountingAccountMappingDto>(`${BASE_URL}/${entrepriseId}/parametres-comptables/mappings`, request);
  }

  modifierAccountingAccountMapping(
    entrepriseId: string,
    mappingId: string,
    request: ModifierAccountingAccountMappingRequest,
  ): Observable<AccountingAccountMappingDto> {
    return this.http.put<AccountingAccountMappingDto>(
      `${BASE_URL}/${entrepriseId}/parametres-comptables/mappings/${mappingId}`,
      request,
    );
  }

  supprimerAccountingAccountMapping(entrepriseId: string, mappingId: string): Observable<void> {
    return this.http.delete<void>(`${BASE_URL}/${entrepriseId}/parametres-comptables/mappings/${mappingId}`);
  }

  testerResolutionCompte(entrepriseId: string, request: TestResolutionRequest): Observable<ResolvedAccountDto> {
    return this.http.post<ResolvedAccountDto>(`${BASE_URL}/${entrepriseId}/parametres-comptables/test-resolution`, request);
  }
}
