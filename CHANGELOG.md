# Changelog

Todas as mudanças notáveis do cliente Android, por versão. Formato livre,
em português, ligado aos [releases do GitHub](https://github.com/gustavo-blacknaut/greenlabs-live-streaming-mobile/releases).

## [1.0.6](https://github.com/gustavo-blacknaut/greenlabs-live-streaming-mobile/releases/tag/v1.0.6) — 2026-08-25

- **720p a 30fps, experimental.** O limite era 15fps. Três tetos precisavam
  subir juntos, senão o mais baixo anulava os outros: o do lado web, o do
  bridge nativo (que cortava em 20fps) e o piso de intervalo entre frames do
  serviço de captura.

  É experimental porque cada frame vira um JPEG por software: 30fps custa o
  dobro de CPU e bateria de 15. Em aparelho mais fraco pode engasgar — nesse
  caso é só escolher uma qualidade menor, que o fps acompanha.

## [1.0.5](https://github.com/gustavo-blacknaut/greenlabs-live-streaming-mobile/releases/tag/v1.0.5) — 2026-08-24

**Primeira versão estável.** Testada em aparelho real: transmitir tela,
câmera, microfone, entrar em sala e o layout de abas funcionando.

- Cliente web embutido atualizado, com o tráfego de sinalização reduzido em
  até 23x em salas grandes (o servidor agrupava mal os broadcasts de ping) e o
  código dividido em módulos.

## [1.0.4](https://github.com/gustavo-blacknaut/greenlabs-live-streaming-mobile/releases/tag/v1.0.4) — 2026-08-24

- **A barra de abas ficava por baixo dos botões de navegação do Android**,
  então os toques não pegavam direito. A abordagem anterior — aplicar padding
  na WebView — não resolve: o padding da view não encolhe de forma confiável
  o que a página enxerga como `100dvh`, então o layout continuava indo até a
  borda. Agora o `MainActivity` publica os tamanhos das barras do sistema
  como variáveis CSS (`--android-inset-*`), reenviadas a cada carregamento
  de página, e o layout se posiciona a partir delas. Medido no navegador
  simulando uma barra de 48dp: antes invadia **34px**, agora zero.
- **Controles no alcance do polegar.** Transmitir tela, câmera, configuração
  e entrar/sair agora ficam numa barra logo acima das abas, em vez de no
  topo da tela.
- Alvos de toque maiores nas abas (56px) e nos controles (~53px).

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
