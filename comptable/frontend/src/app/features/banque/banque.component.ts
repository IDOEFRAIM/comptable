import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { finalize, map } from 'rxjs/operators';
import { ComptabiliteService } from '../comptabilite/comptabilite.service';
import { CompteComptableDto } from '../comptabilite/models/comptabilite.model';
import {
  AllocationDto,
  ApercuImportDto,
  CandidatDto,
  FactureCandidateDto,
  LIBELLES_STATUT_PAIEMENT,
  LIBELLES_STATUT_TRANSACTION,
  LotImportDto,
  MetriquesBanqueDto,
  PageTransactions,
  PropositionLotDto,
  ReglementARepartirDto,
  StatutTransaction,
  SyntheseBanqueDto,
  TransactionDto,
} from './banque.model';
import { BanqueService } from './banque.service';

type Onglet = 'TRANSACTIONS' | 'REGLEMENTS' | 'IMPORTS';

/**
 * Écran Banque du cabinet : ce qui est rapproché tout seul, ce qui demande UNE décision, ce qui n'a pas de correspondance. Les cas certains sont
 * déjà rangés ; ici on ne traite que les exceptions. Aucun identifiant n'est jamais saisi : on choisit un mouvement, une facture, un règlement par
 * ses libellés. La validation en lot passe TOUJOURS par un aperçu filtré par confiance.
 */
@Component({
  selector: 'app-banque',
  standalone: true,
  imports: [FormsModule, CurrencyPipe, DatePipe, DecimalPipe],
  template: `
    <div class="page-banque">
      <header class="entete">
        <h2>Banque{{ periode ? ' — ' + periode : '' }}</h2>
        <div class="filtres-haut">
          <label>Compte
            <select [(ngModel)]="compteId" (ngModelChange)="recharger()" data-champ="compte">
              <option value="">Tous les comptes</option>
              @for (c of comptes(); track c.id) {
                <option [value]="c.id">{{ c.numero }} — {{ c.intitule }}</option>
              }
            </select>
          </label>
          <label>Période
            <input type="month" [(ngModel)]="periode" (ngModelChange)="recharger()" data-champ="periode" />
          </label>
        </div>
      </header>

      @if (info()) {
        <p class="message-info" role="status">{{ info() }}</p>
      }
      @if (erreur()) {
        <p class="message-erreur" role="alert">{{ erreur() }}</p>
      }

      <nav class="onglets" role="tablist">
        <button type="button" role="tab" [class.actif]="onglet() === 'TRANSACTIONS'" (click)="ouvrirOnglet('TRANSACTIONS')" data-onglet="transactions">Transactions</button>
        <button type="button" role="tab" [class.actif]="onglet() === 'REGLEMENTS'" (click)="ouvrirOnglet('REGLEMENTS')" data-onglet="reglements">
          Règlements à répartir @if (reglements().length) { <span class="pastille">{{ reglements().length }}</span> }
        </button>
        <button type="button" role="tab" [class.actif]="onglet() === 'IMPORTS'" (click)="ouvrirOnglet('IMPORTS')" data-onglet="imports">Imports</button>
        <button type="button" class="bouton-principal petit" (click)="importOuvert.set(!importOuvert())" data-action="ouvrir-import">+ Importer un relevé</button>
      </nav>

      <!-- ------------------------------------------------------------------------------------------ import : aperçu puis confirmation -->
      @if (importOuvert()) {
        <section class="carte panneau-import" data-bloc="import">
          <h3>Importer un relevé</h3>
          <div class="ligne-champs">
            <label>Compte bancaire
              <select [(ngModel)]="importCompteId" data-champ="import-compte">
                <option value="">Choisir…</option>
                @for (c of comptes(); track c.id) {
                  <option [value]="c.id">{{ c.numero }} — {{ c.intitule }}</option>
                }
              </select>
            </label>
            <label>Fichier CSV
              <input type="file" accept=".csv,.txt" (change)="choisirFichier($event)" data-champ="import-fichier" />
            </label>
            <label>Solde d'ouverture (facultatif)
              <input type="text" inputmode="decimal" [(ngModel)]="soldeOuverture" />
            </label>
            <label>Solde de clôture (facultatif)
              <input type="text" inputmode="decimal" [(ngModel)]="soldeCloture" />
            </label>
          </div>
          <div class="actions">
            <button type="button" class="bouton-principal" [disabled]="occupe() || !importCompteId || !fichier()" (click)="apercuImport()" data-action="apercu-import">Aperçu</button>
            <button type="button" class="bouton-lien" (click)="fermerImport()">Annuler</button>
          </div>

          @if (apercuImport_(); as a) {
            <div class="apercu" data-bloc="apercu-import">
              <h4>Ce qui sera importé</h4>
              <ul class="chiffres">
                <li><strong>{{ a.lignesTotal }}</strong> ligne(s) lue(s)</li>
                <li><strong>{{ a.valides }}</strong> valide(s)</li>
                <li [class.alerte]="a.invalides > 0"><strong>{{ a.invalides }}</strong> invalide(s)</li>
                <li><strong>{{ a.doublonsExacts }}</strong> déjà importée(s) (ignorées)</li>
                <li [class.alerte]="a.doublonsPossibles > 0"><strong>{{ a.doublonsPossibles }}</strong> doublon(s) possible(s)</li>
                <li><strong>{{ a.aImporter }}</strong> à importer</li>
              </ul>
              <p class="meta">
                Devise {{ a.devise }}
                @if (a.debut) { · du {{ a.debut | date: 'dd/MM/yyyy' }} au {{ a.fin | date: 'dd/MM/yyyy' }} }
                · crédits {{ a.totalCredits | currency: a.devise : 'symbol' : '1.0-0' }} · débits {{ a.totalDebits | currency: a.devise : 'symbol' : '1.0-0' }}
              </p>
              @if (a.soldesCoherents === true) {
                <p class="ok">Les soldes sont cohérents : ouverture + mouvements = clôture.</p>
              } @else if (a.soldesCoherents === false) {
                <p class="message-erreur">Les soldes ne sont pas cohérents (écart de {{ a.ecartSolde | currency: a.devise : 'symbol' : '1.0-0' }}) : vérifiez le fichier.</p>
              }
              @if (a.dejaImporteIntegralement) {
                <p class="message-info">Ce relevé a déjà été importé : il n'y a rien de nouveau.</p>
              }
              @for (r of a.rejets; track r.numeroLigne) {
                <p class="rejet">Ligne {{ r.numeroLigne }} : {{ r.raison }}</p>
              }
              <div class="actions">
                <button type="button" class="bouton-principal" [disabled]="occupe() || a.aImporter === 0" (click)="confirmerImport()" data-action="confirmer-import">
                  Confirmer l'import de {{ a.aImporter }} transaction(s)
                </button>
              </div>
            </div>
          }
        </section>
      }

      <!-- ------------------------------------------------------------------------------------------------------ transactions -->
      @if (onglet() === 'TRANSACTIONS') {
        @if (synthese(); as s) {
          <div class="tuiles">
            <button type="button" class="tuile" [class.active]="statut === ''" (click)="filtrer('')" data-tuile="transactions"><strong>{{ s.transactions }}</strong><span>Transactions</span></button>
            <button type="button" class="tuile" [class.active]="statut === 'RAPPROCHEE'" (click)="filtrer('RAPPROCHEE')" data-tuile="rapprochees"><strong>{{ s.rapprochees }}</strong><span>Rapprochées</span></button>
            <button type="button" class="tuile" [class.active]="statut === 'A_CONFIRMER'" (click)="filtrer('A_CONFIRMER')" data-tuile="a-confirmer"><strong>{{ s.aConfirmer }}</strong><span>À confirmer</span></button>
            <button type="button" class="tuile" [class.active]="statut === 'AMBIGUE'" (click)="filtrer('AMBIGUE')" data-tuile="ambigues"><strong>{{ s.ambigues }}</strong><span>Ambiguës</span></button>
            <button type="button" class="tuile tuile-alerte" [class.active]="statut === 'SANS_CORRESPONDANCE'" (click)="filtrer('SANS_CORRESPONDANCE')" data-tuile="sans"><strong>{{ s.sansCorrespondance }}</strong><span>Sans correspondance</span></button>
            <button type="button" class="tuile" [class.active]="statut === 'VIREMENT_INTERNE'" (click)="filtrer('VIREMENT_INTERNE')" data-tuile="virements"><strong>{{ s.virementsInternes }}</strong><span>Virements internes</span></button>
            <div class="tuile" data-tuile="taux"><strong>{{ s.tauxRapproche | number: '1.1-1' }} %</strong><span>Taux rapproché</span></div>
          </div>
          @if (s.doublonsPossibles > 0) {
            <p class="message-info">{{ s.doublonsPossibles }} transaction(s) ressemblent à une autre (même jour, même montant) : à vérifier.</p>
          }
        }

        <div class="barre-liste">
          <input type="search" placeholder="Rechercher un libellé, une référence, un montant…" [(ngModel)]="recherche" (keyup.enter)="recharger()" data-champ="recherche" />
          <button type="button" class="bouton-lien" (click)="recharger()">Rechercher</button>
          <select [(ngModel)]="tri" (ngModelChange)="recharger()" data-champ="tri" aria-label="Trier par">
            <option value="date-desc">Date (récentes d'abord)</option>
            <option value="date-asc">Date (anciennes d'abord)</option>
            <option value="montant-desc">Montant (décroissant)</option>
            <option value="montant-asc">Montant (croissant)</option>
            <option value="libelle-asc">Libellé (A → Z)</option>
          </select>
          @if (synthese() && synthese()!.aConfirmer > 0) {
            <button type="button" class="bouton-principal petit" (click)="ouvrirLot()" data-action="ouvrir-lot">Valider les correspondances fortes…</button>
          }
        </div>

        @if (lotOuvert()) {
          <section class="carte panneau-lot" data-bloc="lot">
            <h3>Validation en lot</h3>
            <label>Confiance minimale
              <input type="number" min="50" max="100" step="5" [(ngModel)]="confianceMin" (change)="ouvrirLot()" data-champ="confiance-min" />
            </label>
            @if (lot().length === 0) {
              <p class="aide">Aucune proposition n'atteint ce niveau de confiance.</p>
            }
            @for (p of lot(); track p.reconciliationId) {
              <label class="ligne-lot">
                <input type="checkbox" [checked]="selectionLot().has(p.reconciliationId)" (change)="basculerLot(p.reconciliationId)" />
                <span>{{ p.date | date: 'dd/MM/yyyy' }}</span>
                <span class="libelle">{{ p.libelle }}</span>
                <strong>{{ p.montant | currency: 'XOF' : 'symbol' : '1.0-0' }}</strong>
                <span>→ {{ p.piece }}</span>
                <span class="badge badge-recu">{{ p.confiance }} %</span>
              </label>
            }
            <div class="actions">
              <button type="button" class="bouton-principal" [disabled]="occupe() || selectionLot().size === 0" (click)="confirmerLot()" data-action="confirmer-lot">
                Valider {{ selectionLot().size }} correspondance(s)
              </button>
              <button type="button" class="bouton-lien" (click)="lotOuvert.set(false)">Fermer</button>
            </div>
          </section>
        }

        @if (chargement()) {
          <p class="meta">Chargement…</p>
        } @else if ((page()?.lignes?.length ?? 0) === 0) {
          <p class="aide">Aucune transaction pour ces critères.</p>
        }
        @for (t of page()?.lignes ?? []; track t.id) {
          <article class="transaction" [attr.data-statut]="t.statut" [attr.data-transaction]="t.id">
            <div class="ligne">
              <span class="date">{{ t.date | date: 'dd/MM/yyyy' }}</span>
              <span class="libelle">{{ t.libelle }}</span>
              <strong class="montant" [class.sortie]="t.sens === 'DEBIT'">{{ t.montant | currency: 'XOF' : 'symbol' : '1.0-0' }}</strong>
              <span class="badge" [class]="'badge ' + classeStatut(t.statut)">{{ libellesStatut[t.statut] }}</span>
              @if (t.doublonPossible) {
                <span class="badge badge-attente">Doublon possible</span>
              }
            </div>

            @if (t.proposition; as p) {
              <div class="proposition">
                @if (p.type === 'VIREMENT_INTERNE') {
                  <span>
                    {{ t.statut === 'VIREMENT_INTERNE' ? 'Virement interne' : 'Virement interne probable' }}
                    @if (p.contrepartie) { <strong>{{ p.contrepartie }}</strong> }
                    — ni charge, ni produit, ni règlement d'un tiers.
                  </span>
                  @if (p.confiance !== null) {
                    <span class="badge badge-recu" data-confiance>Confiance {{ p.confiance }} %</span>
                  }
                } @else if (t.statut === 'AMBIGUE') {
                  <span>{{ p.nbCandidats }} mouvements possibles — le système ne choisit pas.</span>
                } @else {
                  <span>
                    {{ t.statut === 'RAPPROCHEE' ? 'Rapprochée avec' : 'Correspondance proposée :' }}
                    <strong>{{ p.piece }}</strong>
                    @if (p.libelleEcriture) { — {{ p.libelleEcriture }} }
                    @if (p.dateEcriture) { ({{ p.dateEcriture | date: 'dd/MM/yyyy' }}) }
                  </span>
                  @if (p.confiance !== null) {
                    <span class="badge badge-recu" data-confiance>Confiance {{ p.confiance }} %</span>
                  }
                  @if (p.source === 'SYSTEM_EXACT') { <span class="meta">automatique</span> }
                  @if (p.source === 'USER') { <span class="meta">par le comptable</span> }
                }
                <button type="button" class="bouton-lien" (click)="basculerDetail(t)" data-action="detail">Pourquoi ?</button>
              </div>
              @if (detailOuvert() === t.id) {
                <div class="detail" data-bloc="detail">
                  <h4>Correspondance proposée : {{ p.piece }}</h4>
                  @for (c of p.criteres; track c.code) {
                    <p class="critere">
                      <span [class.ko]="!c.satisfait">{{ c.satisfait ? '✓' : '⚠' }}</span> {{ c.libelle }}
                      <span class="meta">{{ c.points }} pt</span>
                    </p>
                  }
                  @if (p.criteres.length === 0 && p.explication) {
                    <p>{{ p.explication }}</p>
                  }
                  @if (p.confiance !== null) {
                    <p><strong>Confiance : {{ p.confiance }} %</strong></p>
                  }
                </div>
              }
            }

            <div class="actions">
              @if (t.statut === 'A_CONFIRMER' && t.proposition?.type === 'VIREMENT_INTERNE') {
                <button type="button" class="bouton-principal petit" (click)="confirmerVirement(t)" data-action="confirmer-virement">Confirmer le virement interne</button>
                <button type="button" class="bouton-lien" (click)="rejeterVirement(t)" data-action="rejeter-virement">Ce n'est pas un virement interne</button>
              } @else if (t.statut === 'A_CONFIRMER') {
                @if (t.proposition?.reconciliationId) {
                  <button type="button" class="bouton-principal petit" (click)="confirmer(t)" data-action="confirmer">Confirmer</button>
                  <button type="button" class="bouton-lien" (click)="rejeter(t)" data-action="rejeter">Rejeter</button>
                } @else if (t.proposition) {
                  <button type="button" class="bouton-principal petit" (click)="choisir(t, t.proposition.ligneEcritureId)" data-action="confirmer">Confirmer</button>
                }
                <button type="button" class="bouton-lien" (click)="ouvrirCandidats(t)" data-action="autre-candidat">Choisir un autre mouvement…</button>
              }
              @if (t.statut === 'AMBIGUE' || t.statut === 'SANS_CORRESPONDANCE') {
                <button type="button" class="bouton-principal petit" (click)="ouvrirCandidats(t)" data-action="choisir">
                  {{ t.statut === 'AMBIGUE' ? 'Choisir le bon mouvement…' : 'Rechercher un mouvement…' }}
                </button>
              }
              @if (t.statut === 'RAPPROCHEE') {
                <button type="button" class="bouton-lien" (click)="demanderAnnulation(t)" data-action="annuler">Annuler le rapprochement…</button>
              }
              @if (t.statut === 'VIREMENT_INTERNE') {
                <button type="button" class="bouton-lien" (click)="demanderAnnulation(t)" data-action="annuler-virement">Annuler le virement interne…</button>
              }
              @if (t.doublonPossible && t.statut !== 'RAPPROCHEE') {
                <button type="button" class="bouton-lien" (click)="doublonDistinct(t)" data-action="distincte">Ce sont deux opérations distinctes</button>
              }
            </div>

            @if (annulationEn() === t.id) {
              <div class="panneau-motif" data-bloc="motif">
                <label>Motif de l'annulation (obligatoire)
                  <input type="text" [(ngModel)]="motifAnnulation" data-champ="motif" />
                </label>
                <button type="button" class="bouton-principal petit" [disabled]="!motifAnnulation.trim() || occupe()" (click)="annuler(t)" data-action="valider-annulation">Annuler le rapprochement</button>
                <button type="button" class="bouton-lien" (click)="annulationEn.set(null)">Fermer</button>
              </div>
            }

            @if (candidatsPour() === t.id) {
              <div class="panneau-candidats" data-bloc="candidats">
                <h4>Quel mouvement correspond ?</h4>
                <div class="recherche-candidats">
                  <input type="search" placeholder="Rechercher par pièce ou libellé…" [(ngModel)]="rechercheCandidat" (keyup.enter)="chargerCandidats(t)" data-champ="recherche-candidat" />
                  <button type="button" class="bouton-lien" (click)="chargerCandidats(t)">Rechercher</button>
                </div>
                @if (candidats().length === 0) {
                  <p class="aide">Aucun mouvement du compte ne correspond à cette recherche.</p>
                }
                @for (c of candidats(); track c.ligneEcritureId) {
                  <div class="candidat" [class.fort]="c.score >= 80" [attr.data-candidat]="c.ligneEcritureId">
                    <span class="piece">{{ c.piece }}</span>
                    <span>{{ c.date | date: 'dd/MM/yyyy' }}</span>
                    <span class="libelle">{{ c.libelle }}</span>
                    <strong>{{ c.montant | currency: 'XOF' : 'symbol' : '1.0-0' }}</strong>
                    <span class="badge" [class]="'badge ' + (c.score >= 80 ? 'badge-recu' : 'badge-attente')">{{ c.score }} %</span>
                    <span class="meta">
                      @for (k of c.criteres; track k.code) {
                        <span [class.ko]="!k.satisfait">{{ k.satisfait ? '✓' : '⚠' }} {{ k.libelle }}&nbsp;</span>
                      }
                    </span>
                    @if (c.dejaRapprochee) {
                      <span class="meta">déjà rapprochée</span>
                    } @else {
                      <button type="button" class="bouton-principal petit" (click)="choisir(t, c.ligneEcritureId)" data-action="choisir-candidat">Choisir</button>
                    }
                  </div>
                }
                <button type="button" class="bouton-lien" (click)="candidatsPour.set(null)">Fermer</button>
              </div>
            }
          </article>
        }

        @if ((page()?.totalPages ?? 0) > 1) {
          <div class="pagination">
            <button type="button" class="bouton-lien" [disabled]="pageCourante === 0" (click)="changerPage(-1)">Précédent</button>
            <span>Page {{ pageCourante + 1 }} / {{ page()!.totalPages }}</span>
            <button type="button" class="bouton-lien" [disabled]="pageCourante + 1 >= page()!.totalPages" (click)="changerPage(1)">Suivant</button>
          </div>
        }

        @if (metriques(); as m) {
          <p class="meta mesures">
            {{ m.pctRapprochesAutomatiquement }} % rapprochées automatiquement · {{ m.pctNecessitantConfirmation }} % à confirmer ·
            {{ m.pctAmbigues }} % ambiguës · {{ m.pctSansCorrespondance }} % sans correspondance ·
            {{ m.facturesOuvertes }} facture(s) ouverte(s) · non rapproché : {{ m.montantNonRapproche | currency: 'XOF' : 'symbol' : '1.0-0' }}
            @if (m.plusAncienEcartJours !== null) { · plus ancien écart : {{ m.plusAncienEcartJours }} jour(s) }
          </p>
        }
      }

      <!-- ------------------------------------------------------------------------------------------ règlements à répartir -->
      @if (onglet() === 'REGLEMENTS') {
        <section data-bloc="reglements">
          <p class="aide">Un règlement peut solder une ou plusieurs factures, ou une facture peut être réglée en plusieurs fois. Les cas certains (même tiers, même montant) sont affectés automatiquement.</p>
          @if (reglements().length === 0) {
            <p class="aide">Aucun règlement en attente d'affectation.</p>
          }
          @for (r of reglements(); track r.paymentLineId) {
            <article class="transaction" [attr.data-reglement]="r.paymentLineId">
              <div class="ligne">
                <span class="date">{{ r.date | date: 'dd/MM/yyyy' }}</span>
                <span class="libelle">{{ r.tiers }} — {{ r.piece }}</span>
                <strong class="montant">{{ r.montant | currency: 'XOF' : 'symbol' : '1.0-0' }}</strong>
                <span class="meta">non affecté : {{ r.nonAffecte | currency: 'XOF' : 'symbol' : '1.0-0' }}</span>
                <button type="button" class="bouton-principal petit" (click)="ouvrirRepartition(r)" data-action="repartir">Répartir…</button>
              </div>
              @if (repartitionPour() === r.paymentLineId) {
                <div class="panneau-candidats" data-bloc="repartition">
                  <h4>Factures à régler avec ce règlement (reste à affecter : {{ resteARepartir() | currency: 'XOF' : 'symbol' : '1.0-0' }})</h4>
                  <div class="recherche-candidats">
                    <input type="search" placeholder="Rechercher une facture (numéro, libellé)…" [(ngModel)]="rechercheFacture" (keyup.enter)="chargerFactures(r)" data-champ="recherche-facture" />
                    <button type="button" class="bouton-lien" (click)="chargerFactures(r)">Rechercher</button>
                  </div>
                  @if (factures().length === 0) {
                    <p class="aide">Aucune facture ouverte pour ce tiers.</p>
                  }
                  @for (f of factures(); track f.invoiceLineId) {
                    <div class="candidat" [attr.data-facture]="f.invoiceLineId">
                      <span class="piece">{{ f.piece }}</span>
                      <span>{{ f.date | date: 'dd/MM/yyyy' }}</span>
                      <span class="libelle">{{ f.libelle }}</span>
                      <span>reste {{ f.reste | currency: 'XOF' : 'symbol' : '1.0-0' }} / {{ f.montant | currency: 'XOF' : 'symbol' : '1.0-0' }}</span>
                      <span class="badge badge-attente">{{ libellesPaiement[f.statut] }}</span>
                      <input type="number" min="1" class="montant-alloue" [ngModel]="montantPropose(f)" (ngModelChange)="saisirMontant(f, $event)" [attr.data-montant]="f.invoiceLineId" />
                      <button type="button" class="bouton-principal petit" [disabled]="occupe()" (click)="allouer(r, f)" data-action="affecter">Affecter</button>
                    </div>
                  }
                  <button type="button" class="bouton-lien" (click)="repartitionPour.set(null)">Fermer</button>
                </div>
              }
            </article>
          }

          <h3>Affectations</h3>
          @for (a of allocations(); track a.id) {
            <p class="allocation" [class.annulee]="a.statut === 'REVERSED'" [attr.data-allocation]="a.id">
              {{ a.creeLe | date: 'dd/MM/yyyy HH:mm' }} — {{ a.piecePaiement }} → {{ a.pieceFacture }} ({{ a.tiers }}) :
              <strong>{{ a.montant | currency: 'XOF' : 'symbol' : '1.0-0' }}</strong>
              <span class="meta">{{ a.source === 'USER' ? 'par le comptable' : 'automatique' }}</span>
              @if (a.statut === 'ACTIVE') {
                <button type="button" class="bouton-lien" (click)="annulationAllocation.set(a.id)" data-action="annuler-allocation">Annuler</button>
              } @else {
                <span class="meta">annulée : {{ a.motifAnnulation }}</span>
              }
            </p>
            @if (annulationAllocation() === a.id) {
              <div class="panneau-motif">
                <label>Motif (obligatoire)
                  <input type="text" [(ngModel)]="motifAllocation" data-champ="motif-allocation" />
                </label>
                <button type="button" class="bouton-principal petit" [disabled]="!motifAllocation.trim() || occupe()" (click)="annulerAllocation(a)" data-action="valider-annulation-allocation">Annuler l'affectation</button>
                <button type="button" class="bouton-lien" (click)="annulationAllocation.set(null)">Fermer</button>
              </div>
            }
          }
        </section>
      }

      <!-- ---------------------------------------------------------------------------------------------------------- imports -->
      @if (onglet() === 'IMPORTS') {
        <section data-bloc="imports">
          @if (lots().length === 0) {
            <p class="aide">Aucun relevé importé.</p>
          }
          @for (l of lots(); track l.id) {
            <p class="lot">
              {{ l.importeLe | date: 'dd/MM/yyyy HH:mm' }} — {{ l.fichier }} : {{ l.lignesLues }} lignes, {{ l.importees }} importée(s),
              {{ l.doublonsExacts }} déjà connue(s), {{ l.rejetees }} rejetée(s)
              @if (l.statut === 'NOTHING_NEW') { <span class="badge badge-attente">Rien de nouveau</span> }
            </p>
          }
        </section>
      }
    </div>
  `,
  styles: [
    `
      .page-banque { display: flex; flex-direction: column; gap: 1rem; }
      .entete { display: flex; justify-content: space-between; align-items: flex-end; flex-wrap: wrap; gap: 1rem; }
      .filtres-haut { display: flex; gap: 1rem; }
      .filtres-haut label, .ligne-champs label, .panneau-motif label { display: flex; flex-direction: column; gap: 0.2rem; font-size: 0.85rem; color: #5b6475; }
      .onglets { display: flex; gap: 0.5rem; align-items: center; border-bottom: 1px solid #d6dbe4; padding-bottom: 0.5rem; flex-wrap: wrap; }
      .onglets button[role=tab] { background: none; border: 0; padding: 0.4rem 0.8rem; cursor: pointer; font: inherit; border-bottom: 2px solid transparent; }
      .onglets button.actif { border-bottom-color: var(--color-primary, #14662f); font-weight: 600; }
      .onglets .petit { margin-left: auto; }
      .pastille { background: #b42318; color: #fff; border-radius: 999px; padding: 0 0.45rem; font-size: 0.75rem; }
      .tuiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 0.6rem; }
      .tuile { display: flex; flex-direction: column; align-items: flex-start; padding: 0.6rem 0.8rem; border: 1px solid #d6dbe4; border-radius: 8px; background: #fff; font: inherit; text-align: left; }
      button.tuile { cursor: pointer; }
      .tuile strong { font-size: 1.4rem; }
      .tuile span { font-size: 0.8rem; color: #5b6475; }
      .tuile.active { border-color: var(--color-primary, #14662f); box-shadow: 0 0 0 1px var(--color-primary, #14662f); }
      .tuile-alerte strong { color: #b42318; }
      .barre-liste { display: flex; gap: 0.6rem; align-items: center; flex-wrap: wrap; }
      .barre-liste input[type=search] { flex: 1; min-width: 14rem; padding: 0.4rem 0.6rem; }
      .transaction { border: 1px solid #e3e7ee; border-radius: 8px; padding: 0.6rem 0.8rem; background: #fff; }
      .ligne { display: flex; flex-wrap: wrap; gap: 0.7rem; align-items: center; }
      .date { color: #5b6475; min-width: 6rem; }
      .libelle { flex: 1; min-width: 12rem; }
      .montant { min-width: 7rem; text-align: right; }
      .montant.sortie { color: #b42318; }
      .proposition { display: flex; gap: 0.7rem; align-items: center; flex-wrap: wrap; margin-top: 0.4rem; font-size: 0.9rem; }
      .detail, .panneau-candidats, .panneau-motif, .panneau-import, .panneau-lot { margin-top: 0.6rem; padding: 0.7rem; background: #f6f8fb; border-radius: 8px; }
      .critere { margin: 0.15rem 0; }
      .ko { color: #b54708; }
      .candidat { display: flex; flex-wrap: wrap; gap: 0.6rem; align-items: center; padding: 0.4rem 0; border-bottom: 1px solid #e3e7ee; }
      .candidat.fort { background: #eef6ff; }
      .piece { font-weight: 600; min-width: 6rem; }
      .actions { display: flex; flex-wrap: wrap; gap: 0.6rem; align-items: center; margin-top: 0.5rem; }
      .recherche-candidats { display: flex; gap: 0.5rem; margin-bottom: 0.4rem; }
      .recherche-candidats input { flex: 1; padding: 0.35rem 0.5rem; }
      .ligne-champs { display: flex; flex-wrap: wrap; gap: 1rem; }
      .chiffres { display: flex; flex-wrap: wrap; gap: 1.2rem; list-style: none; padding: 0; }
      .chiffres .alerte { color: #b42318; }
      .apercu { margin-top: 1rem; padding-top: 0.8rem; border-top: 2px solid var(--color-primary, #14662f); }
      .rejet { color: #b42318; font-size: 0.85rem; margin: 0.1rem 0; }
      .ok { color: #067647; }
      .ligne-lot { display: flex; flex-wrap: wrap; gap: 0.7rem; align-items: center; padding: 0.3rem 0; }
      .montant-alloue { width: 7rem; padding: 0.25rem 0.4rem; }
      .allocation.annulee { opacity: 0.55; text-decoration: line-through; }
      .meta { color: #5b6475; font-size: 0.82rem; }
      .mesures { margin-top: 0.5rem; }
      .pagination { display: flex; gap: 1rem; align-items: center; justify-content: center; }
      .message-info { background: var(--color-primary-light, #ecf6ef); padding: 0.5rem 0.8rem; border-radius: 6px; }
      .message-erreur { color: #b42318; }
    `,
  ],
})
export class BanqueComponent implements OnInit {
  private readonly service = inject(BanqueService);
  private readonly comptabilite = inject(ComptabiliteService);
  private readonly route = inject(ActivatedRoute);

  readonly entrepriseId = toSignal(this.route.paramMap.pipe(map((p) => p.get('entrepriseId')!)), { initialValue: '' });

  readonly libellesStatut = LIBELLES_STATUT_TRANSACTION;
  readonly libellesPaiement = LIBELLES_STATUT_PAIEMENT;

  readonly onglet = signal<Onglet>('TRANSACTIONS');
  readonly comptes = signal<CompteComptableDto[]>([]);
  readonly synthese = signal<SyntheseBanqueDto | null>(null);
  readonly metriques = signal<MetriquesBanqueDto | null>(null);
  readonly page = signal<PageTransactions | null>(null);
  readonly chargement = signal(false);
  readonly occupe = signal(false);
  readonly info = signal<string | null>(null);
  readonly erreur = signal<string | null>(null);

  compteId = '';
  periode = '';
  statut: StatutTransaction | '' = '';
  recherche = '';
  tri = 'date-desc';
  pageCourante = 0;

  // détail, candidats, annulation
  readonly detailOuvert = signal<string | null>(null);
  readonly candidatsPour = signal<string | null>(null);
  readonly candidats = signal<CandidatDto[]>([]);
  rechercheCandidat = '';
  readonly annulationEn = signal<string | null>(null);
  motifAnnulation = '';

  // import
  readonly importOuvert = signal(false);
  readonly fichier = signal<File | null>(null);
  readonly apercuImport_ = signal<ApercuImportDto | null>(null);
  importCompteId = '';
  soldeOuverture = '';
  soldeCloture = '';

  // lot
  readonly lotOuvert = signal(false);
  readonly lot = signal<PropositionLotDto[]>([]);
  readonly selectionLot = signal<Set<string>>(new Set());
  confianceMin = 85;

  // règlements
  readonly reglements = signal<ReglementARepartirDto[]>([]);
  readonly repartitionPour = signal<string | null>(null);
  readonly factures = signal<FactureCandidateDto[]>([]);
  readonly allocations = signal<AllocationDto[]>([]);
  readonly montantsSaisis = signal<Record<string, number>>({});
  readonly annulationAllocation = signal<string | null>(null);
  rechercheFacture = '';
  motifAllocation = '';
  readonly resteARepartir = computed(() => {
    const id = this.repartitionPour();
    return this.reglements().find((r) => r.paymentLineId === id)?.nonAffecte ?? 0;
  });

  readonly lots = signal<LotImportDto[]>([]);

  ngOnInit(): void {
    // Comptes de trésorerie utilisables (les comptes de regroupement à 2 chiffres, « 52 », « 57 », ne reçoivent aucun relevé).
    this.comptabilite
      .listerComptes(this.entrepriseId())
      .subscribe((comptes) => this.comptes.set(comptes.filter((c) => c.numero.startsWith('5') && c.numero.length > 2)));
    this.recharger();
    this.chargerReglements();
  }

  // ------------------------------------------------------------------------------------------------------ chargement

  recharger(): void {
    this.pageCourante = 0;
    this.charger();
  }

  private charger(): void {
    const id = this.entrepriseId();
    this.chargement.set(true);
    this.service.synthese(id, this.compteId || undefined, this.periode || undefined).subscribe({
      next: (s) => this.synthese.set(s),
      error: () => this.erreur.set('Synthèse indisponible.'),
    });
    this.service.metriques(id).subscribe({ next: (m) => this.metriques.set(m), error: () => this.metriques.set(null) });
    this.service
      .transactions(id, { compteId: this.compteId, periode: this.periode, statut: this.statut, q: this.recherche, tri: this.tri.split('-')[0], ordre: this.tri.split('-')[1], page: this.pageCourante, taille: 25 })
      .pipe(finalize(() => this.chargement.set(false)))
      .subscribe({ next: (p) => this.page.set(p), error: () => this.erreur.set('Impossible de charger les transactions.') });
  }

  private chargerReglements(): void {
    this.service.reglementsARepartir(this.entrepriseId()).subscribe({ next: (r) => this.reglements.set(r), error: () => this.reglements.set([]) });
    this.service.allocations(this.entrepriseId()).subscribe({ next: (a) => this.allocations.set(a), error: () => this.allocations.set([]) });
  }

  ouvrirOnglet(onglet: Onglet): void {
    this.onglet.set(onglet);
    this.erreur.set(null);
    if (onglet === 'REGLEMENTS') {
      this.chargerReglements();
    } else if (onglet === 'IMPORTS') {
      this.service.lots(this.entrepriseId()).subscribe((l) => this.lots.set(l));
    }
  }

  filtrer(statut: StatutTransaction | ''): void {
    this.statut = statut;
    this.recharger();
  }

  changerPage(sens: number): void {
    this.pageCourante = Math.max(0, this.pageCourante + sens);
    this.charger();
  }

  classeStatut(s: StatutTransaction): string {
    switch (s) {
      case 'RAPPROCHEE':
        return 'badge-recu';
      case 'SANS_CORRESPONDANCE':
        return 'badge-erreur';
      case 'VIREMENT_INTERNE':
        return 'badge-recu';
      default:
        return 'badge-attente';
    }
  }

  // ------------------------------------------------------------------------------------------------------ décisions

  basculerDetail(t: TransactionDto): void {
    this.detailOuvert.set(this.detailOuvert() === t.id ? null : t.id);
  }

  confirmer(t: TransactionDto): void {
    const id = t.proposition?.reconciliationId;
    if (id) {
      this.agir(this.service.confirmer(this.entrepriseId(), id), 'Rapprochement confirmé.');
    }
  }

  rejeter(t: TransactionDto): void {
    const id = t.proposition?.reconciliationId;
    if (id) {
      this.agir(this.service.rejeter(this.entrepriseId(), id), 'Proposition rejetée : elle ne reviendra pas.');
    }
  }

  ouvrirCandidats(t: TransactionDto): void {
    this.candidatsPour.set(t.id);
    this.rechercheCandidat = '';
    this.chargerCandidats(t);
  }

  chargerCandidats(t: TransactionDto): void {
    this.service.candidats(this.entrepriseId(), t.id, this.rechercheCandidat || undefined).subscribe({
      next: (c) => this.candidats.set(c),
      error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de charger les mouvements.'),
    });
  }

  choisir(t: TransactionDto, ligneEcritureId: string): void {
    this.candidatsPour.set(null);
    this.agir(this.service.rapprocher(this.entrepriseId(), t.id, ligneEcritureId), 'Transaction rapprochée.');
  }

  confirmerVirement(t: TransactionDto): void {
    const id = t.proposition?.transfertId;
    if (id) {
      this.agir(this.service.confirmerVirement(this.entrepriseId(), id), 'Virement interne confirmé : ni charge, ni produit.');
    }
  }

  rejeterVirement(t: TransactionDto): void {
    const id = t.proposition?.transfertId;
    if (id) {
      this.agir(this.service.rejeterVirement(this.entrepriseId(), id), 'Rejeté : les deux transactions sont rapprochées normalement.');
    }
  }

  demanderAnnulation(t: TransactionDto): void {
    this.motifAnnulation = '';
    this.annulationEn.set(t.id);
  }

  annuler(t: TransactionDto): void {
    this.annulationEn.set(null);
    const virement = t.statut === 'VIREMENT_INTERNE' ? t.proposition?.transfertId : null;
    if (virement) {
      this.agir(this.service.annulerVirement(this.entrepriseId(), virement, this.motifAnnulation.trim()), 'Virement interne annulé : les transactions redeviennent à traiter.');
      return;
    }
    this.agir(this.service.annulerRapprochement(this.entrepriseId(), t.id, this.motifAnnulation.trim()), 'Rapprochement annulé : la transaction redevient à traiter.');
  }

  doublonDistinct(t: TransactionDto): void {
    this.agir(this.service.doublonDistinct(this.entrepriseId(), t.id), 'Marquées comme deux opérations distinctes.');
  }

  private agir(appel: ReturnType<BanqueService['confirmer']>, message: string): void {
    this.erreur.set(null);
    this.occupe.set(true);
    appel.pipe(finalize(() => this.occupe.set(false))).subscribe({
      next: () => {
        this.info.set(message);
        this.charger();
        this.chargerReglements();
      },
      error: (err) => this.erreur.set(err.error?.message ?? 'Action impossible.'),
    });
  }

  // ------------------------------------------------------------------------------------------------------ lot

  ouvrirLot(): void {
    this.lotOuvert.set(true);
    this.service.apercuLot(this.entrepriseId(), this.confianceMin).subscribe({
      next: (l) => {
        this.lot.set(l);
        this.selectionLot.set(new Set(l.map((p) => p.reconciliationId)));
      },
      error: () => this.erreur.set("Impossible de charger l'aperçu."),
    });
  }

  basculerLot(id: string): void {
    this.selectionLot.update((s) => {
      const copie = new Set(s);
      if (!copie.delete(id)) {
        copie.add(id);
      }
      return copie;
    });
  }

  confirmerLot(): void {
    this.occupe.set(true);
    this.service
      .confirmerLot(this.entrepriseId(), [...this.selectionLot()], this.confianceMin)
      .pipe(finalize(() => this.occupe.set(false)))
      .subscribe({
        next: (r) => {
          this.info.set(`${r.confirmees} correspondance(s) validée(s)` + (r.ignorees > 0 ? `, ${r.ignorees} ignorée(s).` : '.'));
          this.lotOuvert.set(false);
          this.charger();
        },
        error: (err) => this.erreur.set(err.error?.message ?? 'Validation impossible.'),
      });
  }

  // ------------------------------------------------------------------------------------------------------ import

  choisirFichier(event: Event): void {
    this.fichier.set((event.target as HTMLInputElement).files?.[0] ?? null);
    this.apercuImport_.set(null);
  }

  fermerImport(): void {
    this.importOuvert.set(false);
    this.apercuImport_.set(null);
    this.fichier.set(null);
  }

  apercuImport(): void {
    const f = this.fichier();
    if (!f || !this.importCompteId) {
      return;
    }
    this.occupe.set(true);
    this.erreur.set(null);
    this.service
      .apercuImport(this.entrepriseId(), this.importCompteId, f, this.soldeOuverture || undefined, this.soldeCloture || undefined)
      .pipe(finalize(() => this.occupe.set(false)))
      .subscribe({
        next: (a) => this.apercuImport_.set(a),
        error: (err) => this.erreur.set(err.error?.message ?? "Impossible de calculer l'aperçu."),
      });
  }

  confirmerImport(): void {
    const f = this.fichier();
    if (!f || !this.importCompteId) {
      return;
    }
    this.occupe.set(true);
    this.service
      .importer(this.entrepriseId(), this.importCompteId, f, this.soldeOuverture || undefined, this.soldeCloture || undefined)
      .pipe(finalize(() => this.occupe.set(false)))
      .subscribe({
        next: (r) => {
          this.info.set(`${r.lot.importedRows} transaction(s) importée(s)` + (r.lot.exactDuplicates ? `, ${r.lot.exactDuplicates} déjà connue(s) ignorée(s).` : '.'));
          this.fermerImport();
          this.statut = '';
          this.recharger();
        },
        error: (err) => this.erreur.set(err.error?.message ?? "Impossible d'importer ce relevé."),
      });
  }

  // ------------------------------------------------------------------------------------------------------ règlements

  ouvrirRepartition(r: ReglementARepartirDto): void {
    this.repartitionPour.set(r.paymentLineId);
    this.rechercheFacture = '';
    this.montantsSaisis.set({});
    this.chargerFactures(r);
  }

  chargerFactures(r: ReglementARepartirDto): void {
    this.service.facturesCandidates(this.entrepriseId(), r.paymentLineId, this.rechercheFacture || undefined).subscribe({
      next: (f) => this.factures.set(f),
      error: (err) => this.erreur.set(err.error?.message ?? 'Impossible de charger les factures.'),
    });
  }

  /** Montant proposé : le plus petit entre le reste de la facture et ce qui reste à affecter sur le règlement ; modifiable. */
  montantPropose(f: FactureCandidateDto): number {
    return this.montantsSaisis()[f.invoiceLineId] ?? Math.min(f.reste, this.resteARepartir());
  }

  saisirMontant(f: FactureCandidateDto, valeur: number): void {
    this.montantsSaisis.update((m) => ({ ...m, [f.invoiceLineId]: valeur }));
  }

  allouer(r: ReglementARepartirDto, f: FactureCandidateDto): void {
    const montant = Number(this.montantPropose(f));
    if (!(montant > 0)) {
      this.erreur.set('Saisissez un montant supérieur à zéro.');
      return;
    }
    this.occupe.set(true);
    this.erreur.set(null);
    this.service
      .allouer(this.entrepriseId(), r.paymentLineId, f.invoiceLineId, montant)
      .pipe(finalize(() => this.occupe.set(false)))
      .subscribe({
        next: () => {
          this.info.set('Règlement affecté à la facture ' + f.piece + '.');
          this.montantsSaisis.set({});
          this.chargerReglements();
          this.chargerFactures(r);
        },
        error: (err) => this.erreur.set(err.error?.message ?? "Impossible d'affecter ce règlement."),
      });
  }

  annulerAllocation(a: AllocationDto): void {
    this.annulationAllocation.set(null);
    this.agir(this.service.annulerAllocation(this.entrepriseId(), a.id, this.motifAllocation.trim()), 'Affectation annulée.');
  }
}
