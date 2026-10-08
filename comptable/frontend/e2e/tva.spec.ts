import { Page, expect, request, test } from '@playwright/test';
import { amorcerTva, jetonDe } from './support-tva';

/**
 * Espace TVA de bout en bout (interface réelle, backend PostgreSQL réel). Les comptes, le cadre fiscal FICTIF (taux inventés, propre au cabinet jetable) et les écritures sont créés par l'API ; tout le reste
 * est fait par l'interface. Aucun taux, aucune échéance ni aucun formulaire réglementaire n'est utilisé.
 */

async function connecter(page: Page, email: string, motDePasse: string): Promise<void> {
  await page.goto('/login');
  await page.getByRole('textbox', { name: /email/i }).fill(email);
  await page.locator('input[type="password"]').fill(motDePasse);
  await page.getByRole('button', { name: 'Se connecter' }).click();
  await expect(page).toHaveURL(/\/cabinet$/);
}

const iso = (d: Date): string => d.toISOString().slice(0, 10);

test('collaborateur : ouvre la période TVA, traite l’opération à revoir et prépare la déclaration pour la revue', async ({ page }) => {
  const api = await request.newContext();
  const d = await amorcerTva(api);
  await d.ecrireTva('F1', 'VENTE', 1000, 100);
  await d.ecrireTva('A1', 'ACHAT', 400, 40);
  const mixte = await d.ecrireTva('M1', 'MIXTE', 1000, 0);                    // opération sur les deux comptes de TVA : traitement non déterminable

  await connecter(page, d.collaborateur.email, d.collaborateur.motDePasse);
  await page.goto(`/entreprises/${d.entrepriseId}/fiscalite/tva`);
  await expect(page.getByRole('heading', { name: 'TVA' })).toBeVisible();
  await expect(page.locator('[data-champ="aucune-periode"]')).toBeVisible();

  // ouverture de la période du mois courant par le formulaire
  const maintenant = new Date();
  await page.locator('[data-action="nouvelle-periode"]').click();
  await page.locator('[data-champ="debut"]').fill(iso(new Date(maintenant.getFullYear(), maintenant.getMonth(), 1, 12)));
  await page.locator('[data-champ="fin"]').fill(iso(new Date(maintenant.getFullYear(), maintenant.getMonth() + 1, 0, 12)));
  await page.locator('[data-action="ouvrir-periode"]').click();
  await expect(page.locator('[data-zone="statut-periode"]')).toContainText(/Ouverte|En préparation/);

  // vue d'ensemble : montants calculés par le backend, une opération à revoir, préparation refusée
  await expect(page.locator('[data-champ="a-revoir"]')).toHaveText('1');
  await expect(page.locator('[data-zone="bloqueurs"]')).toContainText('opération(s) à revoir');
  await page.locator('[data-action="preparer"]').click();
  await expect(page.locator('[data-zone="erreur-tva"]')).toContainText('pas prête pour la revue');

  // ventes et achats
  await page.locator('[data-onglet="VENTES"]').click();
  await expect(page.locator('[data-zone="ventes"] [data-ligne]').filter({ hasText: 'F1' })).toBeVisible();
  await page.locator('[data-onglet="ACHATS"]').click();
  await expect(page.locator('[data-zone="achats"] [data-ligne]').filter({ hasText: 'A1' })).toBeVisible();

  // l'opération à revoir : origine puis décision justifiée
  await page.locator('[data-onglet="VENTES"]').click();
  await page.locator('[data-champ="a-revoir-seulement"]').check();
  const aRevoir = page.locator(`[data-zone="ventes"] [data-ligne="${mixte}"]`);
  await expect(aRevoir).toBeVisible();
  await aRevoir.locator('[data-action="traiter"]').click();
  await expect(page.locator('[data-zone="origine"]')).toContainText('443900');
  await expect(page.locator('[data-action="enregistrer-traitement"]')).toBeDisabled();
  await page.locator('[data-champ="traitement-select"]').selectOption('OUT_OF_SCOPE');
  await page.locator('[data-champ="traitement-motif"]').fill('Opération hors champ selon le dossier du client');
  await page.locator('[data-action="enregistrer-traitement"]').click();

  // le contrôle devient vert, la période est préparable
  await page.locator('[data-onglet="CONTROLES"]').click();
  await expect(page.locator('[data-champ="aucune-anomalie"]')).toBeVisible();
  await page.locator('[data-onglet="VUE"]').click();
  await expect(page.locator('[data-champ="a-revoir"]')).toHaveText('0');
  await page.locator('[data-action="preparer"]').click();
  await expect(page.locator('[data-zone="statut-periode"]')).toContainText('Prête pour revue');
  await expect(page.locator('[data-action="revoir"]')).toHaveCount(0);        // un collaborateur ne revoit pas
});

test('expert : de la file du cabinet à la déclaration finalisée et son snapshot', async ({ page }) => {
  const api = await request.newContext();
  const d = await amorcerTva(api);
  await d.ecrireTva('F1', 'VENTE', 1000, 100);
  await d.ecrireTva('A1', 'ACHAT', 400, 40);
  const jetonCollab = await jetonDe(api, d.collaborateur.email, d.collaborateur.motDePasse);
  const periodeId = await d.ouvrirPeriode(jetonCollab);
  await d.preparer(jetonCollab, periodeId);

  await connecter(page, d.email, d.motDePasse);
  const ligne = page.locator('[data-zone="a-traiter"] tr[data-item]').filter({ hasText: 'Déclaration TVA prête pour revue' });
  await expect(ligne).toHaveCount(1);
  await expect(ligne).toContainText("À revoir par l'expert");
  await ligne.getByRole('link', { name: 'Ouvrir' }).click();
  await expect(page.getByRole('heading', { name: 'TVA' })).toBeVisible();
  await expect(page.locator('[data-zone="statut-periode"]')).toContainText('Prête pour revue');

  await page.locator('[data-onglet="DECLARATION"]').click();
  await expect(page.locator('[data-ligne-declaration="TVA_NETTE"]')).toContainText('60');          // 100 collectée − 40 déductible
  await expect(page.locator('[data-zone="declaration"]')).toContainText('non officielle');

  await page.locator('[data-action="revoir"]').click();
  await expect(page.locator('[data-zone="statut-periode"]')).toContainText('Revue');
  await page.locator('[data-action="finaliser"]').click();
  await expect(page.locator('[data-zone="confirmation-finalisation"]')).toContainText('immuable');
  await page.locator('[data-action="confirmer-finalisation"]').click();
  await expect(page.locator('[data-zone="statut-periode"]')).toContainText('Finalisée');
  await page.locator('[data-onglet="DECLARATION"]').click();
  await expect(page.locator('[data-zone="declaration-finale"]')).toContainText('snapshot immuable');
  await expect(page.locator('[data-action="recalculer"]')).toBeDisabled();

  // le dossier quitte la file à revoir
  await page.getByRole('link', { name: 'Cabinet', exact: true }).click();
  await expect(page.locator('[data-zone="a-traiter"] tr[data-item]').filter({ hasText: 'Déclaration TVA prête pour revue' })).toHaveCount(0);
});

test('anomalie : TVA incohérente → constat dans la file du cabinet → ajustement justifié → auto-résolution', async ({ page }) => {
  const api = await request.newContext();
  const d = await amorcerTva(api);
  await d.ecrireTva('F1', 'VENTE', 1000, 103);                                  // TVA comptabilisée 103 ≠ 10 % de 1000 calculés avec le cadre fictif
  await d.ouvrirPeriode(await jetonDe(api, d.collaborateur.email, d.collaborateur.motDePasse));

  await connecter(page, d.collaborateur.email, d.collaborateur.motDePasse);
  const anomalie = page.locator('[data-zone="a-traiter"] tr[data-item]').filter({ hasText: 'TVA comptabilisée 103' });
  await expect(anomalie).toHaveCount(1);
  await expect(anomalie).toContainText('Fiscalité');
  await expect(anomalie).toContainText('À corriger avant la déclaration');

  await anomalie.getByRole('link', { name: 'Ouvrir' }).click();
  await page.locator('[data-onglet="CONTROLES"]').click();
  await expect(page.locator('[data-anomalie="VAT_AMOUNT_MISMATCH"]')).toBeVisible();

  // correction par un ajustement justifié, lié à l'opération
  await page.locator('[data-onglet="AJUSTEMENTS"]').click();
  await page.locator('[data-action="nouvel-ajustement"]').click();
  await page.locator('[data-champ="ajustement-montant"]').fill('3');
  await page.locator('[data-champ="ajustement-motif"]').fill('Arrondi commercial accepté par le client');
  await page.locator('[data-champ="ajustement-operation"]').selectOption({ index: 1 });
  await page.locator('[data-action="enregistrer-ajustement"]').click();
  await expect(page.locator('[data-ajustement]')).toHaveCount(1);
  await page.locator('[data-onglet="CONTROLES"]').click();
  await expect(page.locator('[data-champ="aucune-anomalie"]')).toBeVisible();

  // la file du cabinet est à jour sans autre action
  await page.getByRole('link', { name: 'Cabinet', exact: true }).click();
  await expect(page.locator('[data-zone="a-traiter"] tr[data-item]').filter({ hasText: 'TVA comptabilisée 103' })).toHaveCount(0);
});
