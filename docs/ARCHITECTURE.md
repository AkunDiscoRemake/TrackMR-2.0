# Arquitetura · MR-first alpha03

Mudanças desta revisão: [ALPHA03](ALPHA03.md). O texto abaixo mantém a arquitetura base; detalhes de MR preencher, overlay de mãos, UI e display Shizuku estão nessa nota.

```
:app → VrActivity (launcher é GLSurfaceView; sem MainActivity 2D)
 ├─ dock/SpatialShell → packet de objetos 3D + atlas de glifos/ícones
 │    └─ JNI → renderer GLES → API Cardboard → distorção/display
 ├─ camera/CameraFeed → ARCore OU Camera2 (um dono por sessão)
 │    └─ CameraConsumer → tracking/HandTracker (worker MediaPipe)
 │         └─ :handtracking (associação/filtros/gestos) → snapshot → GL
 ├─ browser/SpatialBrowser → WebView/Presentation em display privado
 ├─ platform/CaptureService → MediaProjection autorizado
 │    └─ browser OU capture → SurfaceTexture OES → janela espacial
 ├─ ai/LocalAssistant → worker MediaPipe LLM → texto espacial
 ├─ audio/SpatialAudio → SoundPool (pan estéreo, não HRTF)
 ├─ diagnostics/SpatialScreenshot → PixelCopy consentido → MediaStore
 ├─ platform/ShizukuBridge → user service shell com AIDL restrito
 └─ :openxr/SessionActivity (opcional; pausa Cardboard/câmera)
      └─ JNI session.cpp → loader oficial → runtime externo

:core          JVM: matemática/filtros/política/estatística reutilizados
:xr            JVM: experiência, janelas/dock, qualidade/relógios/recovery
:handtracking  JVM: contratos, associação temporal, filtros, gestos
:openxr        Android/C++: cliente de sessão, independente de UI/loja/MediaPipe
:runtime       APK companion: Broker, sondagem e acesso ao cliente :openxr
:dev-api       JVM: contratos v1 e exemplo; host de plugins ainda não implementado
render/include/trackmr/math.hpp → matemática comum aos renderers nativos
```

Os diretórios `camera`, `browser`, `ai`, `audio`, `diagnostics`, `dock`, `tracking`, `store`, `platform` isolam responsabilidades dentro do app. Não existem implementações completas de SLAM próprio, network/download manager, marketplace, malha de sala ou runtime Monado ocultas atrás desses nomes. ARCore é o provedor atual de SLAM/planos/âncoras quando disponível.

## Renderização e espaços

- API Cardboard oficial fixada no bootstrap: projeção/eye-from-head e malha de distorção; nada de substituir por divisão simples da tela. A escala do framebuffer não altera dimensões físicas usadas nas lentes.
- Objetos em metros, +X direita/+Y acima/-Z frente. ARCore `displayOrientedPose` é relativa à posição/yaw inicial, mantendo gravidade. Não há fusão Cardboard/ARCore: transição/relocalização ainda precisa de validação de conforto.
- Camera2 fornece imagem mono e orientação Cardboard; **não fornece posição/SLAM**. Intrínsecos/rotação/FOV aproximados precisam de calibração física por aparelho.
- MR usa imagem real em OES. Falta/perda de imagem troca para espaço neutro; panorama só no modo VR selecionado pelo usuário.
- Mãos MediaPipe: imagem → viewport, 21 landmarks e profundidade relativa. Há overlay de esqueleto projetado por olho, identificado como câmera e não mão 3D métrica. Índice controla ponteiro; gestos usam amostras filtradas, sem previsão.
- Depth opcional: buffer depth16 em R16UI, UV de IMAGE_NORMALIZED separado do UV OES, descartado se velho. Compara profundidade axial com objetos procedurais. Não cobre UI/mãos, não reconstrói ambiente e não resolve sozinho offset câmera/olhos.
- Janelas são objetos/controles espaciais independentes. Até cinco, packet de 160 itens, atlas de 128 tiles. Teclado e fades têm orçamento testado. Layout salvo é relativo à sessão, não mapa persistente.
- Navegador/captura são conteúdos 2D em uma superfície da janela; o dock e seus controles não são screenshot de launcher Android. Conteúdo browser/captura/app pode usar superfície cilíndrica tessellada, com hit/UV correspondentes; controles da moldura continuam planos.

## Threads e ownership

| Dono | Recursos |
|---|---|
| Main looper | Activity, permissões/pickers, browser/WebView, Surface de VirtualDisplay, bindings/captura |
| GL thread | EGL/Cardboard, texturas, frame ARCore, packet, decisões de interação, NN opcional ≤1 Hz |
| Worker MediaPipe | Inicialização, inferência e fechamento do backend de mãos |
| Camera2 HandlerThread | Callbacks de câmera/ImageReader; reserva antes de enviar imagem |
| Worker LLM | Importação limitada e inferência textual, fechamento após trabalho em voo |
| Worker IO | Panoramas, screenshot, importação/verificação neural |
| Worker OpenXR | Todas as chamadas da sessão e renderização dedicada; stop atômico |

Mãos têm backpressure de capacidade um. Imagem/MPImage são fechadas no caminho normal/erro. Bitmap/conversão reutilizados; snapshots/publicação/filtros ainda alocam. Não há alegação de zero-copy/zero-GC.

`onPause` desliga fonte browser/capture e pausa câmera; drena inferência antes de fechar leitor/sessão. Serviço de captura libera projeção explicitamente mesmo enquanto vinculado. Uma autorização de MediaProjection cria no máximo um display; nova captura exige novo consentimento. Perda de EGL encerra conteúdo antes de liberar a Surface antiga.

LLM síncrono não é cancelável instantaneamente: close é enfileirado depois da inferência. NN de qualidade pode bloquear o GL por até o custo da inferência/importação; é experimental, desabilitada por padrão e deve ser medida antes de uso contínuo.

## Apps e segurança

1. MediaProjection: autorizado, notificação/parar, um app no Android 14+ quando escolhido; não injeta toque. Browser e captura não coexistem na única Surface de conteúdo.
2. Shizuku: autorização separada; comandos restritos `am start --display`/`wm size -d` em display não primário. Um VirtualDisplay próprio agora recebe app via shell e input restrito ao display. Múltiplos apps simultâneos ainda faltam. Não promete suporte por todos os OEMs.
3. Biblioteca: lançar abre Activity Android externa ao VR; recentes/favoritos são locais; detalhes/remoção usam o sistema.
4. Browser: HTTPS, sem acesso file/content ou bridge JS, download bloqueado e câmera/mic negados por padrão. JavaScript do site é habilitado; inserção textual manual usa JSON quoting no campo DOM focado.
5. IA: modelo local escolhido pelo usuário. Modelos são entrada de bibliotecas nativas e devem vir de fonte confiável. Nenhum peso é redistribuído sem licença.

Sem shell arbitrário, instalação silenciosa, impersonação do Broker, analytics ou upload automático de câmera. Notificações locais não equivalem a ler notificações privadas de outros apps.
