# Árvore genealógica

App web em português para cadastrar você e sua família (nome, data e local de nascimento, CPF opcional, pais, cônjuges, filhos, irmãos) e ver a árvore desenhada na hora.

Funciona direto no navegador, **sem servidor e sem conta**. Tudo fica salvo só no navegador de quem usa.

## Como abrir

Dê dois cliques em `index.html`. Se preferir servir por HTTP:

```sh
npm start        # http://localhost:8080
```

## Usar no celular (link + instalar como app)

O app é um PWA: abre por um link e pode ser instalado na tela inicial, com ícone próprio, tela cheia e funcionamento **sem internet**.

**Publicar (uma vez só):** o workflow `.github/workflows/arvore-genealogica-pages.yml` publica a pasta na branch `gh-pages` a cada alteração. Falta só ligar o GitHub Pages: no repositório, **Settings > Pages > Build and deployment > Source: "Deploy from a branch" > Branch: `gh-pages` / `(root)` > Save**. O link fica em `https://<usuário>.github.io/<repositório>/`.

**Instalar:**
- Android (Chrome): menu ⋮ > **Instalar app** (ou *Adicionar à tela inicial*).
- iPhone (Safari): botão de compartilhar > **Adicionar à Tela de Início**.

Observações:
- Os dados ficam no navegador do aparelho. Abrir o link em outro aparelho (ou no computador) começa vazio: use **Backup** e **Importar** para levar a árvore de um para outro.
- Os dados ficam guardados por *endereço do site*. Em `usuario.github.io`, outros sites seus no GitHub Pages compartilham o mesmo armazenamento. Se isso importar, use um domínio próprio para o app.
- Atualizações: o app abre com a versão guardada e baixa a nova em segundo plano, então a versão nova aparece na abertura seguinte.

## Computador (Windows, Mac, Linux)

Download direto: **https://github.com/enricocubas-wq/ARMSX2-NX/releases/download/arvore-genealogica-apk/arvore-genealogica-pc.zip**

1. **Extraia tudo** do `.zip` (Windows: botão direito > *Extrair tudo*). Não abra de dentro do zip.
2. Dê dois cliques em `index.html`. Abre no navegador (Chrome, Edge ou Firefox), sem instalar nada e sem internet.

Os dados ficam no navegador daquele computador. Faça **Backup** de vez em quando, principalmente antes de mudar a pasta de lugar ou limpar os dados do navegador. Para levar a árvore entre celular e computador, use **Backup** em um e **Importar** no outro.

Alternativa sem baixar nada: se o GitHub Pages estiver ligado (veja a seção do celular), o mesmo app abre pelo link no navegador do computador, e o Chrome/Edge permitem instalá-lo como aplicativo (ícone na barra de endereço).

## APK para Android

Download direto (Android): **https://github.com/enricocubas-wq/ARMSX2-NX/releases/download/arvore-genealogica-apk/arvore-genealogica.apk**

1. Baixe o arquivo no celular e toque nele.
2. Se o Android pedir, permita instalar apps desta fonte (navegador ou gerenciador de arquivos). Isso vale só para este app.
3. Abra o app **Árvore**.

Como é feito: o app web fica **dentro** do APK (`android/`), exibido por uma WebView. Funciona sem internet e **sem pedir nenhuma permissão**, nem a de internet; o backup automático do Android também está desligado, então os dados não vão para a nuvem do Google. Para o que a WebView não faz sozinha, a casca nativa cuida de `confirm()`, da escolha de arquivo (Importar) e do "Salvar como" do Android (Exportar GEDCOM e Backup).

O APK é compilado pelo GitHub Actions (`.github/workflows/arvore-genealogica-apk.yml`) a cada alteração em `arvore-genealogica/` e publicado na release `arvore-genealogica-apk` (marcada como pré-lançamento para não virar a "última release" do repositório do emulador). Para compilar à mão: `sh android/sync-web.sh` e depois `gradle assembleRelease` dentro de `android/` (precisa de JDK 17 e Android SDK 34).

Observações:
- É assinado com uma chave própria, criada na primeira compilação e guardada no cache do GitHub Actions. Enquanto o cache existir, um APK novo atualiza o app **sem apagar os dados**. Se o cache for perdido, o Android recusa a atualização por cima: faça **Backup**, desinstale, instale e use **Importar**.
- Só para Android. No iPhone, use o link do PWA (seção anterior).

## O que faz

- **Cadastro inicial**: você, seu pai e sua mãe. A árvore já aparece.
- **Crescer a árvore**: clique em uma pessoa e use *+ Pai ou mãe*, *+ Cônjuge*, *+ Filho(a)* ou *+ Irmão(ã)*. Dá para vincular alguém que já está cadastrado em vez de criar de novo (por exemplo, o mesmo avô em dois ramos).
- **Datas parciais**: `10/04/1999`, `04/1999` ou só `1999`, porque em genealogia muitas vezes só se sabe o ano.
- **Importar e exportar GEDCOM 5.5.1**, o formato padrão de troca entre programas de genealogia.
- **Backup em JSON** com todos os dados (inclusive CPF).
- Funciona offline, em celular e computador, com modo claro e escuro.

## Privacidade

- A página tem uma política de segurança (`Content-Security-Policy`) que **bloqueia qualquer conexão de rede**. Não há como os dados saírem dela.
- O CPF é opcional, é validado pelos dígitos verificadores e **nunca aparece nos cartões da árvore**. No painel ele fica mascarado (`•••.•••.•••-75`) até você clicar em *mostrar*.
- O GEDCOM exportado **não inclui o CPF**, a menos que você marque essa opção.
- Dados de parentes também são dados pessoais. Compartilhe o GEDCOM e o backup com cuidado.
- *Apagar tudo* remove os dados do navegador.

## Projetos open source usados e para continuar

| Projeto | Papel |
| --- | --- |
| [family-chart](https://github.com/donatso/family-chart) (MIT) + [d3](https://d3js.org) (ISC) | Desenho interativo da árvore. Estão em `vendor/`, sem modificações (veja `vendor/LICENSES.md`). |
| [GEDCOM 5.5.1](https://gedcom.io) | Formato de arquivo para levar a árvore para outros programas. |
| [Gramps](https://gramps-project.org) / [Gramps Web](https://www.grampsweb.org), [webtrees](https://webtrees.net), [Topola Viewer](https://pewu.github.io/topola-viewer/) | Programas open source que abrem GEDCOM. Servem para quando a árvore crescer além do que este app faz (fontes, documentos, fotos, vários colaboradores). |

O GEDCOM gerado aqui foi verificado por ida e volta (exportar e importar de novo neste próprio app) e contra a estrutura do padrão. Não foi testado programa por programa.

## O que este app não faz

- **Não descobre parentes sozinho.** Não existe base pública que ligue um CPF ou um nome aos pais de alguém, e certidões de nascimento e casamento não têm API aberta. A árvore é construída com o que você informa (ou importa).
- **Não junta árvores de várias pessoas.** Cada navegador tem a sua. Para comparar árvores de parentes, cada um exporta o GEDCOM e vocês juntam num programa como Gramps ou webtrees.
- Guarda só nascimento e falecimento. Casamentos (data e local), fotos e fontes ainda não.

## Estrutura

```
index.html        página única
manifest.webmanifest, sw.js, icons/   instalação no celular e uso offline (PWA)
css/app.css       visual (claro/escuro)
js/cpf.js         validação, formatação e máscara de CPF
js/dates.js       datas parciais (dd/mm/aaaa, mm/aaaa, aaaa) e datas GEDCOM
js/model.js       pessoas, vínculos, validações e conversão para o family-chart
js/gedcom.js      exportação e importação GEDCOM
js/app.js         interface
vendor/           family-chart e d3
test/             testes automatizados
```

## Testes

```sh
npm test
```

Cobrem CPF, datas, vínculos (inclusive proteção contra ciclos e terceiro pai/mãe) e GEDCOM (ida e volta, arquivos de outros programas com adoção e datas aproximadas, codificações UTF-8/UTF-16/Windows-1252, CPF opcional).
