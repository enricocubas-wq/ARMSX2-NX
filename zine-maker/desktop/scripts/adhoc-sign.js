'use strict';
/*
 * Assinatura "ad hoc" do app no macOS (sem certificado da Apple), usada como hook afterPack.
 *
 * Por quê: em Macs com chip Apple um app sem NENHUMA assinatura não abre ("está danificado").
 * Com a assinatura ad hoc ele abre pelo clique direito → Abrir (só na primeira vez).
 * Para distribuir sem esse aviso é preciso o Apple Developer Program (assinatura + notarização).
 *
 * Por que afterPack e não afterSign: sem certificado o electron-builder não assina, e então
 * pula o hook afterSign. No build "universal" ele empacota x64 e arm64 em pastas temporárias
 * (…-temp), mescla os dois e só então chama afterPack no app final, que é o que deve ser assinado.
 */
const path = require('node:path');
const { execFileSync } = require('node:child_process');

exports.default = async function adhocSign(context) {
  if (context.electronPlatformName !== 'darwin') return;
  if (/-temp$/.test(context.appOutDir)) return; // pastas por arquitetura, antes da mescla
  const app = path.join(context.appOutDir, context.packager.appInfo.productFilename + '.app');
  console.log('  • assinatura ad hoc: ' + app);
  execFileSync('codesign', ['--force', '--deep', '--sign', '-', app], { stdio: 'inherit' });
  execFileSync('codesign', ['--verify', '--deep', '--strict', app], { stdio: 'inherit' });
};
