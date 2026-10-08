import { Page, expect, request, test } from '@playwright/test';
import { API, amorcerCabinet, preparerParApi } from './support';

/**
 * Cabinet Workspace de bout en bout, avec les éléments réels de l'interface et un backend PostgreSQL réel. Les jeux de données (cabinet, collaborateur, entreprise, écritures, demandes) sont créés
 * par l'API avec des comptes jetables propres à chaque exécution ; tout le reste est fait par l'interface.
 */

async function connecter(page: Page, email: string, motDePasse: string): Promise<void> {
  page.on('dialog', (d) => (d.type() === 'prompt' ? d.accept('Dettes fournisseurs ouvertes acceptees pour le test E2E') : d.accept()));
  await page.goto('/login');
  await page.getByRole('textbox', { name: /email/i }).fill(email);
  await page.locator('input[type="password"]').fill(motDePasse);
  await page.getByRole('button', { name: 'Se connecter' }).click();
  await expect(page).toHaveURL(/\/cabinet$/);
}

test('collaborateur : de son tableau de bord à l\'anomalie corrigée, le compteur se met à jour', async ({ page }) => {
  const api = await request.newContext();
  const c = await amorcerCabinet(api, false);

  await connecter(page, c.collaborateur.email, c.collaborateur.motDePasse);

  // le collaborateur arrive sur SES dossiers, avec une file « À traiter » claire
  await expect(page.getByRole('heading', { name: 'Cabinet' })).toBeVisible();
  await expect(page.locator('[data-perimetre="MES_DOSSIERS"]')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('[data-champ="resume-actifs"]')).toContainText('1');
  const file = page.locator('[data-zone="a-traiter"]');
  const anomalie = file.locator('tr[data-item]').filter({ hasText: /sans evenement qualifi/i });
  await expect(anomalie).toHaveCount(1);
  await expect(anomalie).toContainText('Société Démo');
  await expect(anomalie).toContainText('Comptabilité');
  const avant = await file.locator('tr[data-item]').count();

  // « Mes dossiers » : le portefeuille montre le dossier avec son statut calculé par le serveur
  await page.getByRole('link', { name: 'Dossiers', exact: true }).click();
  await expect(page.locator('[data-dossier]')).toHaveCount(1);
  await expect(page.locator('[data-dossier]')).toHaveAttribute('data-statut-dossier', 'ATTENTION_REQUISE');
  await page.getByRole('link', { name: 'Cabinet', exact: true }).click();

  // ouvre la source de l'anomalie, la corrige par l'interface (qualification du flux de trésorerie)
  await anomalie.getByRole('link', { name: 'Ouvrir' }).click();
  await expect(page.getByRole('heading', { name: /Dossier financier/ })).toBeVisible();
  await page.locator('[data-onglet="CONTROLES"]').click();
  await expect(page.locator('[data-exception="UNQUALIFIED_CASH_EVENT"]')).toBeVisible();
  await page.locator('[data-zone="qualification"] select').selectOption('CAPITAL_CASH_INCREASE');
  await page.locator('[data-action="qualifier"]').click();
  await expect(page.locator('[data-exception="UNQUALIFIED_CASH_EVENT"]')).toHaveCount(0);

  // retour au tableau de bord : l'anomalie a disparu, la file est plus courte
  await page.getByRole('link', { name: 'Cabinet', exact: true }).click();
  await expect(page.locator('[data-zone="a-traiter"] tr[data-item]').filter({ hasText: /sans evenement qualifi/i })).toHaveCount(0);
  await expect.poll(async () => page.locator('[data-zone="a-traiter"] tr[data-item]').count()).toBeLessThan(avant);
});

test('attente client : la pièce demandée met le dossier en attente, sa résolution recalcule le statut', async ({ page }) => {
  const api = await request.newContext();
  const c = await amorcerCabinet(api, false);
  await preparerParApi(api, c);                                      // aucune anomalie interne : la seule suite dépend du client
  await api.post(`${API}/entreprises/${c.entrepriseId}/demandes-pieces`, { headers: c.entete, data: { libelle: 'Facture du fournisseur Station XYZ', description: 'Pièce manquante', envoyer: false } })
    .then(async (r) => expect(r.status()).toBe(201));

  await connecter(page, c.collaborateur.email, c.collaborateur.motDePasse);
  await expect(page.locator('[data-champ="resume-attente-client"]')).toContainText('1');

  await page.locator('[data-champ="resume-attente-client"]').click();
  await expect(page).toHaveURL(/filtre=EN_ATTENTE_CLIENT/);
  const ligne = page.locator('[data-dossier]');
  await expect(ligne).toHaveCount(1);
  await expect(ligne).toHaveAttribute('data-statut-dossier', 'EN_ATTENTE_CLIENT');
  await expect(ligne).toContainText('En attente du client');

  // la demande est ouverte depuis le dossier
  await ligne.getByRole('link', { name: 'Ouvrir' }).click();
  await expect(page).toHaveURL(/demandes-pieces/);
  await expect(page.getByText('Facture du fournisseur Station XYZ')).toBeVisible();

  // la pièce est reçue, la demande est résolue (action du cabinet) : le statut est recalculé par le backend
  const jeton = await page.evaluate(() => Object.values(localStorage).find((v) => v.split('.').length === 3) as string);
  const demandes = await (await api.get(`${API}/entreprises/${c.entrepriseId}/demandes-pieces`, { headers: { Authorization: `Bearer ${jeton}` } })).json();
  const resolue = await api.post(`${API}/entreprises/${c.entrepriseId}/demandes-pieces/${demandes[0].id}/marquer-recue`, { headers: { Authorization: `Bearer ${jeton}` }, data: { commentaire: 'Reçue par e-mail' } });
  expect(resolue.status()).toBe(200);

  await page.goto('/cabinet/dossiers?filtre=MES_DOSSIERS');
  await expect(page.locator('[data-dossier]')).toHaveCount(1);
  await expect(page.locator('[data-dossier]')).not.toHaveAttribute('data-statut-dossier', 'EN_ATTENTE_CLIENT');
  await page.locator('[data-filtre="EN_ATTENTE_CLIENT"]').click();
  await expect(page.locator('[data-champ="portefeuille-vide"]')).toBeVisible();
});

test('expert : file « À revoir », revue des notes, clôture et finalisation, le dossier quitte la file', async ({ page }) => {
  const api = await request.newContext();
  const c = await amorcerCabinet(api, false);
  await preparerParApi(api, c);                                      // le collaborateur a terminé la préparation : rien ne bloque

  await connecter(page, c.email, c.motDePasse);
  await expect(page.locator('[data-champ="resume-prets-revue"]')).toContainText('1');
  const revue = page.locator('[data-zone="a-revoir"]');
  await expect(revue).toContainText('Société Démo');
  await expect(revue).toContainText('Prêt pour revue');

  // l'expert ouvre directement le dossier de révision
  await revue.getByRole('link', { name: 'Ouvrir la revue' }).click();
  await expect(page.getByRole('heading', { name: /Dossier financier/ })).toBeVisible();
  await page.locator('[data-onglet="NOTES"]').click();
  for (const code of ['2', '17', '22', '23']) {
    await page.locator('[data-filtre="TOUTES"]').click();
    await page.locator(`ul.notes li[data-note="${code}"]`).click();
    await page.locator('[data-action="marquer-revue"]').click();
    await expect(page.locator('[data-champ="revue-info"]')).toBeVisible();
  }

  // revue terminée : le cabinet voit le dossier « prêt à clôturer »
  await page.getByRole('link', { name: 'Cabinet', exact: true }).click();
  await expect(page.locator('[data-champ="resume-prets-revue"]')).toContainText('0');
  await page.getByRole('link', { name: 'Dossiers', exact: true }).click();
  await page.locator('[data-filtre="PRETS_CLOTURE"]').click();
  await expect(page.locator('[data-dossier]').first()).toHaveAttribute('data-statut-dossier', 'PRET_A_CLOTURER');

  // clôture puis finalisation (expert)
  await page.locator('[data-dossier]').first().getByRole('link', { name: 'Ouvrir' }).click();
  await page.locator('[data-onglet="VERSION_FINALE"]').click();
  await page.locator('[data-action="cloturer"]').click();
  await expect(page.locator('[data-action="finaliser"]').last()).toBeVisible({ timeout: 60_000 });
  await page.locator('[data-zone="pre-finalisation"] [data-action="finaliser"]').click();
  await page.locator('[data-action="confirmer-finalisation"]').click();
  await expect(page.locator('[data-zone="dossier-finalise"]')).toContainText('Dossier finalisé', { timeout: 60_000 });

  // le dossier est FINALISE et n'est plus dans la file à revoir
  await page.getByRole('link', { name: 'Dossiers', exact: true }).click();
  await page.locator('[data-filtre="FINALISES"]').click();
  await expect(page.locator('[data-dossier]').filter({ hasText: 'Société Démo' }).first()).toHaveAttribute('data-statut-dossier', 'FINALISE');
  await page.getByRole('link', { name: 'Cabinet', exact: true }).click();
  await expect(page.locator('[data-zone="a-revoir"]')).toContainText('Aucun dossier à revoir');
  await expect(page.locator('[data-champ="resume-prets-revue"]')).toContainText('0');
});

test('équipe : l’expert ajoute un collaborateur, lui affecte un dossier et filtre le portefeuille sur lui', async ({ page }) => {
  const api = await request.newContext();
  const c = await amorcerCabinet(api, false);                         // un cabinet, une entreprise ; le collaborateur « Fatou Kone » existe déjà
  await connecter(page, c.email, c.motDePasse);

  // Équipe : membres existants visibles ; ajout d'un collaborateur par le formulaire
  await page.getByRole('link', { name: 'Équipe', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Équipe' })).toBeVisible();
  await expect(page.locator('[data-membre]')).toHaveCount(2);
  const email = `awa-${Math.random().toString(16).slice(2, 8)}@test.ci`;
  await page.locator('[data-action="ajouter-membre"]').click();
  await page.locator('[data-champ="membre-prenom"]').fill('Awa');
  await page.locator('[data-champ="membre-nom"]').fill('Traore');
  await page.locator('[data-champ="membre-email"]').fill('pas-un-mail');
  await expect(page.locator('[data-champ="erreur-email"]')).toBeVisible();
  await expect(page.locator('[data-action="enregistrer-membre"]')).toBeDisabled();
  await page.locator('[data-champ="membre-email"]').fill(email);
  await page.locator('[data-action="enregistrer-membre"]').click();
  await expect(page.locator('[data-champ="mot-de-passe-temporaire"]')).toContainText('Awa Traore');
  await expect(page.locator(`[data-membre="${email}"]`)).toContainText('Collaborateur');
  await expect(page.locator(`[data-membre="${email}"]`)).toContainText('Actif');

  // un doublon est refusé par le backend, message métier affiché
  await page.locator('[data-action="ajouter-membre"]').click();
  await page.locator('[data-champ="membre-prenom"]').fill('Awa');
  await page.locator('[data-champ="membre-nom"]').fill('Traore');
  await page.locator('[data-champ="membre-email"]').fill(email);
  await page.locator('[data-action="enregistrer-membre"]').click();
  await expect(page.locator('[data-champ="erreur-equipe"]')).toContainText('déjà utilisée');

  // le nouveau collaborateur est proposé immédiatement à l'affectation : sélection du dossier -> affectation en masse
  await page.getByRole('link', { name: 'Dossiers', exact: true }).click();
  await expect(page.locator('[data-dossier]')).toHaveCount(1);
  await page.locator('[data-dossier] input[type="checkbox"]').check();
  await page.locator('[data-champ="masse-collaborateur"]').selectOption({ label: 'Traore Awa' });
  await page.locator('[data-action="affecter-masse"]').click();
  await expect(page.locator('[data-champ="info"]')).toContainText('1 dossier affecté');

  // filtre du portefeuille sur ce collaborateur : le dossier est visible ; sur l'autre collaborateur : il ne l'est plus
  await page.locator('[data-champ="filtre-collaborateur"]').selectOption({ label: 'Traore Awa' });
  await expect(page.locator('[data-dossier]')).toHaveCount(1);
  await expect(page.locator('[data-dossier]')).toContainText('Traore Awa');
  await page.locator('[data-champ="filtre-collaborateur"]').selectOption({ label: 'Kone Fatou' });
  await expect(page.locator('[data-champ="portefeuille-vide"]')).toBeVisible();

  // l'Équipe compte désormais un dossier affecté pour Awa ; la désactivation la retire des sélecteurs
  await page.getByRole('link', { name: 'Équipe', exact: true }).click();
  await expect(page.locator(`[data-membre="${email}"]`)).toContainText('1');
  await page.locator(`[data-membre="${email}"] [data-action="desactiver-membre"]`).click();
  await expect(page.locator(`[data-membre="${email}"]`)).toContainText('Désactivé');
  await page.getByRole('link', { name: 'Dossiers', exact: true }).click();
  await expect(page.locator('[data-champ="filtre-collaborateur"] option', { hasText: 'Traore Awa' })).toHaveCount(0);
});

test('anomalie financière proactive : visible dans le Cabinet Workspace sans jamais ouvrir le dossier financier', async ({ page }) => {
  const api = await request.newContext();
  const c = await amorcerCabinet(api, false);                         // écritures validées par l'API : AUCUN appel au dossier financier ni aux contrôles
  await connecter(page, c.collaborateur.email, c.collaborateur.motDePasse);

  const anomalie = page.locator('[data-zone="a-traiter"] tr[data-item]').filter({ hasText: /sans evenement qualifi/i });
  await expect(anomalie).toHaveCount(1);                              // l'écriture de capital non qualifiée est déjà dans la file
  await expect(page.locator('[data-zone="a-traiter"] tr[data-item]').filter({ hasText: /Note 17/ })).toHaveCount(1);

  // la correction se fait hors du dossier financier (qualification par l'API, comme le ferait un autre poste) : la file se met à jour au rechargement
  const jeton = await page.evaluate(() => Object.values(localStorage).find((v) => v.split('.').length === 3) as string);
  const q = await api.put(`${API}/entreprises/${c.entrepriseId}/etats-financiers-syscohada/ecritures/${c.ecritureCapitalId}/cash-flow-event`, { headers: { Authorization: `Bearer ${jeton}` }, data: { event: 'CAPITAL_CASH_INCREASE' } });
  expect(q.ok()).toBeTruthy();
  await page.reload();
  await expect(page.locator('[data-zone="a-traiter"] tr[data-item]').filter({ hasText: /sans evenement qualifi/i })).toHaveCount(0);
  await expect(page.locator('[data-zone="a-traiter"] tr[data-item]').filter({ hasText: /Note 17/ })).toHaveCount(1);
});
