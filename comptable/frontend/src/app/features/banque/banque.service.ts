import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AllocationDto,
  ApercuImportDto,
  CandidatDto,
  FactureCandidateDto,
  LotImportDto,
  MetriquesBanqueDto,
  PageTransactions,
  PropositionLotDto,
  ReglementARepartirDto,
  ResultatImportDto,
  ResultatLotDto,
  StatutTransaction,
  SyntheseBanqueDto,
} from './banque.model';

const BASE = '/api/entreprises';

export interface FiltreTransactions {
  compteId?: string;
  periode?: string;
  statut?: StatutTransaction | '';
  q?: string;
  tri?: string;
  ordre?: string;
  page?: number;
  taille?: number;
}

@Injectable({ providedIn: 'root' })
export class BanqueService {
  private readonly http = inject(HttpClient);

  private url(entrepriseId: string, chemin: string): string {
    return `${BASE}/${entrepriseId}/banque${chemin}`;
  }

  private params(valeurs: Record<string, string | number | undefined | null>): HttpParams {
    let p = new HttpParams();
    for (const [cle, valeur] of Object.entries(valeurs)) {
      if (valeur !== undefined && valeur !== null && valeur !== '') {
        p = p.set(cle, String(valeur));
      }
    }
    return p;
  }

  synthese(entrepriseId: string, compteId?: string, periode?: string): Observable<SyntheseBanqueDto> {
    return this.http.get<SyntheseBanqueDto>(this.url(entrepriseId, '/synthese'), { params: this.params({ compteId, periode }) });
  }

  metriques(entrepriseId: string): Observable<MetriquesBanqueDto> {
    return this.http.get<MetriquesBanqueDto>(this.url(entrepriseId, '/metriques'));
  }

  transactions(entrepriseId: string, filtre: FiltreTransactions): Observable<PageTransactions> {
    return this.http.get<PageTransactions>(this.url(entrepriseId, '/transactions'), {
      params: this.params({ ...filtre }),
    });
  }

  candidats(entrepriseId: string, transactionId: string, q?: string): Observable<CandidatDto[]> {
    return this.http.get<CandidatDto[]>(this.url(entrepriseId, `/transactions/${transactionId}/candidats`), { params: this.params({ q }) });
  }

  rapprocher(entrepriseId: string, transactionId: string, ligneEcritureId: string): Observable<unknown> {
    return this.http.post(this.url(entrepriseId, `/transactions/${transactionId}/rapprocher`), { ligneEcritureId });
  }

  annulerRapprochement(entrepriseId: string, transactionId: string, motif: string): Observable<unknown> {
    return this.http.post(this.url(entrepriseId, `/transactions/${transactionId}/annuler-rapprochement`), { motif });
  }

  doublonDistinct(entrepriseId: string, transactionId: string): Observable<void> {
    return this.http.post<void>(this.url(entrepriseId, `/transactions/${transactionId}/doublon-distinct`), {});
  }

  confirmer(entrepriseId: string, reconciliationId: string): Observable<unknown> {
    return this.http.post(this.url(entrepriseId, `/rapprochements/${reconciliationId}/confirmer`), {});
  }

  rejeter(entrepriseId: string, reconciliationId: string, motif?: string): Observable<unknown> {
    return this.http.post(this.url(entrepriseId, `/rapprochements/${reconciliationId}/rejeter`), { motif: motif ?? '' });
  }

  confirmerVirement(entrepriseId: string, transfertId: string): Observable<unknown> {
    return this.http.post(this.url(entrepriseId, `/virements-internes/${transfertId}/confirmer`), {});
  }

  rejeterVirement(entrepriseId: string, transfertId: string): Observable<unknown> {
    return this.http.post(this.url(entrepriseId, `/virements-internes/${transfertId}/rejeter`), { motif: '' });
  }

  annulerVirement(entrepriseId: string, transfertId: string, motif: string): Observable<unknown> {
    return this.http.post(this.url(entrepriseId, `/virements-internes/${transfertId}/annuler`), { motif });
  }

  apercuLot(entrepriseId: string, confianceMin: number): Observable<PropositionLotDto[]> {
    return this.http.get<PropositionLotDto[]>(this.url(entrepriseId, '/rapprochements/lot/apercu'), { params: this.params({ confianceMin }) });
  }

  confirmerLot(entrepriseId: string, reconciliationIds: string[], confianceMin: number): Observable<ResultatLotDto> {
    return this.http.post<ResultatLotDto>(this.url(entrepriseId, '/rapprochements/lot/confirmer'), { reconciliationIds, confianceMin });
  }

  apercuImport(entrepriseId: string, compteId: string, fichier: File, soldeOuverture?: string, soldeCloture?: string): Observable<ApercuImportDto> {
    return this.http.post<ApercuImportDto>(this.url(entrepriseId, '/imports/apercu'), this.formulaire(compteId, fichier, soldeOuverture, soldeCloture));
  }

  importer(entrepriseId: string, compteId: string, fichier: File, soldeOuverture?: string, soldeCloture?: string): Observable<ResultatImportDto> {
    return this.http.post<ResultatImportDto>(this.url(entrepriseId, '/imports'), this.formulaire(compteId, fichier, soldeOuverture, soldeCloture));
  }

  lots(entrepriseId: string): Observable<LotImportDto[]> {
    return this.http.get<LotImportDto[]>(this.url(entrepriseId, '/imports'));
  }

  reglementsARepartir(entrepriseId: string): Observable<ReglementARepartirDto[]> {
    return this.http.get<ReglementARepartirDto[]>(this.url(entrepriseId, '/paiements/reglements-a-repartir'));
  }

  facturesCandidates(entrepriseId: string, paymentLineId: string, q?: string): Observable<FactureCandidateDto[]> {
    return this.http.get<FactureCandidateDto[]>(this.url(entrepriseId, `/paiements/reglements/${paymentLineId}/factures-candidates`), {
      params: this.params({ q }),
    });
  }

  allouer(entrepriseId: string, paymentLineId: string, invoiceLineId: string, montant: number): Observable<unknown> {
    return this.http.post(this.url(entrepriseId, '/paiements/allocations'), { paymentLineId, invoiceLineId, montant });
  }

  allocations(entrepriseId: string): Observable<AllocationDto[]> {
    return this.http.get<AllocationDto[]>(this.url(entrepriseId, '/paiements/allocations'));
  }

  annulerAllocation(entrepriseId: string, allocationId: string, motif: string): Observable<unknown> {
    return this.http.post(this.url(entrepriseId, `/paiements/allocations/${allocationId}/annuler`), { motif });
  }

  private formulaire(compteId: string, fichier: File, soldeOuverture?: string, soldeCloture?: string): FormData {
    const donnees = new FormData();
    donnees.set('compteId', compteId);
    donnees.set('fichier', fichier);
    if (soldeOuverture) {
      donnees.set('soldeOuverture', soldeOuverture);
    }
    if (soldeCloture) {
      donnees.set('soldeCloture', soldeCloture);
    }
    return donnees;
  }
}
