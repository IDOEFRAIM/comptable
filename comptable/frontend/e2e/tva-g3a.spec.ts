import { Page, expect, request, test } from '@playwright/test';
import { amorcerTva, jetonDe } from './support-tva';

/**
 * G3A de bout en bout (interface réelle, backend PostgreSQL réel) : recherche et filtres serveur, validation en masse, cadre non vérifié (finalisation bloquée), puis règlement interne d'une déclaration
 * finalisée. Les cadres fiscaux sont des FIXTURES TEST_ONLY (taux inventés, propres au cabinet jetable) : aucune règle burkinabè n'est utilisée. Scénario B n'a de sens qu'avec une fixture VERIFIED :
 * il ne prouve rien sur la réglementation, seulement le suivi technique du règlement.
 */

async function connecter(page: Page, email: string, motDePasse: string): Promise<void> {
  await page.goto('/login');
  await page.getByRole('textbox', { name: /email/i }).fill(email);
  await page.locator('input[type="password"]').fill(motDePasse);
  await page.getByRole('button', { name: 'Se connecter' }).click();
  await expect(page).toHaveURL(/\/cabinet$/);
}

test('A — collaborateur : recherche, filtre à revoir, classification, validation en masse, prête pour revue ; cadre non vérifié : finalisation bloquée', async ({ page, browser }) => {
  const api = await request.newContext();
  const d = await amorcerTva(api, { cadreVerifie: false });
  for (let i = 1; i <= 6; i++) {
    await d.ecrireTva(`F${i}`, 'VENTE', i * 100, i * 10);
  }
  await d.ecrireTva('A1', 'ACHAT', 400, 40);
  const mixte = await d.ecrireTva('M1', 'MIXTE', 1000, 0);
  const jetonCollab = await jetonDe(api, d.collaborateur.email, d.collaborateur.motDePasse);
  await d.ouvrirPeriode(jetonCollab);

  await connecter(page, d.collaborateur.email, d.collaborateur.motDePasse);
  await page.goto(`/entreprises/${d.entrepriseId}/fiscalite/tva`);

  // cadre réglementaire non vérifié : explication sobre, sans alerte d'erreur ; le statut opérationnel vient du backend
  await expect(page.locator('[data-champ="cadre-reglementaire"]')).toContainText('non vérifié');
  await expect(page.locator('[data-champ="explication-cadre"]')).toContainText('peut être préparée et contrôlée');
  await expect(page.locator('[data-zone="erreur-tva"]')).toHaveCount(0);
  await expect(page.locator('[data-champ="statut-operationnel"]')).toContainText('À préparer');

  // recherche côté serveur : « F3 » ne laisse que la vente F3 (plus l'opération non déterminable n'y correspond pas)
  await page.locator('[data-onglet="VENTES"]').click();
  await expect(page.locator('[data-zone="ventes"] [data-ligne]')).toHaveCount(7);                  // 6 ventes + l'opération non déterminable (affichée des deux côtés)
  await page.locator('[data-champ="recherche"]').fill('F3');
  await expect(page.locator('[data-zone="ventes"] [data-ligne]')).toHaveCount(1);
  await expect(page.locator('[data-champ="pagination-info"]')).toContainText('sur 1');
  await page.locator('[data-champ="recherche"]').fill('');
  await expect(page.locator('[data-zone="ventes"] [data-ligne]')).toHaveCount(7);

  // filtre « à revoir » : seule l'opération ambiguë
  await page.locator('[data-champ="a-revoir-seulement"]').check();
  await expect(page.locator('[data-zone="ventes"] [data-ligne]')).toHaveCount(1);
  await page.locator(`[data-zone="ventes"] [data-ligne="${mixte}"] [data-action="traiter"]`).click();
  await page.locator('[data-champ="traitement-select"]').selectOption('OUT_OF_SCOPE');
  await page.locator('[data-champ="traitement-motif"]').fill('Opération hors champ selon le dossier du client');
  await page.locator('[data-action="enregistrer-traitement"]').click();
  await expect(page.locator('[data-zone="ventes"] [data-ligne]')).toHaveCount(0);
  await page.locator('[data-champ="a-revoir-seulement"]').uncheck();

  // validation en masse des ventes : le serveur revalide, la décision est tracée
  await expect(page.locator('[data-zone="ventes"] [data-ligne]')).toHaveCount(7);
  const cases = page.locator('[data-zone="ventes"] [data-champ="selection-ligne"]');
  for (let i = 0; i < 6; i++) {
    await page.locator(`[data-zone="ventes"] [data-ligne]`).filter({ hasText: `F${i + 1}` }).locator('[data-champ="selection-ligne"]').check();
  }
  await expect(cases.first()).toBeVisible();
  await expect(page.locator('[data-champ="nb-selection"]')).toContainText('6 opération(s) sélectionnée(s)');
  await page.locator('[data-champ="motif-groupe"]').fill('Ventes taxables confirmées par la lecture des factures');
  await page.locator('[data-action="valider-groupe"]').click();
  await expect(page.locator('[data-zone="validation-groupe"]')).toHaveCount(0);
  await expect(page.locator('[data-zone="ventes"] [data-ligne]').filter({ hasText: 'F2' })).toContainText('décidé');

  // prête pour la revue (techniquement), mais le cadre n'est pas vérifié
  await page.locator('[data-onglet="VUE"]').click();
  await page.locator('[data-action="preparer"]').click();
  await expect(page.locator('[data-zone="statut-periode"]')).toContainText('Prête pour revue');
  await expect(page.locator('[data-champ="statut-operationnel"]')).toContainText('Prête pour revue');
  await expect(page.locator('[data-raison="REGULATORY_PACK_NOT_VERIFIED"]')).toContainText('Aucun cadre réglementaire vérifié');

  // l'expert révise, mais ne peut pas finaliser : le bouton est présent et inactif, la raison est expliquée
  const contexteExpert = await browser.newContext();
  const pageExpert = await contexteExpert.newPage();
  await connecter(pageExpert, d.email, d.motDePasse);
  await pageExpert.goto(`/entreprises/${d.entrepriseId}/fiscalite/tva`);
  await pageExpert.locator('[data-action="revoir"]').click();
  await expect(pageExpert.locator('[data-zone="statut-periode"]')).toContainText('Revue');
  await expect(pageExpert.locator('[data-champ="statut-operationnel"]')).toContainText('finalisation bloquée');
  await expect(pageExpert.locator('[data-action="finaliser"]')).toBeDisabled();
  await expect(pageExpert.locator('[data-raison="REGULATORY_PACK_NOT_VERIFIED"]')).toBeVisible();
  await contexteExpert.close();
});

test('B — expert : déclaration finalisée (fixture TEST_ONLY vérifiée), paiement partiel puis complet, statut Payée', async ({ page }) => {
  const api = await request.newContext();
  const d = await amorcerTva(api);
  await d.ecrireTva('F1', 'VENTE', 1000, 100);
  await d.ecrireTva('A1', 'ACHAT', 400, 40);
  const jetonCollab = await jetonDe(api, d.collaborateur.email, d.collaborateur.motDePasse);
  const periodeId = await d.ouvrirPeriode(jetonCollab);
  await d.preparer(jetonCollab, periodeId);

  await connecter(page, d.email, d.motDePasse);
  await page.goto(`/entreprises/${d.entrepriseId}/fiscalite/tva`);
  await page.locator('[data-action="revoir"]').click();
  await page.locator('[data-action="finaliser"]').click();
  await page.locator('[data-action="confirmer-finalisation"]').click();
  await expect(page.locator('[data-zone="statut-periode"]')).toContainText('Finalisée');

  // règlement : solde déclaré 60 (100 − 40), rien de payé, statut « À payer » ; aucun bouton de paiement en ligne
  await expect(page.locator('[data-champ="solde-declare"]')).toHaveText('60');
  await expect(page.locator('[data-champ="reste-a-payer"]')).toHaveText('60');
  await expect(page.locator('[data-champ="statut-operationnel"]')).toContainText('À payer');
  await expect(page.getByRole('button', { name: /payer en ligne/i })).toHaveCount(0);

  // le dossier apparaît dans la file du cabinet avec le reste à payer
  await page.getByRole('link', { name: 'Cabinet', exact: true }).click();
  await expect(page.locator('[data-zone="a-traiter"] tr[data-item]').filter({ hasText: 'TVA à payer' })).toHaveCount(1);
  await page.goto(`/entreprises/${d.entrepriseId}/fiscalite/tva`);

  // paiement partiel de 20
  await page.locator('[data-champ="paiement-montant"]').fill('20');
  await page.locator('[data-champ="paiement-reference"]').fill('VIR-1');
  await page.locator('[data-action="enregistrer-paiement"]').click();
  await expect(page.locator('[data-champ="paye"]')).toHaveText('20');
  await expect(page.locator('[data-champ="reste-a-payer"]')).toHaveText('40');
  await expect(page.locator('[data-champ="statut-operationnel"]')).toContainText('Partiellement payée');

  // un dépassement est refusé par le serveur
  await page.locator('[data-champ="paiement-montant"]').fill('50');
  await page.locator('[data-action="enregistrer-paiement"]').click();
  await expect(page.locator('[data-zone="erreur-tva"]')).toContainText('dépasse le reste');
  await expect(page.locator('[data-champ="paye"]')).toHaveText('20');

  // paiement complet
  await page.locator('[data-champ="paiement-montant"]').fill('40');
  await page.locator('[data-champ="paiement-reference"]').fill('VIR-2');
  await page.locator('[data-action="enregistrer-paiement"]').click();
  await expect(page.locator('[data-champ="reste-a-payer"]')).toHaveText('0');
  await expect(page.locator('[data-champ="statut-operationnel"]')).toContainText('Payée');
  await expect(page.locator('[data-zone="form-paiement"]')).toHaveCount(0);
  await expect(page.locator('[data-zone="historique-paiements"] [data-paiement]')).toHaveCount(2);

  // annulation tracée du second paiement : le reste à payer est rétabli, l'historique conserve la ligne
  await page.locator('[data-zone="historique-paiements"] [data-paiement]').nth(1).locator('[data-action="annuler-paiement"]').click();
  await page.locator('[data-champ="motif-annulation-paiement"]').fill('Virement rejeté par la banque');
  await page.locator('[data-action="confirmer-annulation-paiement"]').click();
  await expect(page.locator('[data-champ="reste-a-payer"]')).toHaveText('40');
  await expect(page.locator('[data-zone="historique-paiements"]')).toContainText('Annulé : Virement rejeté par la banque');
  await expect(page.locator('[data-champ="statut-operationnel"]')).toContainText('Partiellement payée');
});

test('C — conflit 409 : message clair, rechargement explicite, puis mutation réussie (jamais d’écrasement ni de rejeu silencieux)', async ({ page }) => {
  const api = await request.newContext();
  const d = await amorcerTva(api);
  await d.ecrireTva('F1', 'VENTE', 1000, 100);
  const jetonCollab = await jetonDe(api, d.collaborateur.email, d.collaborateur.motDePasse);
  const periodeId = await d.ouvrirPeriode(jetonCollab);

  await connecter(page, d.collaborateur.email, d.collaborateur.motDePasse);
  await page.goto(`/entreprises/${d.entrepriseId}/fiscalite/tva`);
  await expect(page.locator('[data-action="preparer"]')).toBeVisible();

  // un autre utilisateur modifie la période pendant que l'écran est ouvert (la version a changé)
  await d.ajusterViaApi(jetonCollab, periodeId);

  await page.locator('[data-action="preparer"]').click();
  await expect(page.locator('[data-zone="erreur-tva"]')).toContainText('modifiée par un autre utilisateur');
  await expect(page.locator('[data-zone="erreur-tva"]')).toContainText('Rechargez les dernières données');
  await expect(page.locator('[data-zone="statut-periode"]')).not.toContainText('Prête pour revue');      // la mutation n'a pas été rejouée toute seule

  await page.locator('[data-action="recharger"]').click();
  await expect(page.locator('[data-zone="erreur-tva"]')).toHaveCount(0);
  await page.locator('[data-action="preparer"]').click();
  await expect(page.locator('[data-zone="statut-periode"]')).toContainText('Prête pour revue');
});
