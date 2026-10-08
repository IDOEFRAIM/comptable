import { Page, expect, request, test } from '@playwright/test';
import { API, amorcer } from './support';

/**
 * Parcours E2E réel d'un expert-comptable, avec les éléments de l'interface (boutons, champs, onglets) et un backend PostgreSQL réel :
 * connexion → dossier → qualification d'un flux de trésorerie → Note 17 (MIXED) → revue → modification (revue obsolète) → Note 22 → Note 2 (MANUAL_REQUIRED, propre) → Note 23
 * (automatique) → Note 35 (effectif 251 puis 250) → validation en masse des non-applicabilités → décisions restantes → export brouillon → clôture → finalisation → snapshot
 * (Notes 2, 17, 22, 23 jointes) → export final → double finalisation impossible.
 *
 * Le jeu de données est créé par l'API (seule étape non visuelle : un cabinet, une entreprise et trois écritures) avec un compte de test jetable propre à chaque exécution.
 */

const note = (page: Page, code: string) => page.locator(`ul.notes li[data-note="${code}"]`);
const onglet = (page: Page, code: string) => page.locator(`[data-onglet="${code}"]`);

async function ouvrirNote(page: Page, code: string): Promise<void> {
  await page.locator('[data-filtre="TOUTES"]').click();
  await note(page, code).click();
  await expect(page.locator('.detail-note h3')).toContainText(`Note ${code} `);
}

async function remplirSections(page: Page, texte: string): Promise<void> {
  const champs = page.locator('.detail-note fieldset.section textarea');
  const n = await champs.count();
  for (let i = 0; i < n; i++) {
    await champs.nth(i).fill(texte);
  }
}

test('dossier financier de bout en bout : de la connexion au snapshot FINAL exporté', async ({ page, context }) => {
  const api = await request.newContext();
  const dossier = await amorcer(api);

  // dialogues natifs du workflow de clôture : confirmation irréversible, puis motif d'acceptation des avertissements
  page.on('dialog', (d) => (d.type() === 'prompt' ? d.accept('Dettes fournisseurs ouvertes acceptees pour le test E2E') : d.accept()));

  // ---- connexion (champs et bouton réels)
  await page.goto('/login');
  await page.getByRole('textbox', { name: /email/i }).fill(dossier.email);
  await page.locator('input[type="password"]').fill(dossier.motDePasse);
  await page.getByRole('button', { name: 'Se connecter' }).click();
  await expect(page).toHaveURL(/\/(entreprises|cabinet)/);

  // ---- ouverture du dossier financier
  await page.goto(`/entreprises/${dossier.entrepriseId}/etats-financiers`);
  await expect(page.getByRole('heading', { name: /Dossier financier/ })).toBeVisible();
  await expect(page.locator('[data-champ="finalisable"]')).toContainText('NON');
  await expect(page.locator('[data-etat-tuile="TFT"]')).toContainText('Incomplet');

  // ---- anomalie : flux de trésorerie non qualifié → qualification (l'enum backend est validé côté serveur)
  await onglet(page, 'CONTROLES').click();
  await expect(page.locator('[data-exception="UNQUALIFIED_CASH_EVENT"]')).toBeVisible();
  await page.locator('[data-zone="qualification"] select').selectOption('CAPITAL_CASH_INCREASE');
  await page.locator('[data-action="qualifier"]').click();
  await expect(page.locator('[data-exception="UNQUALIFIED_CASH_EVENT"]')).toHaveCount(0);
  await onglet(page, 'VUE_ENSEMBLE').click();
  await expect(page.locator('[data-etat-tuile="TFT"]')).toContainText('Prêt');

  // ---- Note 17 (MIXED) : incomplète → saisie → prête → revue
  await onglet(page, 'NOTES').click();
  await ouvrirNote(page, '17');
  await expect(page.locator('.detail-note .entete-detail')).toContainText('Incomplet');
  await expect(page.locator('[data-zone="donnees-calculees"]')).toContainText('300');
  await page.locator('[data-champ="champ-groupe"]').fill('Aucune dette envers le groupe');
  await page.locator('[data-champ="champ-echeances"]').fill('Paiement a 30 jours fin de mois');
  await page.locator('[data-action="enregistrer-note"]').click();
  await expect(page.locator('.detail-note .entete-detail')).toContainText('Prêt');
  await page.locator('[data-action="marquer-revue"]').click();
  await expect(page.locator('[data-champ="revue-info"]')).toBeVisible();
  await expect(note(page, '17')).toContainText('Revue');

  // ---- modification après revue : la revue devient obsolète, puis nouvelle revue
  await page.locator('[data-champ="champ-groupe"]').fill('Aucune dette envers le groupe (precision)');
  await page.locator('[data-action="enregistrer-note"]').click();
  await expect(page.locator('[data-champ="revue-obsolete"]')).toContainText('Revue devenue obsolète');
  await expect(note(page, '17')).toContainText('Revue : non');
  await page.locator('[data-action="marquer-revue"]').click();
  await expect(page.locator('[data-champ="revue-info"]')).toBeVisible();

  // ---- Note 22 (MIXED) ; Note 23 (automatique, revue)
  await ouvrirNote(page, '22');
  await remplirSections(page, 'Ventilation fournie pour le test');
  await page.locator('[data-action="enregistrer-note"]').click();
  await expect(page.locator('.detail-note .entete-detail')).toContainText('Prêt');
  await ouvrirNote(page, '23');
  await expect(page.locator('[data-zone="donnees-calculees"]')).toContainText('100');
  await expect(page.locator('.detail-note .entete-detail')).toContainText('Prêt');
  await page.locator('[data-action="marquer-revue"]').click();
  await expect(page.locator('[data-champ="revue-info"]')).toBeVisible();

  // ---- Note 2 (MANUAL_REQUIRED) : décision APPLICABLE, saisie, prête — elle sera jointe au snapshot
  await ouvrirNote(page, '2');
  await expect(page.locator('.detail-note .entete-detail')).toContainText('À décider');
  await page.locator('[data-action="applicable"]').click();
  await expect(page.locator('.detail-note .entete-detail')).toContainText('Applicable');
  await remplirSections(page, 'Renseigne pour le test');
  await page.locator('[data-action="enregistrer-note"]').click();
  await expect(page.locator('.detail-note .entete-detail')).toContainText('Prêt');

  // ---- Note 35 : effectif 251 → applicable ; effectif 250 → non applicable (règle réglementaire)
  await ouvrirNote(page, '35');
  await expect(page.locator('[data-champ="note35"]')).toContainText('Effectif non renseigné');
  await page.locator('#champ-effectif-effectif').fill('251');
  await page.locator('[data-action="enregistrer-note"]').click();
  await expect(page.locator('[data-champ="note35"]')).toContainText('Note obligatoire');
  await expect(page.locator('.detail-note .entete-detail')).toContainText('Applicable');
  await page.locator('#champ-effectif-effectif').fill('250');
  await page.locator('[data-action="enregistrer-note"]').click();
  await expect(page.locator('[data-champ="note35"]')).toContainText('non obligatoire');
  await expect(page.locator('.detail-note .entete-detail')).toContainText('Non applicable');

  // ---- validation en masse : la liste est affichée avant la confirmation explicite
  await page.locator('[data-action="valider-candidates"]').click();
  await expect(page.locator('[data-zone="confirmation-candidates"]')).toContainText('Note 9');
  await page.locator('[data-action="confirmer-candidates"]').click();
  await expect(page.locator('[data-zone="candidates"]')).toHaveCount(0);

  // ---- décisions restantes, une par une, par l'interface (filtre « À décider »)
  await page.locator('[data-filtre="A_DECIDER"]').click();
  for (let restantes = await page.locator('ul.notes li[data-note]').count(); restantes > 0; restantes = await page.locator('ul.notes li[data-note]').count()) {
    await page.locator('ul.notes li[data-note]').first().click();
    await page.locator('[data-action="non-applicable"]').click();
    await expect(page.locator('ul.notes li[data-note]')).toHaveCount(restantes - 1);
  }
  await expect(page.locator('[data-champ="progression-notes"]')).toContainText('46 / 46 décidées');

  // ---- pré-finalisation : aperçu BROUILLON, exercice ouvert donc non finalisable
  await onglet(page, 'VERSION_FINALE').click();
  await expect(page.locator('[data-action="finaliser"]')).toHaveCount(0);
  const [apercu] = await Promise.all([context.waitForEvent('page'), page.locator('[data-action="previsualiser"]').click()]);
  await apercu.waitForLoadState();
  await expect(apercu.locator('body')).toContainText('BROUILLON');
  await expect(apercu.locator('body')).not.toContainText('DOSSIER FINANCIER FINAL');
  await apercu.close();

  // ---- clôture puis finalisation (confirmation explicite d'immuabilité)
  await page.locator('[data-action="cloturer"]').click();
  await expect(page.locator('[data-action="finaliser"]')).toBeVisible({ timeout: 60_000 });
  await page.locator('[data-action="finaliser"]').click();
  await expect(page.locator('[data-zone="confirmation-finalisation"]')).toContainText('version immuable');
  await page.locator('[data-action="confirmer-finalisation"]').click();
  await expect(page.locator('[data-zone="dossier-finalise"]')).toContainText('Dossier finalisé', { timeout: 60_000 });
  await expect(page.locator('[data-action="finaliser"]')).toHaveCount(0);

  // ---- snapshot FINAL : états figés, quatre notes jointes (automatique, mixtes, manuelle)
  const viewer = page.locator('[data-zone="snapshot-viewer"]');
  await expect(viewer).toContainText('Dossier financier final');
  const notes = page.locator('[data-zone="snapshot-notes"]');
  await expect(notes).toContainText('Notes annexes jointes (4)');
  for (const attendue of ['Note 2 ', 'Note 17 ', 'Note 22 ', 'Note 23 ']) {
    await expect(notes).toContainText(attendue);
  }
  await expect(notes).toContainText('Aucune dette envers le groupe (precision)');
  await expect(notes).toContainText('Renseigne pour le test');

  // ---- export FINAL depuis le snapshot
  const [exportFinal] = await Promise.all([context.waitForEvent('page'), page.locator('[data-action="exporter-final"]').click()]);
  await exportFinal.waitForLoadState();
  await expect(exportFinal.locator('body')).toContainText('DOSSIER FINANCIER FINAL');
  await expect(exportFinal.locator('.filigrane')).toHaveCount(0);
  await expect(exportFinal.locator('body')).toContainText('Note 17');
  await exportFinal.close();

  // ---- une seconde finalisation est impossible : plus aucune action, et le backend la refuse (409)
  const jeton = await page.evaluate(() => Object.values(localStorage).find((v) => v.split('.').length === 3) as string);
  const exercices = await (await api.get(`${API}/entreprises/${dossier.entrepriseId}/exercices`, { headers: { Authorization: `Bearer ${jeton}` } })).json();
  const cloture = exercices.find((e: { statut: string }) => e.statut === 'CLOTURE');
  const seconde = await api.post(`${API}/entreprises/${dossier.entrepriseId}/etats-financiers-syscohada/DOSSIER_COMPLET/finaliser?exerciceId=${cloture.id}`, {
    headers: { Authorization: `Bearer ${jeton}` },
  });
  expect(seconde.status()).toBe(409);
});
