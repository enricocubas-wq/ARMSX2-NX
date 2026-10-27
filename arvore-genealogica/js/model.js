(function (root, factory) {
  const api = factory(typeof require === 'function' ? require('./dates.js') : root.Arvore.Dates);
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.Arvore = Object.assign(root.Arvore || {}, { Model: api });
})(typeof self !== 'undefined' ? self : this, function (Dates) {
  // Cada pessoa guarda só os vínculos "para cima" (parentIds, no máximo 2) e os cônjuges
  // declarados. Filhos e irmãos são sempre calculados a partir daí, então não há como
  // os dois lados de uma relação ficarem inconsistentes.
  const MAX_TEXT = 500;

  function emptyTree() {
    return { version: 1, ownerId: null, people: {} };
  }

  function blankPerson(id, fields) {
    return Object.assign(
      { id, firstName: '', lastName: '', gender: '', birthDate: '', birthPlace: '', deathDate: '', cpf: '', notes: '', parentIds: [], spouseIds: [] },
      fields
    );
  }

  function newId(tree) {
    let id;
    do id = 'p' + Math.random().toString(36).slice(2, 9);
    while (tree.people[id]);
    return id;
  }

  function createPerson(tree, fields) {
    const person = blankPerson(newId(tree), fields);
    person.parentIds = [];
    person.spouseIds = [];
    tree.people[person.id] = person;
    if (!tree.ownerId) tree.ownerId = person.id;
    return person;
  }

  function get(tree, id) {
    const person = tree.people[id];
    if (!person) throw new Error('Pessoa não encontrada.');
    return person;
  }

  function displayName(person) {
    return [person.firstName, person.lastName].filter(Boolean).join(' ') || 'Sem nome';
  }

  function updatePerson(tree, id, fields) {
    const person = get(tree, id);
    for (const key of ['firstName', 'lastName', 'gender', 'birthDate', 'birthPlace', 'deathDate', 'cpf', 'notes']) {
      if (key in fields) person[key] = fields[key];
    }
    return person;
  }

  function childrenOf(tree, id) {
    return Object.values(tree.people).filter((p) => p.parentIds.includes(id));
  }

  function spousesOf(tree, id) {
    const ids = new Set(get(tree, id).spouseIds);
    for (const child of childrenOf(tree, id)) child.parentIds.forEach((pid) => pid !== id && ids.add(pid));
    return [...ids].filter((sid) => tree.people[sid]).map((sid) => tree.people[sid]);
  }

  function siblingsOf(tree, id) {
    const parents = get(tree, id).parentIds;
    if (!parents.length) return [];
    return Object.values(tree.people).filter((p) => p.id !== id && p.parentIds.some((pid) => parents.includes(pid)));
  }

  function ancestorIds(tree, id) {
    const seen = new Set();
    const stack = [...get(tree, id).parentIds];
    while (stack.length) {
      const current = stack.pop();
      if (seen.has(current) || !tree.people[current]) continue;
      seen.add(current);
      stack.push(...tree.people[current].parentIds);
    }
    return seen;
  }

  function descendantIds(tree, id) {
    const seen = new Set();
    const stack = [id];
    while (stack.length) {
      const current = stack.pop();
      for (const child of childrenOf(tree, current)) {
        if (!seen.has(child.id)) {
          seen.add(child.id);
          stack.push(child.id);
        }
      }
    }
    return seen;
  }

  // Ordem usada para exibir/exportar um casal: homem primeiro, quando o gênero é conhecido.
  function coupleOrder(a, b) {
    if (a.gender === 'F' && b.gender !== 'F') return [b, a];
    if (b.gender === 'M' && a.gender !== 'M') return [b, a];
    return [a, b];
  }

  function linkSpouses(tree, aId, bId) {
    if (aId === bId) throw new Error('Uma pessoa não pode ser cônjuge de si mesma.');
    const a = get(tree, aId);
    const b = get(tree, bId);
    if (!a.spouseIds.includes(bId)) a.spouseIds.push(bId);
    if (!b.spouseIds.includes(aId)) b.spouseIds.push(aId);
  }

  function linkParent(tree, childId, parentId) {
    const child = get(tree, childId);
    const parent = get(tree, parentId);
    if (child.parentIds.includes(parentId)) throw new Error(displayName(parent) + ' já é pai/mãe de ' + displayName(child) + '.');
    if (child.parentIds.length >= 2) throw new Error(displayName(child) + ' já tem dois pais/mães cadastrados.');
    if (childId === parentId || ancestorIds(tree, parentId).has(childId)) {
      throw new Error('Esse vínculo criaria um ciclo na árvore (alguém seria ancestral de si mesmo).');
    }
    child.parentIds.push(parentId);
  }

  // `spec` é { existingId } para vincular alguém já cadastrado, ou { fields } para criar uma pessoa nova.
  function resolve(tree, spec, defaults) {
    if (spec.existingId) return get(tree, spec.existingId);
    return createPerson(tree, Object.assign({}, defaults, spec.fields));
  }

  function addParent(tree, childId, spec) {
    const child = get(tree, childId);
    if (child.parentIds.length >= 2) throw new Error(displayName(child) + ' já tem dois pais/mães cadastrados.');
    const parent = resolve(tree, spec, {});
    try {
      linkParent(tree, childId, parent.id);
    } catch (err) {
      if (!spec.existingId) delete tree.people[parent.id];
      throw err;
    }
    // Pais do mesmo filho formam um casal.
    const other = child.parentIds.find((pid) => pid !== parent.id);
    if (other) linkSpouses(tree, parent.id, other);
    return parent;
  }

  function addSpouse(tree, personId, spec) {
    get(tree, personId);
    const spouse = resolve(tree, spec, {});
    try {
      linkSpouses(tree, personId, spouse.id);
    } catch (err) {
      if (!spec.existingId) delete tree.people[spouse.id];
      throw err;
    }
    return spouse;
  }

  function addChild(tree, parentId, spec, otherParentId) {
    get(tree, parentId);
    if (otherParentId) get(tree, otherParentId);
    const child = resolve(tree, spec, {});
    const parentsBefore = [...child.parentIds];
    try {
      linkParent(tree, child.id, parentId);
      if (otherParentId) {
        linkParent(tree, child.id, otherParentId);
        linkSpouses(tree, parentId, otherParentId);
      }
    } catch (err) {
      if (!spec.existingId) delete tree.people[child.id];
      else child.parentIds = parentsBefore;
      throw err;
    }
    return child;
  }

  function addSibling(tree, personId, spec) {
    const person = get(tree, personId);
    if (!person.parentIds.length) throw new Error('Cadastre ao menos um pai ou mãe antes de adicionar irmãos.');
    const sibling = createPerson(tree, spec.fields);
    sibling.parentIds = [...person.parentIds];
    return sibling;
  }

  function removePerson(tree, id) {
    get(tree, id);
    delete tree.people[id];
    for (const p of Object.values(tree.people)) {
      p.parentIds = p.parentIds.filter((pid) => pid !== id);
      p.spouseIds = p.spouseIds.filter((sid) => sid !== id);
    }
    if (tree.ownerId === id) tree.ownerId = Object.keys(tree.people)[0] || null;
  }

  // Quem pode ser vinculado a `personId` na relação `kind`, sem criar ciclos nem duplicar vínculos.
  function candidates(tree, kind, personId) {
    const person = get(tree, personId);
    const ancestors = ancestorIds(tree, personId);
    const descendants = descendantIds(tree, personId);
    const spouses = new Set(spousesOf(tree, personId).map((s) => s.id));
    return Object.values(tree.people)
      .filter((p) => {
        if (p.id === personId) return false;
        if (kind === 'parent') return !person.parentIds.includes(p.id) && !descendants.has(p.id) && !spouses.has(p.id);
        if (kind === 'spouse') return !spouses.has(p.id) && !ancestors.has(p.id) && !descendants.has(p.id);
        if (kind === 'child') {
          return p.parentIds.length < 2 && !ancestors.has(p.id) && !descendants.has(p.id) && !spouses.has(p.id);
        }
        return false;
      })
      .sort((a, b) => displayName(a).localeCompare(displayName(b), 'pt-BR'));
  }

  // Converte para o formato do family-chart (https://github.com/donatso/family-chart).
  function toFamilyChart(tree) {
    const people = Object.values(tree.people);
    return people.map((p) => {
      const parents = p.parentIds.map((id) => tree.people[id]).filter(Boolean);
      const ordered = parents.length === 2 ? coupleOrder(parents[0], parents[1]) : parents;
      return {
        id: p.id,
        data: {
          gender: p.gender === 'F' ? 'F' : 'M',
          'first name': p.firstName,
          'last name': p.lastName,
          birthday: Dates.formatBR(p.birthDate),
          deathday: Dates.formatBR(p.deathDate),
          birthYear: Dates.year(p.birthDate),
          deathYear: Dates.year(p.deathDate),
          unknownGender: p.gender === '',
          isOwner: tree.ownerId === p.id,
        },
        rels: {
          parents: ordered.map((x) => x.id),
          spouses: spousesOf(tree, p.id).map((x) => x.id),
          children: childrenOf(tree, p.id).map((x) => x.id),
        },
      };
    });
  }

  // Quem digita no celular costuma usar só minúsculas ("enrico chinellato prado", "santos, sp").
  // Só mexe em texto 100% minúsculo; o que já tem maiúscula fica como a pessoa escreveu.
  const NAME_PARTICLES = new Set(['da', 'de', 'do', 'das', 'dos', 'e', 'di', 'du', 'del', 'della', 'van', 'von']);

  function smartCase(text) {
    const t = String(text == null ? '' : text);
    if (!t || t !== t.toLowerCase()) return t;
    return t.replace(/\p{L}+/gu, (word, at) => (at > 0 && NAME_PARTICLES.has(word) ? word : word.charAt(0).toUpperCase() + word.slice(1)));
  }

  // Além da regra acima, a sigla depois da última vírgula ("Santos, sp") vira maiúscula.
  function smartPlace(text) {
    return smartCase(text).replace(/,\s*(\p{L}{2})\s*$/u, (all, uf) => ', ' + uf.toUpperCase());
  }

  function clip(value) {
    return typeof value === 'string' ? value.slice(0, MAX_TEXT) : '';
  }

  function cleanDate(value) {
    return Dates.parseBR(Dates.formatBR(value)) || '';
  }

  // Valida e limpa uma árvore vinda de fora (arquivo JSON, GEDCOM): tipos, referências soltas,
  // vínculos simétricos e ciclos. Um ciclo quebraria o desenho da árvore.
  function sanitize(input) {
    const tree = emptyTree();
    const source = input && typeof input === 'object' && input.people && typeof input.people === 'object' ? input.people : {};
    for (const [key, raw] of Object.entries(source)) {
      if (!raw || typeof raw !== 'object') continue;
      const id = String(raw.id || key).slice(0, 64);
      tree.people[id] = blankPerson(id, {
        firstName: smartCase(clip(raw.firstName)),
        lastName: smartCase(clip(raw.lastName)),
        gender: raw.gender === 'M' || raw.gender === 'F' ? raw.gender : '',
        birthDate: cleanDate(raw.birthDate),
        birthPlace: smartPlace(clip(raw.birthPlace)),
        deathDate: cleanDate(raw.deathDate),
        cpf: clip(raw.cpf),
        notes: clip(raw.notes),
        parentIds: Array.isArray(raw.parentIds) ? [...new Set(raw.parentIds.map(String))] : [],
        spouseIds: Array.isArray(raw.spouseIds) ? [...new Set(raw.spouseIds.map(String))] : [],
      });
    }
    for (const p of Object.values(tree.people)) {
      p.spouseIds = p.spouseIds.filter((sid) => sid !== p.id && tree.people[sid]);
      const kept = [];
      for (const pid of p.parentIds) {
        if (kept.length < 2 && tree.people[pid] && pid !== p.id && !ancestorIds(tree, pid).has(p.id)) kept.push(pid);
      }
      p.parentIds = kept;
    }
    for (const p of Object.values(tree.people)) p.spouseIds.forEach((sid) => linkSpouses(tree, p.id, sid));
    const ids = Object.keys(tree.people);
    tree.ownerId = input && tree.people[input.ownerId] ? input.ownerId : ids[0] || null;
    return tree;
  }

  // Pessoas da ascendência de quem criou a árvore que ainda não têm os dois pais cadastrados,
  // das mais próximas às mais distantes: são os próximos nomes a descobrir.
  function frontier(tree) {
    const owner = tree.people[tree.ownerId];
    if (!owner) return [];
    const result = [];
    const seen = new Set([owner.id]);
    let level = [owner];
    for (let generation = 0; level.length; generation++) {
      const next = [];
      for (const person of level) {
        if (person.parentIds.length < 2) result.push({ person, generation, missing: 2 - person.parentIds.length });
        for (const pid of person.parentIds) {
          if (tree.people[pid] && !seen.has(pid)) {
            seen.add(pid);
            next.push(tree.people[pid]);
          }
        }
      }
      level = next;
    }
    return result;
  }

  return {
    smartCase, smartPlace, frontier,
    emptyTree, createPerson, updatePerson, removePerson, displayName,
    addParent, addSpouse, addChild, addSibling,
    childrenOf, spousesOf, siblingsOf, ancestorIds, descendantIds, coupleOrder,
    candidates, toFamilyChart, sanitize,
  };
});
