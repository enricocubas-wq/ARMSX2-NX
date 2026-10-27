#!/usr/bin/env node
/*
 * Gera dist/zine-maker.html: o app inteiro (HTML, CSS, JS, bibliotecas de PDF e o worker)
 * num único arquivo. É só mandar o arquivo para os alunos: dá duplo clique e funciona,
 * sem internet e sem instalar nada.
 */
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = (f) => fs.readFileSync(path.join(root, f), 'utf8');
// evita que o HTML feche o <script> ou abra um comentário no meio do código embutido
const safe = (code) => code.replace(/<\/script/gi, '<\\/script').replace(/<!--/g, '<\\!--');

let html = read('index.html');
html = html.replace(/<link rel="stylesheet" href="([^"]+)">/, (_, f) => '<style>\n' + read(f) + '\n</style>');
html = html.replace(/\s*<link rel="manifest"[^>]*>/, '');
const icon = fs.readFileSync(path.join(root, 'icons/icon.svg')).toString('base64');
html = html.replace(/<link rel="icon" [^>]*>/, '<link rel="icon" type="image/svg+xml" href="data:image/svg+xml;base64,' + icon + '">');

const worker = fs.readFileSync(path.join(root, 'vendor/pdf.worker.min.js')).toString('base64');
let first = true;
html = html.replace(/<script src="([^"]+)"><\/script>/g, (_, f) => {
  const pre = first ? '<script>window.__ZINE_PDF_WORKER_B64="' + worker + '";</script>\n' : '';
  first = false;
  return pre + '<script>\n' + safe(read(f)) + '\n</script>';
});

const out = path.join(root, 'dist', 'zine-maker.html');
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, html);
console.log('Gerado ' + path.relative(process.cwd(), out) + ' (' + (html.length / 1048576).toFixed(2) + ' MB)');
