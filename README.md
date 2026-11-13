# Grid of Thieves

Overlay para **King of Thieves** no Android (pacote
`io.github.dmrangel.gridofthieves`): desenha por cima do jogo a mesma grade
14×8 que o jogo usa (células 7×4 e meias-células), com bolha flutuante, painel no
visual do **design system do Modrinth App** e um **detector de base** que lê a tela e
diz qual base da tabela 6.9 está aberta.

Sem bibliotecas: só o framework do Android, views desenhadas em código e animações
com molas próprias. O APK de release passa por R8 e remoção de recursos.

## Como usar

1. Instale o APK e abra **Grid of Thieves**. Na primeira vez o Android pede a permissão
   **Sobrepor a outros apps**.
2. Aparece uma bolha com o ícone da grade. Toque nela para abrir o painel; segure para
   mostrar ou esconder a grade; arraste até o **X** embaixo para fechar o overlay.
3. Aba **Ajustar**: setas e lupa movem e redimensionam a grade (segure para repetir;
   acelera com o tempo), **Passo** define quantos pixels cada toque anda, o alvo do meio
   centraliza na horizontal e **Padrão** volta ao tamanho calculado para a tela.
   **Ajustar com o dedo** deixa arrastar a grade e redimensionar com pinça direto na tela.
   Tudo é salvo sozinho, separado por rotação.
4. Aba **Base**: com a base aberta no jogo, toque em **Detectar base**. Na primeira vez
   o Android pede para capturar a tela. O overlay some por um instante, a tela é lida e
   o painel mostra o número da base, a semelhança, as outras parecidas e um mini-mapa;
   na tela, as paredes da base piscam por cima do jogo (diferenças em vermelho).
   **Alinhar grade** aparece quando a base foi achada fora da grade calibrada e leva a
   grade até ela. **Vídeos** abre a busca da base no YouTube.

Abrir o ícone de novo com o overlay ativo só expande o painel.

## Como funciona

- `OverlayService` é um serviço em primeiro plano dono de todas as janelas (grade, bolha,
  alvo de fechar e painel). As janelas usam coordenadas da tela inteira, incluindo o
  recorte da câmera, e ignoram as barras do sistema; a calibração não mexe quando elas
  aparecem ou o celular vira 180°.
- A janela da grade tem o tamanho da grade (não a tela toda) e não recebe toques; o
  alpha dela fica em 0,78 porque o Android 12+ bloqueia o toque no jogo atrás de janelas
  mais opacas que 0,8.
- `GridConfig` guarda a posição como fração da tela, por rotação. O padrão vem dos
  prints: a célula ocupa 10,04% da altura da tela, ou 6,68% da largura em telas mais
  quadradas (iPad), centralizada, com o topo a 7,7% da altura.
- `scan/BaseMatcher` é o porte do `src/kot/base-match.js` do
  [chrollo](https://github.com/dmrangel/chrollo): limiar de Otsu, maior região clara
  conectada para achar a base, leitura das paredes em 14×8 meias-células e comparação
  com `assets/bases.json` (o mesmo arquivo do bot). Aqui a grade calibrada entra como
  mais um candidato de retângulo, e as bases viram bits para comparar com `bitCount`.
- `scan/ScreenCapture` usa MediaProjection com um único VirtualDisplay que fica sem
  superfície entre as análises (custo zero) e já captura reduzido a 1000 px de largura.

## Desenvolvimento

```bash
./gradlew assembleRelease        # APK leve (R8), assinado com a chave de debug
./gradlew testDebugUnitTest      # testes do BaseMatcher com prints reais
```

- `tools/make_fixtures.py print.webp` converte um print de base em fixture de teste
  (`app/src/test/resources/bases/<número>-<aparelho>.png`, precisa do Pillow). Todo print
  novo com número conhecido deve virar fixture; o teste exige acerto com margem.
- `tools/gen_icons.py` gera os ícones Lucide em `res/drawable/`.
- Atualizou a tabela de bases no chrollo? Copie o `src/kot/bases.json` de lá para
  `app/src/main/assets/bases.json`.

Licenças de terceiros em [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
