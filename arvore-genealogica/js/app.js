(function () {
  'use strict';
  const { Model, Gedcom, Cpf, Dates } = window.Arvore;

  const STORAGE_KEY = 'arvore-genealogica:v1';
  const MAX_FILE_BYTES = 25 * 1024 * 1024;

  const $ = (selector) => document.querySelector(selector);
  const esc = (value) =>
    String(value == null ? '' : value).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

  let tree = loadTree();
  let selectedId = tree.ownerId;
  let revealCpf = false;
  let chart = null;
  let dialogState = null;
  let statusTimer = null;

  // ---------- armazenamento (só neste navegador) ----------

  function loadTree() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      return raw ? Model.sanitize(JSON.parse(raw)) : Model.emptyTree();
    } catch (err) {
      return Model.emptyTree();
    }
  }

  function saveTree() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(tree));
    } catch (err) {
      notify('Não consegui salvar neste navegador (armazenamento bloqueado ou cheio). Use "Backup" para não perder os dados.', true);
    }
  }

  function notify(message, isError) {
    const el = $('#status');
    el.textContent = message;
    el.hidden = false;
    el.classList.toggle('error', Boolean(isError));
    clearTimeout(statusTimer);
    statusTimer = setTimeout(() => { el.hidden = true; }, isError ? 12000 : 7000);
  }

  // ---------- desenho ----------

  function render() {
    const hasPeople = Object.keys(tree.people).length > 0;
    $('#welcome').hidden = hasPeople;
    $('#workspace').hidden = !hasPeople;
    $('#toolbar').hidden = !hasPeople;
    if (!hasPeople) return;
    if (!tree.people[selectedId]) selectedId = tree.ownerId;
    renderChart();
    renderPanel();
  }

  // O family-chart insere o HTML do cartão sem tratar o texto, então tudo aqui passa por esc().
  function cardHtml(d) {
    const x = d.data.data;
    const name = [x['first name'], x['last name']].filter(Boolean).join(' ') || 'Sem nome';
    const life = x.birthYear || x.deathYear ? (x.birthYear || '?') + (x.deathYear ? ' – ' + x.deathYear : '') : '';
    return (
      '<div class="card-inner ap-card' + (x.unknownGender ? ' ap-unknown' : '') + '">' +
      (x.isOwner ? '<span class="ap-badge">você</span>' : '') +
      '<div class="ap-name">' + esc(name) + '</div>' +
      (life ? '<div class="ap-life">' + esc(life) + '</div>' : '') +
      '</div>'
    );
  }

  function renderChart() {
    const host = $('#chart');
    host.replaceChildren();
    chart = window.f3
      .createChart(host, Model.toFamilyChart(tree))
      .setTransitionTime(350)
      .setCardXSpacing(230)
      .setCardYSpacing(130)
      .setOrientationVertical()
      .setShowSiblingsOfMain(true);
    chart.setCardHtml().setCardInnerHtmlCreator(cardHtml).setOnCardClick((event, d) => select(d.data.id));
    chart.updateMainId(selectedId);
    chart.updateTree({ initial: true, tree_position: 'fit' });
  }

  function select(id) {
    if (!tree.people[id]) return;
    selectedId = id;
    revealCpf = false;
    chart.updateMainId(id);
    chart.updateTree({});
    renderPanel();
  }

  function renderPanel() {
    const person = tree.people[selectedId];
    const panel = $('#panel');
    if (!person) {
      panel.replaceChildren();
      return;
    }
    const links = (list) =>
      list.length
        ? list.map((p) => '<button type="button" class="linklike" data-select="' + esc(p.id) + '">' + esc(Model.displayName(p)) + '</button>').join(', ')
        : '<span class="none">nenhum cadastrado</span>';
    const rows = [];
    const row = (label, html) => html && rows.push('<div><dt>' + label + '</dt><dd>' + html + '</dd></div>');

    row('Nascimento', esc([Dates.formatBR(person.birthDate), person.birthPlace].filter(Boolean).join(' · ')));
    row('Falecimento', esc(Dates.formatBR(person.deathDate)));
    if (person.cpf) {
      const shown = revealCpf ? Cpf.format(person.cpf) : Cpf.mask(person.cpf) || '•••';
      row('CPF', esc(shown) + ' <button type="button" class="linklike" data-action="toggle-cpf">' + (revealCpf ? 'ocultar' : 'mostrar') + '</button>');
    }
    row('Observações', esc(person.notes).replace(/\n/g, '<br>'));

    const everyone = Object.values(tree.people).sort((a, b) => Model.displayName(a).localeCompare(Model.displayName(b), 'pt-BR'));
    const jump = everyone
      .map((p) => '<option value="' + esc(p.id) + '"' + (p.id === selectedId ? ' selected' : '') + '>' + esc(Model.displayName(p)) + '</option>')
      .join('');

    panel.innerHTML =
      '<label class="jump">Ir para <select id="jump">' + jump + '</select></label>' +
      '<h2>' + esc(Model.displayName(person)) + (tree.ownerId === person.id ? ' <span class="ap-badge inline">você</span>' : '') + '</h2>' +
      (rows.length ? '<dl>' + rows.join('') + '</dl>' : '<p class="none">Sem mais detalhes. Use "Editar" para completar.</p>') +
      '<dl class="rels">' +
      '<div><dt>Pais</dt><dd>' + links(person.parentIds.map((id) => tree.people[id]).filter(Boolean)) + '</dd></div>' +
      '<div><dt>Cônjuges</dt><dd>' + links(Model.spousesOf(tree, person.id)) + '</dd></div>' +
      '<div><dt>Filhos</dt><dd>' + links(Model.childrenOf(tree, person.id)) + '</dd></div>' +
      '<div><dt>Irmãos</dt><dd>' + links(Model.siblingsOf(tree, person.id)) + '</dd></div>' +
      '</dl>' +
      '<div class="actions">' +
      '<button type="button" data-action="edit">Editar</button>' +
      (person.parentIds.length < 2 ? '<button type="button" data-action="add-parent">+ Pai ou mãe</button>' : '') +
      '<button type="button" data-action="add-spouse">+ Cônjuge</button>' +
      '<button type="button" data-action="add-child">+ Filho(a)</button>' +
      (person.parentIds.length ? '<button type="button" data-action="add-sibling">+ Irmão(ã)</button>' : '') +
      (tree.ownerId !== person.id ? '<button type="button" data-action="make-owner">Sou eu</button>' : '') +
      '<button type="button" data-action="delete" class="danger">Excluir</button>' +
      '</div>';
  }

  // ---------- leitura e validação de formulários ----------

  function readPerson(form, prefix) {
    const get = (name) => {
      const field = form.elements[prefix + name];
      return field ? String(field.value || '').trim() : '';
    };
    return {
      firstName: get('firstName'), lastName: get('lastName'), gender: get('gender'), birthDate: get('birthDate'),
      birthPlace: get('birthPlace'), deathDate: get('deathDate'), cpf: get('cpf'), notes: get('notes'),
    };
  }

  function validatePerson(raw, ignoreId, who) {
    const errors = [];
    const fail = (name, message) => errors.push({ name, message: (who ? who + ': ' : '') + message });
    const fields = { firstName: raw.firstName, lastName: raw.lastName, gender: raw.gender, birthPlace: raw.birthPlace, notes: raw.notes, birthDate: '', deathDate: '', cpf: '' };

    if (!raw.firstName) fail('firstName', 'informe o nome.');
    const birth = Dates.parseBR(raw.birthDate);
    const death = Dates.parseBR(raw.deathDate);
    if (birth === null) fail('birthDate', 'data de nascimento inválida. Use dd/mm/aaaa, mm/aaaa ou só o ano (não pode ser no futuro).');
    else fields.birthDate = birth;
    if (death === null) fail('deathDate', 'data de falecimento inválida. Use dd/mm/aaaa, mm/aaaa ou só o ano (não pode ser no futuro).');
    else fields.deathDate = death;
    if (birth && death) {
      const n = Math.min(birth.length, death.length);
      if (death.slice(0, n) < birth.slice(0, n)) fail('deathDate', 'o falecimento não pode ser anterior ao nascimento.');
    }
    if (raw.cpf) {
      const digits = Cpf.digits(raw.cpf);
      const owner = Object.values(tree.people).find((p) => p.id !== ignoreId && Cpf.digits(p.cpf) === digits);
      if (!Cpf.isValid(raw.cpf)) fail('cpf', 'CPF inválido. Confira os 11 dígitos ou deixe em branco.');
      else if (owner) fail('cpf', 'este CPF já está cadastrado para ' + Model.displayName(owner) + '.');
      else fields.cpf = Cpf.format(digits);
    }
    return { fields, errors };
  }

  function showErrors(form, prefix, errorBox, errors) {
    for (const el of form.querySelectorAll('[aria-invalid]')) el.removeAttribute('aria-invalid');
    errorBox.hidden = errors.length === 0;
    errorBox.textContent = errors.map((e) => e.message.charAt(0).toUpperCase() + e.message.slice(1)).join('\n');
    for (const e of errors) {
      const field = form.elements[(e.prefix || prefix) + e.name];
      if (field && field.setAttribute) field.setAttribute('aria-invalid', 'true');
    }
    const first = errors[0] && form.elements[(errors[0].prefix || prefix) + errors[0].name];
    if (first && first.focus) first.focus();
  }

  // ---------- cadastro inicial ----------

  function onWelcomeSubmit(event) {
    event.preventDefault();
    const form = event.target;
    const errors = [];
    const me = validatePerson(readPerson(form, 'me_'), null, 'Seus dados');
    me.errors.forEach((e) => errors.push(Object.assign(e, { prefix: 'me_' })));

    const parents = [];
    for (const [role, label, gender] of [['father', 'Seu pai', 'M'], ['mother', 'Sua mãe', 'F']]) {
      const raw = readPerson(form, role + '_');
      if (!raw.firstName && !raw.lastName && !raw.birthDate) continue;
      const result = validatePerson(raw, null, label);
      result.errors.forEach((e) => errors.push(Object.assign(e, { prefix: role + '_' })));
      parents.push(Object.assign(result.fields, { gender }));
    }
    showErrors(form, '', $('#welcomeError'), errors);
    if (errors.length) return;

    tree = Model.emptyTree();
    const self = Model.createPerson(tree, me.fields);
    parents.forEach((fields) => Model.addParent(tree, self.id, { fields }));
    selectedId = self.id;
    saveTree();
    form.reset();
    render();
    notify('Árvore criada. Clique em uma pessoa para adicionar parentes.');
  }

  // ---------- diálogo de pessoa ----------

  const TITLES = {
    edit: 'Editar',
    'add-parent': 'Adicionar pai ou mãe de',
    'add-spouse': 'Adicionar cônjuge de',
    'add-child': 'Adicionar filho(a) de',
    'add-sibling': 'Adicionar irmão(ã) de',
  };
  const EXISTING_KIND = { 'add-parent': 'parent', 'add-spouse': 'spouse', 'add-child': 'child' };

  function fillSelect(select, options, placeholder) {
    select.replaceChildren(new Option(placeholder, ''));
    for (const p of options) {
      const year = Dates.year(p.birthDate);
      select.add(new Option(Model.displayName(p) + (year ? ' (' + year + ')' : ''), p.id));
    }
  }

  function suggestedGender(kind, person) {
    if (kind === 'add-spouse') return person.gender === 'M' ? 'F' : person.gender === 'F' ? 'M' : '';
    if (kind === 'add-parent') {
      const other = tree.people[person.parentIds[0]];
      return other && other.gender === 'M' ? 'F' : other && other.gender === 'F' ? 'M' : '';
    }
    return '';
  }

  function openPersonDialog(kind, personId) {
    const person = tree.people[personId];
    const form = $('#personForm');
    dialogState = { kind, personId };
    form.reset();
    showErrors(form, '', $('#personError'), []);
    $('#personTitle').textContent = TITLES[kind] + ' ' + Model.displayName(person);
    $('#personSubmit').textContent = kind === 'edit' ? 'Salvar' : 'Adicionar';

    const existingKind = EXISTING_KIND[kind];
    const candidates = existingKind ? Model.candidates(tree, existingKind, personId) : [];
    fillSelect($('#existingSelect'), candidates, 'Não, cadastrar uma nova pessoa');
    $('#existingBox').hidden = candidates.length === 0;

    const spouses = kind === 'add-child' ? Model.spousesOf(tree, personId) : [];
    fillSelect($('#otherParentSelect'), spouses, 'Não informado');
    if (spouses.length === 1) $('#otherParentSelect').value = spouses[0].id;
    $('#otherParentBox').hidden = kind !== 'add-child' || spouses.length === 0;

    const values = kind === 'edit' ? person : { gender: suggestedGender(kind, person) };
    for (const name of ['firstName', 'lastName', 'birthPlace', 'cpf', 'notes']) form.elements[name].value = values[name] || '';
    form.elements.birthDate.value = Dates.formatBR(values.birthDate);
    form.elements.deathDate.value = Dates.formatBR(values.deathDate);
    form.elements.gender.value = values.gender || '';
    form.elements.cpf.value = Cpf.format(values.cpf);
    syncExistingChoice();
    $('#personDialog').showModal();
    (form.elements.firstName.disabled ? $('#existingSelect') : form.elements.firstName).focus();
  }

  function syncExistingChoice() {
    const usingExisting = !$('#existingBox').hidden && $('#existingSelect').value !== '';
    $('#personFields').hidden = usingExisting;
    for (const el of $('#personFields').querySelectorAll('input, textarea')) el.disabled = usingExisting;
  }

  function onPersonSubmit(event) {
    event.preventDefault();
    const form = event.target;
    const { kind, personId } = dialogState;
    const existingId = $('#existingBox').hidden ? '' : $('#existingSelect').value;
    const otherParentId = $('#otherParentBox').hidden ? '' : $('#otherParentSelect').value;
    let spec = { existingId };
    if (!existingId) {
      const result = validatePerson(readPerson(form, ''), kind === 'edit' ? personId : null);
      showErrors(form, '', $('#personError'), result.errors);
      if (result.errors.length) return;
      spec = { fields: result.fields };
    }
    try {
      // O foco continua em quem está sendo editado, para dar para adicionar pai e mãe em sequência.
      if (kind === 'edit') Model.updatePerson(tree, personId, spec.fields);
      else if (kind === 'add-parent') Model.addParent(tree, personId, spec);
      else if (kind === 'add-spouse') Model.addSpouse(tree, personId, spec);
      else if (kind === 'add-child') Model.addChild(tree, personId, spec, otherParentId || undefined);
      else if (kind === 'add-sibling') Model.addSibling(tree, personId, spec);
      saveTree();
      $('#personDialog').close();
      render();
    } catch (err) {
      showErrors(form, '', $('#personError'), [{ name: 'firstName', message: err.message }]);
    }
  }

  // ---------- ações do painel ----------

  function onPanelAction(action) {
    const person = tree.people[selectedId];
    if (action === 'toggle-cpf') {
      revealCpf = !revealCpf;
      renderPanel();
    } else if (action === 'make-owner') {
      tree.ownerId = person.id;
      saveTree();
      render();
    } else if (action === 'delete') {
      const message = 'Excluir ' + Model.displayName(person) + '? Os vínculos com os parentes serão desfeitos, mas os parentes continuam cadastrados.';
      if (!window.confirm(message)) return;
      Model.removePerson(tree, person.id);
      selectedId = tree.ownerId;
      saveTree();
      render();
    } else {
      openPersonDialog(action, person.id);
    }
  }

  // ---------- importar e exportar ----------

  function download(filename, text, type) {
    // Dentro do APK não existe download do navegador: o app nativo abre o "Salvar como" do Android.
    if (window.AndroidApp && window.AndroidApp.saveFile) {
      window.AndroidApp.saveFile(filename, type, text);
      return;
    }
    const url = URL.createObjectURL(new Blob([text], { type }));
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  const stamp = () => new Date().toISOString().slice(0, 10);

  async function onFileChosen(file) {
    if (!file) return;
    if (file.size > MAX_FILE_BYTES) return notify('Arquivo grande demais (limite de 25 MB).', true);
    const count = Object.keys(tree.people).length;
    if (count && !window.confirm('Importar substitui a árvore atual (' + count + ' pessoa(s)). Para guardá-la, cancele e use "Backup" antes. Continuar?')) return;
    try {
      const buffer = await file.arrayBuffer();
      const text = Gedcom.decode(buffer);
      let imported;
      let warnings = [];
      if (text.trimStart().startsWith('{')) {
        const parsed = JSON.parse(text);
        if (!parsed || typeof parsed.people !== 'object') throw new Error('Este arquivo JSON não é um backup desta árvore.');
        imported = Model.sanitize(parsed);
      } else {
        ({ tree: imported, warnings } = Gedcom.importGedcom(text));
      }
      if (!Object.keys(imported.people).length) throw new Error('O arquivo não tem nenhuma pessoa.');
      tree = imported;
      selectedId = tree.ownerId;
      saveTree();
      render();
      const first = Model.displayName(tree.people[tree.ownerId]);
      notify(Object.keys(tree.people).length + ' pessoa(s) importada(s). A árvore está centrada em ' + first + '; escolha outra pessoa e clique em "Sou eu" se preciso.' + (warnings.length ? ' Avisos: ' + warnings.join(' ') : ''), false);
    } catch (err) {
      notify('Não consegui importar: ' + (err instanceof SyntaxError ? 'o arquivo não é um JSON válido.' : err.message), true);
    }
  }

  function onCommand(cmd) {
    if (cmd === 'import') $('#fileInput').click();
    else if (cmd === 'export-gedcom') $('#exportDialog').showModal();
    else if (cmd === 'export-json') {
      download('arvore-genealogica-backup-' + stamp() + '.json', JSON.stringify(tree, null, 2), 'application/json');
      notify('Backup baixado. Ele inclui o CPF, então guarde em local seguro.');
    } else if (cmd === 'wipe') {
      if (!window.confirm('Apagar TODOS os dados deste navegador? Isso não pode ser desfeito. Se quiser guardar a árvore, faça o "Backup" antes.')) return;
      tree = Model.emptyTree();
      selectedId = null;
      try { localStorage.removeItem(STORAGE_KEY); } catch (err) { /* sem armazenamento, nada a apagar */ }
      render();
      notify('Todos os dados foram apagados deste navegador.');
    }
  }

  // ---------- eventos ----------

  document.addEventListener('click', (event) => {
    const target = event.target.closest('[data-cmd], [data-action], [data-select], [data-close]');
    if (!target) return;
    if (target.dataset.cmd) onCommand(target.dataset.cmd);
    else if (target.dataset.action) onPanelAction(target.dataset.action);
    else if (target.dataset.select) select(target.dataset.select);
    else if (target.hasAttribute('data-close')) target.closest('dialog').close();
  });

  document.addEventListener('input', (event) => {
    const el = event.target;
    if (el.name && /(^|_)cpf$/.test(el.name)) el.value = Cpf.format(el.value);
    if (el.hasAttribute && el.hasAttribute('aria-invalid')) el.removeAttribute('aria-invalid');
  });

  let resizeTimer = null;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => { if (chart && !$('#workspace').hidden) chart.updateTree({ tree_position: 'fit' }); }, 250);
  });

  $('#panel').addEventListener('change', (event) => {
    if (event.target.id === 'jump') select(event.target.value);
  });
  $('#existingSelect').addEventListener('change', syncExistingChoice);
  $('#welcomeForm').addEventListener('submit', onWelcomeSubmit);
  $('#personForm').addEventListener('submit', onPersonSubmit);
  $('#fileInput').addEventListener('change', (event) => {
    const file = event.target.files[0];
    event.target.value = '';
    onFileChosen(file);
  });
  $('#exportForm').addEventListener('submit', (event) => {
    event.preventDefault();
    const includeCpf = event.target.elements.includeCpf.checked;
    download('arvore-genealogica-' + stamp() + '.ged', '﻿' + Gedcom.exportGedcom(tree, { includeCpf }), 'text/plain;charset=utf-8');
    $('#exportDialog').close();
    notify('Arquivo GEDCOM baixado' + (includeCpf ? ' (com CPF).' : ' (sem CPF).'));
  });

  render();

  // Uso offline e instalação no celular. Só funciona em http(s), nunca ao abrir o arquivo direto.
  if ('serviceWorker' in navigator && /^https?:$/.test(location.protocol) && !window.AndroidApp) {
    navigator.serviceWorker.register('sw.js').catch(() => { /* sem service worker o app continua funcionando online */ });
  }
})();
