# Pesquisa: como funciona a produção caseira de zine

Este documento registra o que foi pesquisado para desenhar o Zine Maker e **o grau de certeza de cada afirmação**:

- **[fonte]** vem de material publicado (links no fim);
- **[derivado]** é dedução minha, conferida por teste automatizado no código;
- **[não verificado]** é uma estimativa ou algo que a pesquisa não confirmou.

## 1. Por que múltiplos de 4

Um livreto grampeado é feito de folhas dobradas ao meio e encaixadas umas dentro das outras. Cada folha tem 2 faces e cada face tem 2 páginas, então **cada folha rende 4 páginas**. Um zine de 12 páginas usa 3 folhas; de 10 páginas usa 3 folhas com 2 páginas em branco. **[fonte]** Guias de zine caseiro e ferramentas de imposição (como o ZineArranger) tratam o miolo como múltiplo de 4 e completam com páginas em branco quando falta.

O Zine Maker faz o mesmo: se faltar, entram páginas em branco no final do miolo (ou antes da contracapa, quando capa e miolo são um livreto só).

## 2. Ordem das páginas (imposição)

Para um livreto de **N** páginas (N múltiplo de 4), a folha **i** (a partir de 0, a mais externa) leva:

| face   | esquerda  | direita   |
|--------|-----------|-----------|
| frente | N − 2i    | 2i + 1    |
| verso  | 2i + 2    | N − 2i − 1 |

Para N = 12:

| folha | frente   | verso    |
|-------|----------|----------|
| 1     | 12 \| 1  | 2 \| 11  |
| 2     | 10 \| 3  | 4 \| 9   |
| 3     | 8 \| 5   | 6 \| 7   |

**[derivado]** A tabela é a imposição clássica de "saddle stitch". O código verifica, para todo N de 4 a 64, que cada página aparece exatamente uma vez e que o verso de cada página é a página seguinte. Além disso, os testes renderizam o PDF e leem os pixels para provar que a página certa caiu em cada posição.

Para leitura da direita para a esquerda (mangá, árabe, hebraico) basta trocar esquerda e direita em cada face.

**Capa separada** é um livreto de 4 páginas numa folha só: por fora `contracapa | capa`, por dentro `2ª capa | 3ª capa`.

## 3. Frente e verso: virar pela borda curta ou longa?

Numa folha paisagem, o verso da página que está na esquerda da frente fica atrás dela, na direita do verso. Isso equivale a virar a folha em torno do eixo vertical, ou seja, **pela borda curta**. **[derivado]**

Se o driver da impressora só oferece virar pela borda longa, o verso precisa sair girado 180°. O app tem essa opção e o teste confirma que o verso é girado.

**[não verificado]** Os nomes e o comportamento exatos variam entre fabricantes e sistemas ("virar na borda curta/longa", orientação paisagem/retrato no driver). Nenhuma fonte encontrada confirmou a configuração exata para cada impressora. Por isso o app gera uma **folha de teste** (números grandes, seta "TOPO") para descobrir isso com uma folha de papel, antes de gastar uma tiragem.

## 4. O problema do "a medida nunca bate": creep

Quando as folhas são dobradas e encaixadas, as de dentro "saem" mais na borda do que as de fora. Esse deslocamento é chamado de **creep** (ou *page creep*, *push-out*). Se o refile corta tudo de uma vez, as páginas de dentro perdem mais margem externa do que as de fora, e a arte das páginas centrais fica mais perto da borda.

**[fonte]**
- A conta usada pelo software de imposição Kodak Preps é `(páginas ÷ 4) × espessura do papel`, ou seja, **número de folhas × espessura**.
- O creep depende do número de páginas e da espessura do papel.
- As compensações usuais são **deslocar progressivamente a arte em direção à lombada** (shingling), reduzindo a margem interna, ou **escalar** a arte progressivamente.
- Profissionais relatam que o creep medido costuma ser **maior** que o teórico, e alguns somam cerca de 20%. A recomendação do próprio fabricante é fazer um boneco de dobra com o papel real e medir.
- Em livretos de até ~48 páginas o creep costuma ser pouco perceptível.

**[derivado]** O modelo do app: cada folha empurra as que estão dentro dela por uma espessura de papel (vezes um fator). A folha `k` (0 = mais externa) fica deslocada em `soma das espessuras das k folhas externas × fator`. Isso permite espessuras diferentes (capa de 180 g/m² e miolo de 75 g/m²). O fator padrão é **1,2** (a prática de +20% das gráficas) e há calibração com um zine real.

**Honestidade sobre a ordem de grandeza.** Com papel comum de 75 g/m² (~0,10 mm) e 3 folhas, o creep teórico é de cerca de **0,2 a 0,3 mm**. Isso é pequeno. **[derivado]** Boa parte da sensação de "nunca bate" num zine caseiro provavelmente vem de outras fontes, e o app trata cada uma:

| causa provável | o que o app faz |
|---|---|
| dobra manual desalinhada em ~1 mm | **sobra mínima** (3 mm por padrão) em todas as folhas |
| impressora que escala a página ("ajustar à página") | instrução de imprimir a 100% e **folha de teste** |
| impressora que não imprime nos 3–5 mm da borda | sobra ≥ 5 mm e **quadros de 1 a 8 mm** na folha de teste para medir |
| medir o corte a partir da borda e não da lombada | o resumo informa a medida **a partir da lombada** |
| creep, relevante com muitas folhas ou papel grosso | deslocamento progressivo da arte por folha |

**[não verificado]** Não validei o modelo de creep contra um zine impresso e fisicamente medido. Ele segue a fórmula publicada. Por isso existe a **calibração**: monte um zine real, meça quanto a folha de dentro passou da de fora, informe no app, e o fator é ajustado para bater com a sua medida.

## 5. Refile e "sobra" em zine caseiro

Diferente de gráfica (que pede sangria de 3 mm em torno da página e corta as quatro bordas), no zine caseiro o refile costuma ser só da **lateral** (a borda "aberta" do livreto). O topo e a base são as bordas de fábrica do papel.

Isso cria uma restrição geométrica: numa folha A4 (297 mm), cada meia-folha tem 148,5 mm, quase exatamente a largura de uma página A5 (148 mm). **Não há espaço para sobra se a página A5 ocupa a meia-folha inteira.** Prever refile significa que a página final fica **mais estreita que A5** (143,5 mm com 5 mm de sobra, por exemplo), e a arte é encaixada nesse espaço. O app mostra essa escala no resumo e deixa escolher entre "caber inteira" (a página encolhe um pouco, nada é cortado) e "preencher" (mantém a escala e pode cortar as bordas).

A linha de corte é **única para o bloco todo**: a largura final é medida da lombada até o corte, e o que sai de cada folha cresce das externas para as internas. O app mostra essa tabela.

## 6. Quantas folhas aguentam

**[não verificado]** Não encontrei um limite publicado. O app avisa (sem bloquear) acima de **8 folhas (32 páginas)**, por julgamento: o miolo fica grosso para dobrar e grampear em casa. É fácil de mudar em `js/app.js`.

## 7. Tecnologia escolhida

- **pdf-lib** monta o PDF imposto. Ele embute páginas de outros PDFs como objetos vetoriais (sem rasterizar) e imagens JPEG/PNG com os bytes originais, então não há perda de qualidade.
- **pdf.js** lê os PDFs enviados e gera as miniaturas e a prévia.
- Tudo roda no navegador, sem servidor: os arquivos nunca saem do computador, funciona offline e pode ser distribuído como um único arquivo HTML.

## 8. Fora do escopo (ideias para depois)

- **Mini zine de 1 folha (8 páginas)**: dobra sem grampo e um corte no meio. É outro tipo de imposição (páginas giradas, sempre 8) e é muito popular em oficinas. Boa candidata para um próximo modo.
- PDF de leitura em tela (páginas em sequência) além do PDF de impressão.
- Duas ou mais páginas por face em folhas maiores (A3 com 4 páginas A5).

## Fontes

- Kodak Preps, cálculo e compensação de creep: <https://workflowhelp.kodak.com/x/1RDTE> e <https://workflowhelp.kodak.com/x/1QDTE>
- Fórum Printplanet, "Creep, how do you calculate it?": <https://printplanet.com/goto/post?id=19494>
- Printivity, "What is Creep in a Saddle-Stitch Booklet?": <https://www.printivity.com/insights/what-is-creep-in-a-saddle-stitch-booklet>
- Smartpress, "Page Creep": <https://smartpress.com/support/printing-basics/creep>
- Zine A5 caseiro, múltiplos de 4 e ZineArranger: <https://itch.io/post/15498052>
- Epson, zine de 12 páginas de miolo grampeado: <https://support.epson.net/fun/articles/86>
- Padrões de dobra de zine de uma folha: <https://notes.hamatti.org/Thoughts/Zine-folding-pattern> e <https://bookleteer.com/blog/2010/09/single-sheet-zine>
- pdf-lib: <https://pdf-lib.js.org>
- Crate `imposer` (referência para a lógica de páginas em livreto): <https://docs.rs/imposer>
