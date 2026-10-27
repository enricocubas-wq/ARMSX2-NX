# Árvore genealógica

App web em português para cadastrar você e sua família (nome, data e local de nascimento, CPF opcional, pais, cônjuges, filhos, irmãos) e ver a árvore desenhada na hora.

Funciona direto no navegador, **sem servidor e sem conta**. Tudo fica salvo só no navegador de quem usa.

## Como abrir

Dê dois cliques em `index.html`. Se preferir servir por HTTP:

```sh
npm start        # http://localhost:8080
```

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
