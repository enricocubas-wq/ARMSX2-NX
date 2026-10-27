# Zine Maker

Monta o livreto do seu zine (miolo e capa) já na ordem certa das páginas, em PDF pronto para imprimir em casa, dobrar e grampear. Você joga as páginas na ordem do projeto, o app cuida do resto.

- Roda **100% no navegador**: seus arquivos nunca saem do computador.
- Funciona **offline** e pode ser distribuído como **um único arquivo** para os alunos.
- Entende **múltiplos de 4**: completa com páginas em branco quando falta.
- Prevê o **refile** (a "sobra" que você corta depois de grampear) e compensa o deslocamento das folhas de dentro conforme o zine cresce.

> A pesquisa por trás das decisões, com o que veio de fonte e o que é estimativa, está em [`docs/PESQUISA.md`](docs/PESQUISA.md).

## Como usar

### Opção 1: um arquivo só (melhor para mandar aos alunos)

```bash
npm run build        # gera dist/zine-maker.html
```

Mande o arquivo `dist/zine-maker.html` (2,3 MB). Dê duplo clique: abre no navegador e funciona sem internet. O arquivo já vem gerado na pasta `dist/`.

### Opção 2: abrir a pasta

Abra `index.html` no navegador (Chrome, Edge, Firefox ou Safari recentes). Funciona direto do disco. Para instalar como app (PWA) e usar offline, sirva por HTTP:

```bash
npm run serve        # http://localhost:8080
```

ou publique a pasta em qualquer hospedagem estática (por exemplo GitHub Pages).

### O fluxo

1. **Páginas do miolo**: arraste JPG, PNG ou PDF. Os nomes são ordenados do jeito certo (`pag2` antes de `pag10`); cada página de um PDF vira uma página. Reordene arrastando ou com ◀ ▶; gire com ↻; duplique ⧉; insira páginas em branco.
2. **Capa**: capa e contracapa (e, se quiser, a 2ª e a 3ª capa). Um PDF de 2 páginas preenche capa e contracapa; de 4 páginas, tudo.
3. **Formato**: folha (A4 → livreto A5, A3 → A4, A5 → A6, Carta → meia-carta, ou medida livre), papel, sentido de leitura e lado em que a impressora vira a folha.
4. **Refile (sobra)**: ligue se você vai cortar a lateral depois de grampear (ver abaixo).
5. Veja a **prévia** de cada folha (frente e verso) e **baixe o PDF**.

Antes de uma tiragem, baixe a **folha de teste**: ela mostra para que lado a sua impressora vira o papel, quanto da borda ela deixa sem imprimir e quanto sobrou no refile.

## Estrutura

| modo | resultado |
|---|---|
| **Miolo + capa à parte** | dois PDFs: o miolo (papel comum) e a capa (papel mais grosso, 1 folha) |
| **Tudo num livreto só** | um PDF; a capa é a primeira página e a contracapa a última |

Miolo de 12 páginas = 3 folhas, impostas assim:

| folha | frente | verso |
|---|---|---|
| 1 | 12 \| 1 | 2 \| 11 |
| 2 | 10 \| 3 | 4 \| 9 |
| 3 | 8 \| 5 | 6 \| 7 |

## Refile e sobra

Com **Prever refile** ligado, o app reserva uma sobra na lateral de cada página e posiciona a arte para que, **depois de um corte único no bloco grampeado**, todas as páginas fiquem com a mesma margem.

- **Sobra mínima**: o que sai da folha mais externa (padrão 3 mm). Impressoras caseiras costumam não imprimir nos 3–5 mm da borda; se a arte vai até a lateral, use 5 mm ou mais.
- O que sai das folhas de dentro **cresce** conforme o papel e o número de folhas (creep). O resumo mostra uma tabela com quanto sai de cada folha.
- **Largura final**: medida **da lombada até o corte**. Ex.: A4, sobra de 3 mm → 145,5 mm.
- **Caber inteira** encolhe a página só o suficiente para caber no espaço (o resumo mostra a escala); **Preencher** mantém a escala e pode cortar as bordas.
- **A sobra fica**: *branca* (ótimo para scans em fundo branco) ou *espelhada* (continua a arte até a borda; use em capas e fundos coloridos).
- **Marcas de corte** na linha de refile (liga/desliga). A prévia mostra a sobra hachurada em vermelho; a hachura **não** vai para o PDF.
- **Compensar a espessura do papel (creep)**: pode desligar. A intensidade padrão (1,2×) segue a prática de gráficas.
- **Calibrar**: monte um zine real do mesmo papel, meça quanto a folha de dentro passou da de fora, informe o número de folhas e a medida, e o app ajusta o fator.

Nas folhas de dentro, uma pequena faixa da margem interna "some" na lombada (o mesmo valor do creep). O resumo avisa quando isso passa de ~0,8 mm.

## Imprimindo

1. Imprima em **tamanho real (100%)**, nunca "ajustar à página".
2. Papel A4, **frente e verso**, virando pela **borda curta** (ou longa, se você escolheu assim no app).
3. Dobre cada folha ao meio, encaixe uma dentro da outra (a folha 1 por fora) e grampeie na dobra.
4. Se previu refile: corte a lateral na medida indicada, **a partir da lombada**.

Sem frente e verso automático? A página 1 do PDF é a frente da folha 1 e a página 2 é o verso dela. Use a folha de teste para descobrir como a sua impressora puxa o papel de volta.

Dica para scans grandes: prefira JPEG. PNGs enormes (600 dpi) deixam a exportação lenta.

## Desenvolvimento

Sem build para rodar: é HTML, CSS e JavaScript simples (sem framework), com as bibliotecas em `vendor/`.

```
index.html            interface
css/style.css
js/core.js            lógica pura: imposição, creep, geometria  (testável no Node)
js/pdfbuilder.js      gera o PDF com pdf-lib
js/sources.js         leitura de imagens e PDFs, miniaturas, EXIF
js/exif.js            orientação EXIF de fotos de celular
js/app.js             estado e interface
sw.js, manifest.webmanifest, icons/    PWA (offline e instalável)
tools/serve.js        servidor local
tools/build-single.js gera dist/zine-maker.html
tests/                testes
vendor/               pdf-lib (MIT) e pdf.js (Apache-2.0), com licenças
```

```bash
npm install          # só para os testes (pdf-lib)
npm test             # testes: imposição, creep, geometria, PDF renderizado e lido pixel a pixel
npm run test:e2e     # navegador real (precisa de Playwright + Chromium, pdftoppm e ImageMagick)
```

`ZINE_E2E_ENTRY=dist/zine-maker.html npm run test:e2e` roda o mesmo teste no arquivo único.

Os testes de PDF renderizam a saída com `pdftoppm` (poppler) e conferem as cores dos pixels: cada página de teste é um bloco de cor que codifica o número da página, então dá para provar qual página caiu onde, a largura da sobra, a rotação e o espelhamento.

## Limitações conhecidas

- A calibração do creep não foi validada contra um zine impresso e medido por quem escreveu o app; ela segue a fórmula publicada. Use a calibração com o seu papel.
- Folhas e papéis usam espessuras **típicas**; seu papel pode variar. Em "Outra espessura…" dá para informar a medida.
- Mini zine de uma folha (8 páginas, dobra e um corte) ainda não existe.
- PDFs protegidos por senha não são aceitos.
- Formatos além de JPG/PNG/WebP/GIF/AVIF dependem do que o navegador consegue abrir (HEIC, por exemplo, só no Safari).

## Licenças de terceiros

- [pdf-lib](https://github.com/Hopding/pdf-lib) (MIT): `vendor/LICENSE.pdf-lib.md`
- [pdf.js](https://github.com/mozilla/pdf.js) (Apache-2.0): `vendor/LICENSE.pdfjs.txt`
