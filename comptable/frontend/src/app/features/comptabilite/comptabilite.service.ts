import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AiMatchProposalDto,
  ArticleDto,
  BalanceAgeeDto,
  BalanceDto,
  BilanDto,
  CommandeAchatDto,
  CompteComptableDto,
  ComptabiliserDocumentRequest,
  CompteResultatDto,
  CreerArticleRequest,
  CreerCommandeAchatRequest,
  CreerEcritureRequest,
  CreerFactureAvoirRequest,
  CreerMouvementStockRequest,
  CreerTiersRequest,
  EcritureComptableDto,
  CreerFactureRequest,
  CreerImmobilisationRequest,
  ExerciceComptableDto,
  FactureAvoirDto,
  FactureDto,
  FactureFournisseurDto,
  GenererDotationsResultDto,
  GrandLivreDto,
  ImmobilisationDto,
  JournalDto,
  LettrageResultDto,
  LettrerLignesRequest,
  LigneReleveDto,
  MouvementStockDto,
  ReleveBancaireDto,
  TiersDto,
  TypeFacture,
  TypeTiers,
} from './models/comptabilite.model';

const BASE_URL = '/api/entreprises';

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

  bilan(entrepriseId: string, exerciceId: string): Observable<BilanDto> {
    const params = new HttpParams().set('exerciceId', exerciceId);
    return this.http.get<BilanDto>(`${BASE_URL}/${entrepriseId}/etats-financiers/bilan`, { params });
  }

  compteResultat(entrepriseId: string, exerciceId: string): Observable<CompteResultatDto> {
    const params = new HttpParams().set('exerciceId', exerciceId);
    return this.http.get<CompteResultatDto>(`${BASE_URL}/${entrepriseId}/etats-financiers/compte-resultat`, { params });
  }

  cloturerExercice(entrepriseId: string, exerciceId: string): Observable<ExerciceComptableDto> {
    return this.http.post<ExerciceComptableDto>(`${BASE_URL}/${entrepriseId}/exercices/${exerciceId}/cloturer`, {});
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

  marquerFacturePayee(entrepriseId: string, factureId: string): Observable<FactureDto> {
    return this.http.post<FactureDto>(`${BASE_URL}/${entrepriseId}/factures/${factureId}/marquer-payee`, {});
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
}
