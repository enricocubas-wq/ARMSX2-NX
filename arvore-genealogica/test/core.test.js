const test = require('node:test');
const assert = require('node:assert/strict');
const Cpf = require('../js/cpf.js');
const Dates = require('../js/dates.js');
const Model = require('../js/model.js');
const Gedcom = require('../js/gedcom.js');
const Research = require('../js/research.js');

const today = new Date(Date.UTC(2026, 9, 27));

test('CPF: valida dígitos verificadores', () => {
  assert.equal(Cpf.isValid('529.982.247-25'), true);
  assert.equal(Cpf.isValid('52998224725'), true);
  assert.equal(Cpf.isValid('529.982.247-26'), false);
  assert.equal(Cpf.isValid('111.111.111-11'), false);
  assert.equal(Cpf.isValid('123'), false);
  assert.equal(Cpf.isValid(''), false);
});

test('CPF: formata durante a digitação e mascara', () => {
  assert.equal(Cpf.format('5'), '5');
  assert.equal(Cpf.format('5299'), '529.9');
  assert.equal(Cpf.format('529982247'), '529.982.247');
  assert.equal(Cpf.format('5299822472'), '529.982.247-2');
  assert.equal(Cpf.format('52998224725999'), '529.982.247-25');
  assert.equal(Cpf.mask('529.982.247-25'), '•••.•••.•••-25');
  assert.equal(Cpf.mask('529'), '');
});

test('datas: aceita dd/mm/aaaa, mm/aaaa e aaaa', () => {
  assert.equal(Dates.parseBR('12/03/1980', today), '1980-03-12');
  assert.equal(Dates.parseBR('3/1980', today), '1980-03');
  assert.equal(Dates.parseBR('1980', today), '1980');
  assert.equal(Dates.parseBR('  ', today), '');
});

test('datas: rejeita inexistentes, futuras e mal formatadas', () => {
  for (const bad of ['31/02/2000', '00/01/2000', '13/2000', '1/1/27', 'abc', '01/01/2027', '11/2026', '0999', '20511961', '0205196', '020519611', '02/05/61', '02//1961x']) {
    assert.equal(Dates.parseBR(bad, today), null, bad);
  }
  assert.equal(Dates.parseBR('27/10/2026', today), '2026-10-27');
});

test('datas: aceita só números e outros separadores (teclado numérico do celular não tem "/")', () => {
  assert.equal(Dates.parseBR('02051961', today), '1961-05-02');
  assert.equal(Dates.parseBR('25021962', today), '1962-02-25');
  assert.equal(Dates.parseBR('051961', today), '1961-05');
  for (const typed of ['02.05.1961', '02,05,1961', '02-05-1961', '02 05 1961', '2/5/1961', '2.5.1961']) {
    assert.equal(Dates.parseBR(typed, today), '1961-05-02', typed);
  }
  assert.equal(Dates.parseBR('5,1961', today), '1961-05');
  assert.equal(Dates.parseBR('  02051961  ', today), '1961-05-02');
});

test('datas: normalizeBR formata o que entende e deixa o resto como está', () => {
  assert.equal(Dates.normalizeBR('02051961', today), '02/05/1961');
  assert.equal(Dates.normalizeBR('2.5.1961', today), '02/05/1961');
  assert.equal(Dates.normalizeBR('051961', today), '05/1961');
  assert.equal(Dates.normalizeBR('1961', today), '1961');
  assert.equal(Dates.normalizeBR('', today), '');
  assert.equal(Dates.normalizeBR('31/02/2000', today), '31/02/2000');
  assert.equal(Dates.normalizeBR('abc', today), 'abc');
});

test('datas: formata de volta e converte para GEDCOM', () => {
  assert.equal(Dates.formatBR('1980-03-12'), '12/03/1980');
  assert.equal(Dates.formatBR('1980-03'), '03/1980');
  assert.equal(Dates.formatBR('1980'), '1980');
  assert.equal(Dates.formatBR('lixo'), '');
  assert.equal(Dates.toGedcom('1980-03-02'), '2 MAR 1980');
  assert.equal(Dates.toGedcom('1980-03'), 'MAR 1980');
  assert.equal(Dates.fromGedcom('2 MAR 1980'), '1980-03-02');
  assert.equal(Dates.fromGedcom('ABT 1900'), '1900');
  assert.equal(Dates.fromGedcom('BET 1900 AND 1910'), '1900');
  assert.equal(Dates.fromGedcom('31 FEB 1900'), null);
  assert.equal(Dates.fromGedcom('Primavera de 1900'), null);
});

function familia() {
  const tree = Model.emptyTree();
  const eu = Model.createPerson(tree, { firstName: 'Enrico', lastName: 'Cubas', gender: 'M', birthDate: '1990-05-10', cpf: '529.982.247-25' });
  const pai = Model.addParent(tree, eu.id, { fields: { firstName: 'José', lastName: 'Cubas', gender: 'M' } });
  const mae = Model.addParent(tree, eu.id, { fields: { firstName: 'Maria', lastName: 'Souza', gender: 'F' } });
  return { tree, eu, pai, mae };
}

test('modelo: pais do mesmo filho viram casal e o filho entra na lista deles', () => {
  const { tree, eu, pai, mae } = familia();
  assert.deepEqual(eu.parentIds, [pai.id, mae.id]);
  assert.deepEqual(Model.spousesOf(tree, pai.id).map((p) => p.id), [mae.id]);
  assert.deepEqual(Model.childrenOf(tree, mae.id).map((p) => p.id), [eu.id]);
  assert.equal(tree.ownerId, eu.id);
});

test('modelo: não aceita um terceiro pai/mãe nem ciclos', () => {
  const { tree, eu, pai } = familia();
  assert.throws(() => Model.addParent(tree, eu.id, { fields: { firstName: 'X' } }), /dois pais/);
  const avo = Model.addParent(tree, pai.id, { fields: { firstName: 'Avô', gender: 'M' } });
  assert.throws(() => Model.addParent(tree, avo.id, { existingId: eu.id }), /ciclo/);
  assert.throws(() => Model.addChild(tree, eu.id, { existingId: avo.id }), /ciclo/);
  assert.equal(Object.keys(tree.people).length, 4);
});

test('modelo: irmãos compartilham os pais; sem pais não há irmãos', () => {
  const { tree, eu, pai, mae } = familia();
  const irma = Model.addSibling(tree, eu.id, { fields: { firstName: 'Ana', gender: 'F' } });
  assert.deepEqual(irma.parentIds, [pai.id, mae.id]);
  assert.deepEqual(Model.siblingsOf(tree, eu.id).map((p) => p.id), [irma.id]);
  assert.throws(() => Model.addSibling(tree, pai.id, { fields: { firstName: 'Tio' } }), /pai ou mãe/);
});

test('modelo: filho com cônjuge declarado cria o vínculo entre os dois', () => {
  const { tree, eu } = familia();
  const esposa = Model.addSpouse(tree, eu.id, { fields: { firstName: 'Paula', gender: 'F' } });
  const filho = Model.addChild(tree, eu.id, { fields: { firstName: 'Davi', gender: 'M' } }, esposa.id);
  assert.deepEqual(filho.parentIds, [eu.id, esposa.id]);
  assert.deepEqual(Model.spousesOf(tree, eu.id).map((p) => p.id), [esposa.id]);
});

test('modelo: addChild com falha não deixa pessoa pela metade', () => {
  const { tree, eu } = familia();
  const before = Object.keys(tree.people).length;
  assert.throws(() => Model.addChild(tree, eu.id, { fields: { firstName: 'Z' } }, eu.id));
  assert.equal(Object.keys(tree.people).length, before);
  const outra = Model.createPerson(tree, { firstName: 'Outra' });
  const existente = Model.createPerson(tree, { firstName: 'Existente' });
  Model.addChild(tree, outra.id, { existingId: existente.id });
  assert.throws(() => Model.addChild(tree, outra.id, { existingId: existente.id }, outra.id));
  assert.deepEqual(existente.parentIds, [outra.id]);
});

test('modelo: remover limpa todas as referências', () => {
  const { tree, eu, pai, mae } = familia();
  Model.removePerson(tree, pai.id);
  assert.deepEqual(eu.parentIds, [mae.id]);
  assert.deepEqual(mae.spouseIds, []);
  Model.removePerson(tree, eu.id);
  assert.notEqual(tree.ownerId, eu.id);
  assert.ok(tree.people[tree.ownerId]);
});

test('modelo: candidatos excluem a própria pessoa, descendentes e ancestrais conforme a relação', () => {
  const { tree, eu, pai, mae } = familia();
  const filho = Model.addChild(tree, eu.id, { fields: { firstName: 'Davi' } });
  const ids = (kind, id) => Model.candidates(tree, kind, id).map((p) => p.id);
  assert.ok(!ids('parent', eu.id).includes(filho.id));
  assert.ok(!ids('parent', eu.id).includes(eu.id));
  assert.ok(!ids('spouse', eu.id).includes(pai.id));
  assert.ok(!ids('child', eu.id).includes(mae.id));
  assert.deepEqual(ids('spouse', pai.id), [], 'mãe já é cônjuge; filho e neto são descendentes');
  assert.deepEqual(ids('child', pai.id), [], 'o filho do pai já está vinculado e o neto é descendente');
});

test('modelo: converte para o family-chart com relações simétricas', () => {
  const { tree, eu, pai, mae } = familia();
  const data = Model.toFamilyChart(tree);
  const by = Object.fromEntries(data.map((d) => [d.id, d]));
  assert.deepEqual(by[eu.id].rels.parents, [pai.id, mae.id]);
  assert.deepEqual(by[pai.id].rels.children, [eu.id]);
  assert.deepEqual(by[pai.id].rels.spouses, [mae.id]);
  assert.deepEqual(by[mae.id].rels.spouses, [pai.id]);
  assert.equal(by[eu.id].data['first name'], 'Enrico');
  assert.equal(by[eu.id].data.birthday, '10/05/1990');
  assert.equal(by[eu.id].data.isOwner, true);
  assert.equal('cpf' in by[eu.id].data, false, 'o CPF nunca vai para o gráfico');
});

test('sanitize: corrige tipos, referências soltas, simetria e ciclos', () => {
  const dirty = {
    ownerId: 'inexistente',
    people: {
      a: { id: 'a', firstName: 'A', gender: 'X', parentIds: ['b', 'ghost', 'a'], spouseIds: ['b', 'ghost'], birthDate: '2999-01-01' },
      b: { id: 'b', firstName: 42, parentIds: ['a'], spouseIds: [] },
      c: null,
    },
  };
  const tree = Model.sanitize(dirty);
  assert.equal(tree.people.a.gender, '');
  assert.equal(tree.people.a.birthDate, '');
  assert.equal(tree.people.b.firstName, '');
  assert.deepEqual(tree.people.a.spouseIds, ['b']);
  assert.deepEqual(tree.people.b.spouseIds, ['a']);
  const cyclic = tree.people.a.parentIds.includes('b') && tree.people.b.parentIds.includes('a');
  assert.equal(cyclic, false);
  assert.ok(tree.people[tree.ownerId]);
  assert.deepEqual(Model.sanitize(null).people, {});
});

test('GEDCOM: exporta estrutura válida e preserva relações ao reimportar', () => {
  const { tree, eu, pai, mae } = familia();
  const irma = Model.addSibling(tree, eu.id, { fields: { firstName: 'Ana', lastName: 'Cubas', gender: 'F', birthDate: '1993', birthPlace: 'Curitiba, PR' } });
  const esposa = Model.addSpouse(tree, eu.id, { fields: { firstName: 'Paula', gender: 'F' } });
  eu.notes = 'Primeira linha\nSegunda @ linha';
  const text = Gedcom.exportGedcom(tree);

  assert.match(text, /^0 HEAD\n/);
  assert.match(text, /\n1 GEDC\n2 VERS 5\.5\.1\n/);
  assert.match(text, /\n1 NAME Enrico \/Cubas\/\n/);
  assert.match(text, /\n1 BIRT\n2 DATE 10 MAY 1990\n/);
  assert.match(text, /\n2 PLAC Curitiba, PR\n/);
  assert.match(text, /\n1 NOTE Primeira linha\n2 CONT Segunda @@ linha\n/);
  assert.match(text, /\n0 TRLR\n$/);
  assert.equal(/529\.982/.test(text), false, 'CPF fora por padrão');
  assert.equal((text.match(/^0 @F\d+@ FAM$/gm) || []).length, 2, 'família dos pais + casal sem filhos');

  const { tree: back, warnings } = Gedcom.importGedcom(text);
  assert.deepEqual(warnings, []);
  const byName = (name) => Object.values(back.people).find((p) => Model.displayName(p) === name);
  const eu2 = byName('Enrico Cubas');
  assert.equal(eu2.birthDate, '1990-05-10');
  assert.equal(eu2.notes, 'Primeira linha\nSegunda @ linha');
  assert.equal(back.ownerId, eu2.id);
  assert.deepEqual(eu2.parentIds.map((id) => Model.displayName(back.people[id])).sort(), ['José Cubas', 'Maria Souza']);
  assert.equal(byName('Ana Cubas').birthDate, '1993');
  assert.equal(byName('Ana Cubas').birthPlace, 'Curitiba, PR');
  assert.deepEqual(Model.siblingsOf(back, eu2.id).map(Model.displayName), ['Ana Cubas']);
  assert.deepEqual(Model.spousesOf(back, eu2.id).map(Model.displayName), ['Paula']);
  assert.equal(Object.keys(back.people).length, Object.keys(tree.people).length);
  assert.ok(pai && mae && irma && esposa);
});

test('GEDCOM: CPF só sai se for pedido, e volta ao importar', () => {
  const { tree } = familia();
  const text = Gedcom.exportGedcom(tree, { includeCpf: true });
  assert.match(text, /\n1 IDNO 529\.982\.247-25\n2 TYPE CPF\n/);
  const { tree: back } = Gedcom.importGedcom(text);
  assert.equal(Object.values(back.people).find((p) => p.firstName === 'Enrico').cpf, '529.982.247-25');
});

test('GEDCOM: notas longas viram CONC e voltam inteiras', () => {
  const { tree, eu } = familia();
  eu.notes = 'x'.repeat(450) + '\n\n' + 'y'.repeat(10);
  const text = Gedcom.exportGedcom(tree);
  assert.ok(text.split('\n').every((l) => l.length <= 210));
  const { tree: back } = Gedcom.importGedcom(text);
  assert.equal(Object.values(back.people).find((p) => p.firstName === 'Enrico').notes, eu.notes);
});

test('GEDCOM: lê arquivos de outros programas (CRLF, BOM, datas aproximadas, adoção)', () => {
  const ged = [
    '﻿0 HEAD', '1 SOUR Gramps', '1 GEDC', '2 VERS 5.5.1', '1 CHAR UTF-8',
    '0 @I1@ INDI', '1 NAME Maria /da Silva/ Jr', '1 SEX F', '1 BIRT', '2 DATE ABT 1950', '2 PLAC Recife',
    '1 DEAT', '2 DATE Primavera de 2001', '1 NOTE @N1@', '1 FAMS @F1@',
    '0 @I2@ INDI', '1 NAME João /Pereira/', '1 SEX M', '1 FAMS @F1@', '1 FAMS @F2@',
    '0 @I3@ INDI', '1 NAME Lia //', '1 FAMC @F1@', '1 FAMC @F2@',
    '0 @I4@ INDI', '1 NAME Adotante /X/', '1 SEX F', '1 FAMS @F2@',
    '0 @N1@ NOTE Nota separada', '1 CONC  continua',
    '0 @F1@ FAM', '1 HUSB @I2@', '1 WIFE @I1@', '1 CHIL @I3@',
    '0 @F2@ FAM', '1 HUSB @I2@', '1 WIFE @I4@', '1 CHIL @I3@',
    '0 TRLR',
  ].join('\r\n');
  const { tree, warnings } = Gedcom.importGedcom(ged);
  const byFirst = (name) => Object.values(tree.people).find((p) => p.firstName === name);
  const maria = byFirst('Maria');
  assert.equal(maria.lastName, 'da Silva Jr');
  assert.equal(maria.birthDate, '1950');
  assert.equal(maria.deathDate, '');
  assert.match(maria.notes, /Falecimento \(data original\): Primavera de 2001/);
  assert.match(maria.notes, /Nota separada continua/);
  const lia = byFirst('Lia');
  assert.deepEqual(lia.parentIds.map((id) => tree.people[id].firstName).sort(), ['João', 'Maria']);
  assert.equal(warnings.length, 2);
  assert.equal(tree.ownerId, maria.id);
});

test('GEDCOM: rejeita arquivos que não são GEDCOM', () => {
  assert.throws(() => Gedcom.importGedcom('olá, isto é um texto qualquer'), /não parece ser um GEDCOM/);
  assert.throws(() => Gedcom.importGedcom('{"people":{}}'), /não parece ser um GEDCOM/);
});

test('GEDCOM: decodifica UTF-8, UTF-16 e Windows-1252', () => {
  const utf8 = Buffer.from('0 HEAD\n1 NAME José\n', 'utf8');
  assert.match(Gedcom.decode(utf8), /José/);
  const latin1 = Buffer.from('0 HEAD\n1 NAME Jos\xe9\n', 'latin1');
  assert.match(Gedcom.decode(latin1), /José/);
  const utf16 = Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from('0 HEAD\n1 NAME José\n', 'utf16le')]);
  assert.match(Gedcom.decode(utf16), /José/);
});

test('GEDCOM: nomes com tags HTML são só texto (a interface é que precisa escapar)', () => {
  const ged = '0 HEAD\n0 @I1@ INDI\n1 NAME <img src=x onerror=alert(1)> /Teste/\n0 TRLR\n';
  const { tree } = Gedcom.importGedcom(ged);
  // continua sendo texto, nada é removido nem interpretado (a capitalização automática só muda as iniciais)
  assert.match(Object.values(tree.people)[0].firstName, /^<img src=x onerror=alert\(1\)>$/i);
});

test('nomes: texto todo em minúsculas ganha iniciais maiúsculas; o resto fica como a pessoa escreveu', () => {
  assert.equal(Model.smartCase('ana maria souza'), 'Ana Maria Souza');
  assert.equal(Model.smartCase('maria da silva dos santos'), 'Maria da Silva dos Santos');
  assert.equal(Model.smartCase('João'), 'João');
  assert.equal(Model.smartCase('MARIA'), 'MARIA');
  assert.equal(Model.smartCase('McDonald'), 'McDonald');
  assert.equal(Model.smartCase(''), '');
  assert.equal(Model.smartPlace('santos, sp'), 'Santos, SP');
  assert.equal(Model.smartPlace('Santos, sp'), 'Santos, SP');
  assert.equal(Model.smartPlace('são paulo'), 'São Paulo');
  assert.equal(Model.smartPlace('Santos, SP'), 'Santos, SP');
});

test('sanitize: arruma nomes em minúsculas que já estavam salvos', () => {
  const tree = Model.sanitize({ ownerId: 'a', people: { a: { id: 'a', firstName: 'ana', lastName: 'de souza lima', birthPlace: 'recife, pe' } } });
  assert.equal(tree.people.a.firstName, 'Ana');
  assert.equal(tree.people.a.lastName, 'De Souza Lima');
  assert.equal(tree.people.a.birthPlace, 'Recife, PE');
});

test('frontier: quem ainda não tem pais cadastrados, do mais próximo ao mais distante', () => {
  const tree = Model.emptyTree();
  const eu = Model.createPerson(tree, { firstName: 'Eu', gender: 'F' });
  assert.deepEqual(Model.frontier(tree).map((f) => [f.person.id, f.generation, f.missing]), [[eu.id, 0, 2]]);
  const pai = Model.addParent(tree, eu.id, { fields: { firstName: 'Pai', gender: 'M' } });
  const mae = Model.addParent(tree, eu.id, { fields: { firstName: 'Mãe', gender: 'F' } });
  assert.deepEqual(Model.frontier(tree).map((f) => [f.person.firstName, f.generation, f.missing]), [['Pai', 1, 2], ['Mãe', 1, 2]]);
  Model.addParent(tree, pai.id, { fields: { firstName: 'Avô', gender: 'M' } });
  const rows = Model.frontier(tree).map((f) => [f.person.firstName, f.generation, f.missing]);
  assert.deepEqual(rows, [['Pai', 1, 1], ['Mãe', 1, 2], ['Avô', 2, 2]]);
  assert.ok(mae);
});

test('pesquisa: links só para sites conhecidos, com os dados codificados na URL', () => {
  const pessoa = { firstName: 'João', lastName: 'da Silva Sá', gender: 'M', birthDate: '1930-05-02', birthPlace: 'São Paulo, SP', deathDate: '' };
  const pai = { firstName: 'José', lastName: 'Silva', gender: 'M' };
  const mae = { firstName: 'Maria', lastName: 'Sá', gender: 'F' };
  const links = Research.links(pessoa, [pai, mae]);
  const hosts = new Set(['www.familysearch.org', 'www.findagrave.com', 'www.wikitree.com', 'antenati.cultura.gov.it', 'museudaimigracao.org.br']);
  for (const l of links) {
    const u = new URL(l.url);
    assert.equal(u.protocol, 'https:');
    assert.ok(hosts.has(u.hostname), u.hostname);
  }
  const fs = new URL(links.find((l) => l.id === 'familysearch').url);
  assert.equal(fs.pathname, '/search/record/results');
  assert.equal(fs.searchParams.get('q.givenName'), 'João');
  assert.equal(fs.searchParams.get('q.surname'), 'da Silva Sá');
  assert.equal(fs.searchParams.get('q.birthLikeDate.from'), '1928');
  assert.equal(fs.searchParams.get('q.birthLikeDate.to'), '1932');
  assert.equal(fs.searchParams.get('q.birthLikePlace'), 'São Paulo, SP');
  assert.equal(fs.searchParams.get('q.fatherGivenName'), 'José');
  assert.equal(fs.searchParams.get('q.motherSurname'), 'Sá');
  assert.ok(links.find((l) => l.id === 'familysearch').url.includes('Jo%C3%A3o'), 'acentos vão codificados em UTF-8');
  const grave = new URL(links.find((l) => l.id === 'findagrave').url);
  assert.equal(grave.searchParams.get('lastname'), 'Sá');
  assert.equal(grave.searchParams.get('birthyear'), '1930');
  const wiki = links.filter((l) => l.id.startsWith('wikitree')).map((l) => new URL(l.url).pathname);
  assert.deepEqual(wiki, ['/genealogy/SILVA', '/genealogy/SA'], 'sem partículas e sem acentos');
});

test('pesquisa: sem nome não há links; sem dados não manda parâmetros vazios nem inventa', () => {
  assert.deepEqual(Research.links({ firstName: '', lastName: 'Silva' }, []), []);
  const so = Research.links({ firstName: 'Ana', lastName: '' }, []);
  const u = new URL(so.find((l) => l.id === 'familysearch').url);
  assert.deepEqual([...u.searchParams.keys()], ['q.givenName']);
  assert.equal(so.filter((l) => l.id.startsWith('wikitree')).length, 0);
});
