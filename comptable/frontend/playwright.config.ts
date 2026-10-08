import { defineConfig } from '@playwright/test';

/**
 * E2E navigateur du dossier financier (Playwright, Chrome du poste : aucun navigateur n'est téléchargé).
 * Les deux serveurs sont démarrés s'ils ne tournent pas déjà : le backend Spring Boot (port 8081, PostgreSQL via Docker Compose) et le frontend Angular (port 4201, proxy /api → 8081).
 * Commande : `npm run e2e` (Docker doit être disponible pour le PostgreSQL du backend).
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 360_000,
  expect: { timeout: 20_000 },
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:4201',
    channel: 'chrome',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: [
    {
      command: 'node e2e/demarrer-backend.mjs',
      // GET sans jeton : 401/403 prouvent que le serveur répond
      url: 'http://localhost:8081/api/entreprises',
      reuseExistingServer: true,
      timeout: 360_000,
      stdout: 'pipe',
    },
    {
      command: 'npx ng serve --proxy-config proxy.conf.json --port 4201',
      url: 'http://localhost:4201',
      reuseExistingServer: true,
      timeout: 180_000,
    },
  ],
});
