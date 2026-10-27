(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.Arvore = Object.assign(root.Arvore || {}, { Dates: api });
})(typeof self !== 'undefined' ? self : this, function () {
  // Datas são guardadas como texto ISO parcial: "1980-03-12", "1980-03" ou "1980",
  // porque em genealogia é comum só se saber o ano (ou o mês).
  const MONTHS = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];

  const pad = (n, size) => String(n).padStart(size, '0');

  // "12/03/1980", "03/1980" ou "1980" -> ISO parcial. "" -> "". Inválida -> null.
  function parseBR(text, today = new Date()) {
    const t = String(text || '').trim();
    if (!t) return '';
    const m = /^(?:(?:(\d{1,2})\/)?(\d{1,2})\/)?(\d{4})$/.exec(t);
    if (!m) return null;
    const day = m[1] ? Number(m[1]) : null;
    const month = m[2] ? Number(m[2]) : null;
    const year = Number(m[3]);
    if (year < 1000 || year > today.getFullYear()) return null;
    if (month !== null && (month < 1 || month > 12)) return null;
    if (day !== null) {
      const probe = new Date(Date.UTC(year, month - 1, day));
      if (probe.getUTCMonth() !== month - 1 || probe.getUTCDate() !== day) return null;
      if (probe > today) return null;
    }
    if (month !== null && day === null && year === today.getFullYear() && month > today.getMonth() + 1) return null;
    return [pad(year, 4), month && pad(month, 2), day && pad(day, 2)].filter(Boolean).join('-');
  }

  function parts(iso) {
    const m = /^(\d{4})(?:-(\d{2})(?:-(\d{2}))?)?$/.exec(iso || '');
    return m ? { year: m[1], month: m[2] || null, day: m[3] || null } : null;
  }

  function formatBR(iso) {
    const p = parts(iso);
    if (!p) return '';
    return [p.day, p.month, p.year].filter(Boolean).join('/');
  }

  function year(iso) {
    const p = parts(iso);
    return p ? p.year : '';
  }

  // ISO parcial -> data GEDCOM: "12 MAR 1980", "MAR 1980", "1980"
  function toGedcom(iso) {
    const p = parts(iso);
    if (!p) return '';
    return [p.day && String(Number(p.day)), p.month && MONTHS[Number(p.month) - 1], p.year].filter(Boolean).join(' ');
  }

  // Data GEDCOM -> ISO parcial. Aceita prefixos de data aproximada (ABT, EST, CAL, BEF, AFT)
  // e intervalos (BET x AND y, FROM x TO y), ficando com a primeira data. Não reconhecida -> null.
  function fromGedcom(text) {
    let t = String(text || '').trim().toUpperCase();
    if (!t) return '';
    t = t.replace(/^(ABT|ABOUT|EST|CAL|BEF|AFT|FROM|BET|BETWEEN)\.?\s+/, '').replace(/\s+(AND|TO)\s+.*$/, '');
    const m = /^(?:(?:(\d{1,2})\s+)?([A-Z]{3})\s+)?(\d{4})$/.exec(t);
    if (!m) return null;
    const month = m[2] ? MONTHS.indexOf(m[2]) + 1 : 0;
    if (m[2] && !month) return null;
    const day = m[1] ? Number(m[1]) : null;
    if (day !== null) {
      const probe = new Date(Date.UTC(Number(m[3]), month - 1, day));
      if (probe.getUTCMonth() !== month - 1 || probe.getUTCDate() !== day) return null;
    }
    return [m[3], month && pad(month, 2), day && pad(day, 2)].filter(Boolean).join('-');
  }

  return { parseBR, formatBR, year, toGedcom, fromGedcom };
});
