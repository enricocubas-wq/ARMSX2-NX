(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.Arvore = Object.assign(root.Arvore || {}, { Cpf: api });
})(typeof self !== 'undefined' ? self : this, function () {
  function digits(value) {
    return String(value || '').replace(/\D/g, '');
  }

  function checkDigit(base) {
    let sum = 0;
    for (let i = 0; i < base.length; i++) sum += Number(base[i]) * (base.length + 1 - i);
    const rest = (sum * 10) % 11;
    return rest === 10 ? 0 : rest;
  }

  function isValid(value) {
    const d = digits(value);
    if (d.length !== 11 || /^(\d)\1{10}$/.test(d)) return false;
    const first = checkDigit(d.slice(0, 9));
    const second = checkDigit(d.slice(0, 9) + first);
    return first === Number(d[9]) && second === Number(d[10]);
  }

  // Formata aos poucos enquanto a pessoa digita: 123 -> 123.4 -> 123.456.789-09
  function format(value) {
    const d = digits(value).slice(0, 11);
    let out = [d.slice(0, 3), d.slice(3, 6), d.slice(6, 9)].filter(Boolean).join('.');
    if (d.length > 9) out += '-' + d.slice(9);
    return out;
  }

  // Mostra só os 2 últimos dígitos, para não deixar o CPF inteiro na tela.
  function mask(value) {
    const d = digits(value);
    return d.length === 11 ? '•••.•••.•••-' + d.slice(9) : '';
  }

  return { digits, isValid, format, mask };
});
