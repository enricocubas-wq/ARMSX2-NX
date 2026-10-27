(function (root, factory) {
  const api = factory(typeof require === 'function' ? require('./dates.js') : root.Arvore.Dates);
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.Arvore = Object.assign(root.Arvore || {}, { Research: api });
})(typeof self !== 'undefined' ? self : this, function (Dates) {
  // Links de busca em fontes abertas, já preenchidos com os dados da pessoa.
  // O app não consulta nada sozinho: quem abre o link (e só então) manda nome e ano para o site escolhido.
  const PARTICLES = new Set(['da', 'de', 'do', 'das', 'dos', 'e', 'di', 'du', 'del', 'della', 'van', 'von']);

  const words = (text) => String(text || '').trim().split(/\s+/).filter(Boolean);
  const plain = (text) => text.normalize('NFD').replace(/[̀-ͯ]/g, '');

  // Sobrenomes de verdade, sem partículas ("da Silva" -> "Silva").
  function surnames(lastName) {
    return words(lastName).filter((w) => !PARTICLES.has(w.toLowerCase()));
  }

  function nameOf(person) {
    return [person.firstName, person.lastName].filter(Boolean).join(' ');
  }

  // Formato dos parâmetros q.* documentado em developers.familysearch.org (Query Terms).
  function familySearchUrl(person, parents) {
    const q = new URLSearchParams();
    if (person.firstName) q.set('q.givenName', person.firstName);
    if (person.lastName) q.set('q.surname', person.lastName);
    const year = Number(Dates.year(person.birthDate));
    if (year) {
      q.set('q.birthLikeDate.from', String(year - 2));
      q.set('q.birthLikeDate.to', String(year + 2));
    }
    if (person.birthPlace) q.set('q.birthLikePlace', person.birthPlace);
    const father = parents.find((p) => p.gender === 'M');
    const mother = parents.find((p) => p.gender === 'F');
    if (father && father.firstName) q.set('q.fatherGivenName', father.firstName);
    if (father && father.lastName) q.set('q.fatherSurname', father.lastName);
    if (mother && mother.firstName) q.set('q.motherGivenName', mother.firstName);
    if (mother && mother.lastName) q.set('q.motherSurname', mother.lastName);
    return 'https://www.familysearch.org/search/record/results?' + q.toString();
  }

  function findAGraveUrl(person) {
    const q = new URLSearchParams();
    if (person.firstName) q.set('firstname', words(person.firstName)[0]);
    const last = surnames(person.lastName).pop();
    if (last) q.set('lastname', last);
    const year = Dates.year(person.birthDate);
    if (year) q.set('birthyear', year);
    return 'https://www.findagrave.com/memorial/search?' + q.toString();
  }

  // Devolve [{ id, label, url, hint }]. `parents` são as pessoas já cadastradas como pai/mãe de `person`.
  function links(person, parents = []) {
    if (!person || !person.firstName) return [];
    const out = [
      {
        id: 'familysearch',
        label: 'FamilySearch: registros históricos',
        url: familySearchUrl(person, parents),
        hint: 'Gratuito, pede cadastro. Tem registros civis, de igreja e de imigração do Brasil e da Itália.',
      },
      {
        id: 'findagrave',
        label: 'Find a Grave: sepultamentos',
        url: findAGraveUrl(person),
        hint: 'Útil para pessoas já falecidas: datas e lugares em lápides.',
      },
    ];
    for (const surname of surnames(person.lastName).slice(0, 2)) {
      out.push({
        id: 'wikitree-' + plain(surname).toLowerCase(),
        label: 'WikiTree: famílias ' + surname,
        url: 'https://www.wikitree.com/genealogy/' + encodeURIComponent(plain(surname).toUpperCase()),
        hint: 'Árvores abertas e colaborativas. Só mostra pessoas falecidas.',
      });
    }
    out.push(
      {
        id: 'antenati',
        label: 'Antenati: registros civis da Itália',
        url: 'https://antenati.cultura.gov.it/',
        hint: 'Gratuito. Em "Cerca per nome", digite: ' + nameOf(person) + '. Serve se a família veio da Itália.',
      },
      {
        id: 'museu-imigracao',
        label: 'Museu da Imigração (SP): chegadas por Santos',
        url: 'https://museudaimigracao.org.br/',
        hint: 'Gratuito. Busque pelo nome completo: ' + nameOf(person) + '. Cobre chegadas de 1882 a 1973.',
      }
    );
    return out;
  }

  return { links, surnames, familySearchUrl, findAGraveUrl };
});
