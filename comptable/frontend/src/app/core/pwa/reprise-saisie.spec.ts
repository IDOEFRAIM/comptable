import { ElementFile } from './file-attente.store';
import { conseilPourRefus, reprendreSaisie } from './reprise-saisie';

const base: ElementFile = { id: 'x1', utilisateur: 'a@b.test', entrepriseId: 'e1', type: 'OPERATION', libelle: 'L', creeLe: 1, statut: 'REJETE', tentatives: 1 };

describe('reprendreSaisie : remettre une saisie refusée dans son formulaire', () => {
  it('un achat revient avec tous ses champs, prêt à être corrigé', () => {
    const reprise = reprendreSaisie({
      ...base,
      chemin: 'operations/achat/proposition',
      requete: { natureAchat: 'MARCHANDISE', fournisseurId: 'f1', date: '2026-11-16', reference: 'F-9', libelle: 'Sacs de riz', montantHt: 45000, compteChargeId: 'c601', montantTtcAttendu: 53100 },
    })!;
    expect(reprise.formulaire).toBe('operation');
    expect(reprise.route).toEqual(['/entreprises', 'e1', 'operations']);
    expect(reprise.valeurs).toEqual(
      jasmine.objectContaining({ categorie: 'ACHAT', sens: 'CLIENT', date: '2026-11-16', libelle: 'Sacs de riz', reference: 'F-9', montant: 45000, compteId: 'c601', montantTtcAttendu: 53100, natureAchat: 'MARCHANDISE', tiersId: 'f1' }),
    );
  });

  it('une vente, un règlement fournisseur et un avoir client gardent leur tiers et leur sens', () => {
    const vente = reprendreSaisie({ ...base, chemin: 'operations/vente/proposition', requete: { clientId: 'c1', date: 'd', libelle: 'V', montantHt: 10, compteProduitId: 'c701', natureVente: 'VENTE_MARCHANDISE' } })!.valeurs as Record<string, unknown>;
    expect(vente).toEqual(jasmine.objectContaining({ categorie: 'VENTE', tiersId: 'c1', compteId: 'c701', montant: 10 }));
    const reglement = reprendreSaisie({ ...base, chemin: 'operations/reglement/proposition', requete: { type: 'REGLEMENT_FOURNISSEUR', tiersId: 't1', date: 'd', libelle: 'R', montant: 5, moyenPaiement: 'ESPECES' } })!.valeurs as Record<string, unknown>;
    expect(reglement).toEqual(jasmine.objectContaining({ categorie: 'REGLEMENT', sens: 'FOURNISSEUR', tiersId: 't1', montant: 5, moyenPaiement: 'ESPECES' }));
    const avoir = reprendreSaisie({ ...base, chemin: 'operations/avoir/proposition', requete: { type: 'AVOIR_VENTE', tiersId: 't2', date: 'd', libelle: 'A', montantHt: 7, compteId: 'c709' } })!.valeurs as Record<string, unknown>;
    expect(avoir).toEqual(jasmine.objectContaining({ categorie: 'AVOIR', sens: 'CLIENT', compteId: 'c709', montant: 7 }));
  });

  it('une écriture revient avec ses lignes, vers la page de saisie d’écriture', () => {
    const reprise = reprendreSaisie({
      ...base,
      type: 'ECRITURE',
      requete: { journalId: 'j1', dateEcriture: '2026-11-16', libelle: 'Loyer', lignes: [{ compteId: 'a', debit: 1000, credit: 0 }, { compteId: 'b', debit: 0, credit: 1000, tiersId: 't1' }] },
    })!;
    expect(reprise.formulaire).toBe('ecriture');
    expect(reprise.route).toEqual(['/entreprises', 'e1', 'ecritures', 'nouvelle']);
    const v = reprise.valeurs as { numeroPiece: string; lignes: { tiersId: string; debit: number }[] };
    expect(v.numeroPiece).toBe('');
    expect(v.lignes.length).toBe(2);
    expect(v.lignes[1].tiersId).toBe('t1');
    expect(v.lignes[0].debit).toBe(1000);
  });

  it('une pièce ou un tiers ne se reprennent pas dans un formulaire (renvoi ou abandon seulement)', () => {
    expect(reprendreSaisie({ ...base, type: 'DOCUMENT', fichier: new Blob(['x']) })).toBeNull();
    expect(reprendreSaisie({ ...base, type: 'TIERS', requete: { type: 'CLIENT', nom: 'X' } })).toBeNull();
  });
});

describe('conseilPourRefus : une phrase claire pour chaque refus', () => {
  it('période clôturée, accès retiré, élément disparu, conflit', () => {
    expect(conseilPourRefus({ ...base, erreur: 'L’exercice 2025 est clôturé.' })).toContain('clôturée');
    expect(conseilPourRefus({ ...base, statutHttp: 403 })).toContain('accès');
    expect(conseilPourRefus({ ...base, statutHttp: 404 })).toContain('n’existe plus');
    expect(conseilPourRefus({ ...base, statutHttp: 409 })).toContain('a changé');
  });

  it('à défaut, le conseil dépend du type de saisie', () => {
    expect(conseilPourRefus({ ...base, statutHttp: 422 })).toContain('Reprenez-la');
    expect(conseilPourRefus({ ...base, type: 'TIERS', statutHttp: 422 })).toContain('comptes clients et fournisseurs');
    expect(conseilPourRefus({ ...base, type: 'DOCUMENT', statutHttp: 422 })).toContain('redéposez');
  });
});
