'use strict';
/* Gera o app em arquivo único (tools/build-single.js) e o copia para desktop/app/index.html. */
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const root = path.resolve(__dirname, '..', '..'); // pasta zine-maker/
execFileSync(process.execPath, [path.join(root, 'tools', 'build-single.js')], { stdio: 'inherit' });

const outDir = path.resolve(__dirname, '..', 'app');
fs.rmSync(outDir, { recursive: true, force: true });
fs.mkdirSync(outDir, { recursive: true });
fs.copyFileSync(path.join(root, 'dist', 'zine-maker.html'), path.join(outDir, 'index.html'));
console.log('App copiado para ' + path.relative(process.cwd(), path.join(outDir, 'index.html')));
