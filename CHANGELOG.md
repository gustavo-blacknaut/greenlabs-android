# Changelog

Todas as mudanças notáveis do cliente Android, por versão. Formato livre,
em português, ligado aos [releases do GitHub](https://github.com/gustavo-blacknaut/greenlabs-live-streaming-mobile/releases).

## [1.0.3](https://github.com/gustavo-blacknaut/greenlabs-live-streaming-mobile/releases/tag/v1.0.3) — 2026-08-24

- **Transmitir a tela nunca funcionou — achada a causa raiz.** O servidor de
  frames não mandava o cabeçalho `Access-Control-Allow-Origin`. A página é
  servida numa porta e o stream em outra, o que para o navegador são origens
  diferentes: o CORS bloqueava o pedido antes mesmo de chegar no servidor,
  e no JS isso aparece exatamente como **"Failed to fetch"**. O servidor de
  áudio do desktop já mandava esse cabeçalho pelo mesmo motivo; faltava aqui.
- **Layout de celular refeito com abas.** Barra inferior alternando entre
  **Telas**, **Transmissões** e **Usuários**, cada uma ocupando a tela
  inteira, em vez de espremer tudo junto (medido: 638px de área útil contra
  309px antes).
- Correção da regressão de áudio da versão anterior (margem de descarte do
  buffer estava abaixo do tamanho das rajadas e jogava fora um terço do som).

## [1.0.2](https://github.com/gustavo-blacknaut/greenlabs-live-streaming-mobile/releases/tag/v1.0.2) — 2026-08-24

Corrige quatro problemas reais de um primeiro teste em aparelho:

- **Compartilhar tela "teleportando"** em vez de mover suavemente — os
  frames JPEG chegavam em rajadas e eram desenhados assim que decodificados;
  agora o desenho no canvas é espaçado pelo fps alvo.
- **"Failed to fetch" ao tentar transmitir a tela** — retry com backoff na
  conexão inicial ao stream nativo.
- **O topo da tela e o espaço da câmera não respeitavam a barra de
  status/notch.** O listener de `WindowInsets` era registrado depois de
  `setContentView()`, perdendo o único despacho automático — o padding só
  aparecia depois de algum evento novo (rotação, teclado). Corrigido
  forçando um novo despacho (`requestApplyInsets`) logo após registrar o
  listener.
- **Notificação do compartilhamento de tela ganhou botões de ação**:
  "Parar transmissão" e "Sair da chamada", sem precisar voltar pro app.

## [1.0.1](https://github.com/gustavo-blacknaut/greenlabs-live-streaming-mobile/releases/tag/v1.0.1) — 2026-08-24

- **`localStorage` não sobrevivia a reiniciar o app.** O servidor local
  abria numa porta aleatória a cada vez, e `localStorage` é preso à
  origem (porta incluída) — cada abertura virava uma origem diferente,
  um balde de armazenamento vazio. Nome/servidor/sala do onboarding
  sumiam toda vez. Trocado para uma porta fixa (47869), com fallback
  para porta aleatória só se ela estiver ocupada.
- Recebe os fixes do app desktop: renegociação após `addTrack` (áudio da
  tela não chegava no remoto) e `setShareError` (nunca tinha sido
  declarado — todo erro de câmera/tela sumia sem avisar).

## [1.0.0](https://github.com/gustavo-blacknaut/greenlabs-live-streaming-mobile/releases/tag/v1.0.0) — 2026-08-24

Primeira versão publicada. Cliente WebView carregando o mesmo React do
desktop, servido por HTTP local (loopback: `file://` mata `getUserMedia`,
`https://` bloqueia `ws://` na LAN — só loopback `http://` resolve os dois).

- **Compartilhar tela via `MediaProjection` nativo** (não existe
  `getDisplayMedia` em navegador Android — é limitação da plataforma, não
  do WebView). `ScreenCaptureService` (foreground, tipo `mediaProjection`)
  captura frames, codifica em JPEG e entrega por HTTP local no mesmo
  formato que o áudio WASAPI do desktop já usa; o lado web desenha os
  frames num `canvas` e usa `captureStream()` para virar uma `MediaStream`
  de verdade. Resolução limitada a 1280×720/15fps (encode por software).
  Exige notificação persistente durante o compartilhamento a partir do
  Android 14 — não é opcional, é exigência da plataforma.
- **Edge-to-edge de verdade.** A partir do Android 15 o sistema força
  edge-to-edge e ignora `setDecorFitsSystemWindows(true)` — era por isso
  que o conteúdo colava na barra de status. Corrigido com um listener de
  `WindowInsets` que aplica padding real no WebView.
- **Tela cheia corrigida.** `Element.requestFullscreen()` só funciona numa
  WebView se o app implementar `onShowCustomView`/`onHideCustomView` —
  nunca tinha sido implementado, então o botão não fazia nada.
- **Ícone de verdade**, gerado a partir da logo do app — antes era o
  template padrão do Android Studio (grade verde + robozinho), nunca
  trocado.
- **Barra de ação nativa removida** — aparecia uma faixa preta escrita
  "GreenLabs" acima do app, sem relação com a UI real; era o tema
  `DarkActionBar` padrão, nunca customizado.
- Layout do painel de controle corrigido em telas de celular
  (Transmissões/Usuários ficavam espremidos lado a lado, herdando um
  breakpoint pensado para tablet).
- Assinatura de release configurada (`keystore.properties`, fora do git) e
  `proguard-rules.pro` (faltava e quebrava o build de release com R8).
