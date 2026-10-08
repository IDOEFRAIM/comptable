import { formaterMontant } from './montant';

describe('montant', () => {
  it('formate en milliers français sans transformer une valeur absente en zéro', () => {
    expect(formaterMontant(1234567).replace(/\s/g, ' ')).toContain('1 234 567');
    expect(formaterMontant(null)).toBe('');
  });
});
