(function (root, factory) {
  const api = factory(
    typeof require === 'function' ? require('./model.js') : root.Arvore.Model,
    typeof require === 'function' ? require('./dates.js') : root.Arvore.Dates,
    typeof require === 'function' ? require('./cpf.js') : root.Arvore.Cpf
  );
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.Arvore = Object.assign(root.Arvore || {}, { Gedcom: api });
})(typeof self !== 'undefined' ? self : this, function (Model, Dates, Cpf) {
  // GEDCOM 5.5.1 é o formato padrão de troca de árvores genealógicas: Gramps, webtrees,
  // MyHeritage, Ancestry, Topola e a maioria dos programas de genealogia leem e gravam.
  const CHUNK = 200;

  // ---------- exportação ----------

  function oneLine(value) {
    return String(value || '').replace(/\s+/g, ' ').trim().replace(/@/g, '@@');
  }

  function buildFamilies(tree) {
    const families = new Map();
    const ensure = (husband, wife, key) => {
      if (!families.has(key)) families.set(key, { husband, wife, children: [] });
      return families.get(key);
    };
    const people = Object.values(tree.people);
    for (const p of people) {
      const parents = p.parentIds.map((id) => tree.people[id]).filter(Boolean);
      if (!parents.length) continue;
      let husband;
      let wife;
      if (parents.length === 2) [husband, wife] = Model.coupleOrder(parents[0], parents[1]);
      else if (parents[0].gender === 'F') wife = parents[0];
      else husband = parents[0];
      const key = parents.map((x) => x.id).sort().join('|');
      ensure(husband || null, wife || null, key).children.push(p);
    }
    for (const p of people) {
      for (const sid of p.spouseIds) {
        const spouse = tree.people[sid];
        if (!spouse || p.id > sid) continue;
        const [husband, wife] = Model.coupleOrder(p, spouse);
        ensure(husband, wife, [p.id, sid].sort().join('|'));
      }
    }
    return [...families.values()];
  }

  function exportGedcom(tree, options = {}) {
    const people = Object.values(tree.people);
    const indiRef = new Map(people.map((p, i) => [p.id, '@I' + (i + 1) + '@']));
    const families = buildFamilies(tree);
    const famRef = new Map(families.map((f, i) => [f, '@F' + (i + 1) + '@']));
    const famsOf = new Map();
    const famcOf = new Map();
    for (const f of families) {
      for (const parent of [f.husband, f.wife]) {
        if (!parent) continue;
        if (!famsOf.has(parent.id)) famsOf.set(parent.id, []);
        famsOf.get(parent.id).push(famRef.get(f));
      }
      for (const child of f.children) if (!famcOf.has(child.id)) famcOf.set(child.id, famRef.get(f));
    }

    const lines = [];
    // Valores longos ou com quebra de linha viram CONC/CONT, como manda o padrão.
    const push = (level, tag, value) => {
      if (value === undefined || value === '') {
        lines.push(level + ' ' + tag);
        return;
      }
      const paragraphs = String(value).replace(/\r\n?/g, '\n').split('\n');
      paragraphs.forEach((paragraph, i) => {
        const text = paragraph.replace(/@/g, '@@');
        const first = i === 0 ? level + ' ' + tag : level + 1 + ' CONT';
        lines.push(first + ' ' + text.slice(0, CHUNK));
        for (let at = CHUNK; at < text.length; at += CHUNK) lines.push(level + 1 + ' CONC ' + text.slice(at, at + CHUNK));
      });
    };
    // Campos de uma linha só: o '@' já é escapado por `oneLine`.
    const pushPlain = (level, tag, value) => {
      const clean = oneLine(value);
      if (clean) lines.push(level + ' ' + tag + ' ' + clean);
    };

    const today = new Date();
    lines.push('0 HEAD');
    lines.push('1 SOUR ArvoreGenealogica', '2 NAME Arvore Genealogica', '2 VERS 1.0');
    lines.push('1 DATE ' + Dates.toGedcom(today.toISOString().slice(0, 10)));
    lines.push('1 GEDC', '2 VERS 5.5.1', '2 FORM LINEAGE-LINKED');
    lines.push('1 CHAR UTF-8', '1 LANG Portuguese');

    for (const p of people) {
      lines.push('0 ' + indiRef.get(p.id) + ' INDI');
      const given = oneLine(p.firstName);
      const surname = oneLine(p.lastName);
      lines.push('1 NAME ' + given + ' /' + surname + '/');
      if (given) lines.push('2 GIVN ' + given);
      if (surname) lines.push('2 SURN ' + surname);
      if (p.gender) lines.push('1 SEX ' + p.gender);
      if (p.birthDate || p.birthPlace) {
        lines.push('1 BIRT');
        if (p.birthDate) lines.push('2 DATE ' + Dates.toGedcom(p.birthDate));
        pushPlain(2, 'PLAC', p.birthPlace);
      }
      if (p.deathDate) lines.push('1 DEAT', '2 DATE ' + Dates.toGedcom(p.deathDate));
      if (options.includeCpf && Cpf.digits(p.cpf)) lines.push('1 IDNO ' + Cpf.format(p.cpf), '2 TYPE CPF');
      if (p.notes) push(1, 'NOTE', p.notes);
      if (famcOf.has(p.id)) lines.push('1 FAMC ' + famcOf.get(p.id));
      for (const ref of famsOf.get(p.id) || []) lines.push('1 FAMS ' + ref);
    }

    for (const f of families) {
      lines.push('0 ' + famRef.get(f) + ' FAM');
      if (f.husband) lines.push('1 HUSB ' + indiRef.get(f.husband.id));
      if (f.wife) lines.push('1 WIFE ' + indiRef.get(f.wife.id));
      const byBirth = [...f.children].sort((a, b) => (a.birthDate || '9999').localeCompare(b.birthDate || '9999'));
      for (const child of byBirth) lines.push('1 CHIL ' + indiRef.get(child.id));
    }

    lines.push('0 TRLR');
    return lines.join('\n') + '\n';
  }

  // ---------- importação ----------

  function parse(text) {
    const root = { level: -1, children: [] };
    const stack = [root];
    for (const raw of String(text).replace(/^﻿/, '').split(/\r\n|\n|\r/)) {
      const m = /^\s*(\d+)\s+(?:(@[^@\s]+@)\s+)?([A-Za-z0-9_]+)(?:\s(.*))?$/.exec(raw);
      if (!m) continue;
      const node = { level: Number(m[1]), xref: m[2] || null, tag: m[3].toUpperCase(), value: m[4] || '', children: [] };
      while (stack.length > node.level + 1) stack.pop();
      const parent = stack[stack.length - 1];
      if (node.tag === 'CONT' || node.tag === 'CONC') {
        parent.value += (node.tag === 'CONT' ? '\n' : '') + node.value;
        continue;
      }
      parent.children.push(node);
      stack.push(node);
    }
    return root.children;
  }

  const sub = (node, tag) => node.children.find((c) => c.tag === tag);
  const unescape = (value) => String(value || '').replace(/@@/g, '@').trim();

  function parseName(node) {
    if (!node) return { first: '', last: '' };
    const m = /^(.*?)\s*\/(.*?)\/\s*(.*)$/.exec(node.value);
    let first = m ? m[1] : node.value;
    let last = m ? [m[2], m[3]].filter(Boolean).join(' ') : '';
    const givn = sub(node, 'GIVN');
    const surn = sub(node, 'SURN');
    if (givn && givn.value) first = givn.value;
    if (surn && surn.value) last = surn.value;
    return { first: unescape(first), last: unescape(last) };
  }

  // Aceita UTF-8 (com ou sem BOM), UTF-16 e, na falta, Windows-1252 (arquivos antigos "ANSI").
  function decode(buffer) {
    const bytes = new Uint8Array(buffer);
    if (bytes[0] === 0xff && bytes[1] === 0xfe) return new TextDecoder('utf-16le').decode(bytes);
    if (bytes[0] === 0xfe && bytes[1] === 0xff) return new TextDecoder('utf-16be').decode(bytes);
    try {
      return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    } catch (err) {
      return new TextDecoder('windows-1252').decode(bytes);
    }
  }

  function importGedcom(text) {
    const records = parse(text);
    if (!records.some((r) => r.tag === 'HEAD') || !records.some((r) => r.tag === 'INDI')) {
      throw new Error('Este arquivo não parece ser um GEDCOM válido (não encontrei o cabeçalho ou nenhuma pessoa).');
    }
    const warnings = [];
    const noteByXref = new Map(records.filter((r) => r.tag === 'NOTE' && r.xref).map((r) => [r.xref, r.value]));
    const tree = Model.emptyTree();
    const idByXref = new Map();
    let badDates = 0;

    const readDate = (node, label, notes) => {
      if (!node || !node.value) return '';
      const iso = Dates.fromGedcom(unescape(node.value));
      if (iso === null) {
        badDates++;
        notes.push(label + ' (data original): ' + unescape(node.value));
        return '';
      }
      return iso;
    };

    for (const rec of records.filter((r) => r.tag === 'INDI' && r.xref)) {
      const name = parseName(sub(rec, 'NAME'));
      const notes = [];
      const birth = sub(rec, 'BIRT');
      const death = sub(rec, 'DEAT');
      const sex = sub(rec, 'SEX');
      const person = Model.createPerson(tree, {
        firstName: name.first,
        lastName: name.last,
        gender: sex && (sex.value === 'M' || sex.value === 'F') ? sex.value : '',
        birthDate: readDate(birth && sub(birth, 'DATE'), 'Nascimento', notes),
        birthPlace: birth && sub(birth, 'PLAC') ? unescape(sub(birth, 'PLAC').value) : '',
        deathDate: readDate(death && sub(death, 'DATE'), 'Falecimento', notes),
      });
      for (const note of rec.children.filter((c) => c.tag === 'NOTE')) {
        const body = note.value.startsWith('@') && noteByXref.has(note.value.trim()) ? noteByXref.get(note.value.trim()) : note.value;
        if (body) notes.push(unescape(body));
      }
      const idno = rec.children.find((c) => c.tag === 'IDNO' && Cpf.isValid(c.value));
      if (idno) person.cpf = Cpf.format(idno.value);
      person.notes = notes.join('\n');
      idByXref.set(rec.xref, person.id);
    }

    let extraFamilies = 0;
    for (const fam of records.filter((r) => r.tag === 'FAM')) {
      const husband = tree.people[idByXref.get((sub(fam, 'HUSB') || {}).value)];
      const wife = tree.people[idByXref.get((sub(fam, 'WIFE') || {}).value)];
      if (husband && !husband.gender) husband.gender = 'M';
      if (wife && !wife.gender) wife.gender = 'F';
      const parents = [husband, wife].filter(Boolean);
      if (husband && wife) {
        husband.spouseIds.push(wife.id);
        wife.spouseIds.push(husband.id);
      }
      for (const c of fam.children.filter((n) => n.tag === 'CHIL')) {
        const kid = tree.people[idByXref.get(c.value)];
        if (!kid || !parents.length) continue;
        if (kid.parentIds.length) {
          extraFamilies++;
          continue;
        }
        kid.parentIds = parents.map((x) => x.id);
      }
    }

    if (badDates) warnings.push(badDates + ' data(s) em formato não reconhecido foram guardadas nas observações.');
    if (extraFamilies) warnings.push(extraFamilies + ' pessoa(s) apareciam em mais de uma família como filho(a) (ex.: adoção); só a primeira foi mantida.');
    const clean = Model.sanitize(tree);
    if (Object.keys(clean.people).length !== Object.keys(tree.people).length) warnings.push('Algumas pessoas foram descartadas por dados inválidos.');
    return { tree: clean, warnings };
  }

  return { exportGedcom, importGedcom, parse, decode };
});
