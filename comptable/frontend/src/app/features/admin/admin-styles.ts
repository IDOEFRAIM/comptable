/** Styles communs aux écrans d'administration de la plateforme. */
export const STYLES_ADMIN = `
  :host { display: block; }
  .page { max-width: 1180px; margin: 0 auto; padding: 1.5rem 1rem 3rem; display: flex; flex-direction: column; gap: 1rem; }
  h1 { margin: 0; font-size: 1.4rem; }
  .intro { margin: 0.25rem 0 0; color: var(--text-secondary, #475467); font-size: 0.875rem; }
  .filtres { display: flex; gap: 0.6rem; flex-wrap: wrap; align-items: flex-end; }
  .filtres label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8125rem; color: var(--text-secondary, #475467); }
  input, select, textarea { padding: 0.5rem 0.6rem; border: 1px solid var(--border-default, #d0d5dd); border-radius: 8px; font: inherit; background: #fff; }
  button { border: 1px solid var(--color-primary, #14662f); background: #fff; color: var(--color-primary, #14662f); border-radius: 8px; padding: 0.45rem 0.9rem; font-size: 0.8125rem; font-weight: 600; cursor: pointer; }
  button.principal { background: var(--color-primary, #14662f); color: #fff; }
  button.danger { border-color: #b42318; color: #b42318; }
  button.petit { padding: 0.25rem 0.6rem; font-size: 0.75rem; }
  button:disabled { opacity: 0.55; cursor: default; }
  table { width: 100%; border-collapse: collapse; background: #fff; border: 1px solid var(--border-subtle, #eaecf0); border-radius: 10px; font-size: 0.8125rem; }
  th { text-align: left; padding: 0.55rem 0.7rem; color: var(--text-tertiary, #667085); font-weight: 600; border-bottom: 1px solid var(--border-subtle, #eaecf0); white-space: nowrap; }
  td { padding: 0.55rem 0.7rem; border-bottom: 1px solid var(--bg-subtle-hover, #f2f4f7); vertical-align: top; }
  .tableau { overflow-x: auto; }
  .badge { display: inline-block; border-radius: 999px; padding: 0.05rem 0.55rem; font-size: 0.75rem; font-weight: 600; background: #ecf6ef; color: #14662f; margin-right: 0.25rem; }
  .badge.suspendu { background: #fee4e2; color: #b42318; }
  .badge.verrouille { background: #fff4d6; color: #8a5a00; }
  .badge.info { background: #eff8ff; color: #0b3b6e; }
  .actions { display: flex; gap: 0.35rem; flex-wrap: wrap; }
  .secondaire { color: var(--text-tertiary, #667085); font-size: 0.75rem; }
  .erreur { color: #b42318; font-size: 0.875rem; margin: 0; }
  .vide { padding: 2rem 1rem; text-align: center; color: var(--text-tertiary, #667085); }
  .pagination { display: flex; gap: 0.6rem; align-items: center; justify-content: flex-end; font-size: 0.8125rem; }
  .panneau { background: #fff; border: 1px solid var(--border-default, #d0d5dd); border-radius: 10px; padding: 1rem 1.1rem; display: flex; flex-direction: column; gap: 0.6rem; }
  .panneau.alerte { border-color: #f2b705; background: #fffaeb; }
  .mdp { font-family: ui-monospace, Consolas, monospace; font-size: 1.1rem; letter-spacing: 0.05em; background: #fff; border: 1px dashed #98a2b3; border-radius: 8px; padding: 0.5rem 0.8rem; user-select: all; }
`;
