// Démarre le backend Spring Boot (port 8081) pour l'E2E : mvnw.cmd sous Windows, mvnw ailleurs. Sert de commande à Playwright (webServer).
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// La suite E2E crée un cabinet et plusieurs connexions par scénario depuis une seule adresse : les plafonds de débit (protection de production) sont relevés pour CE backend seulement.
const DEBITS_E2E = { RATE_LOGIN_MAX: '100000', RATE_RESET_MAX: '100000', RATE_REGISTER_MAX: '100000' };
const racine = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const windows = process.platform === 'win32';
const commande = windows ? path.join(racine, 'mvnw.cmd') : path.join(racine, 'mvnw');
const processus = spawn(commande, ['-q', 'spring-boot:run', '-Dspring-boot.run.arguments=--server.port=8081'], { cwd: racine, stdio: 'inherit', shell: windows, env: { ...process.env, ...DEBITS_E2E } });
processus.on('exit', (code) => process.exit(code ?? 0));
process.on('SIGTERM', () => processus.kill());
process.on('SIGINT', () => processus.kill());
