# Arquitetura

```
:app (APK dev.trackmr)
 ├─ UI Android → preferências / catálogo / consentimento / IA local
 ├─ GLSurfaceView → JNI → :cardboard (Google SDK)
 │                   ├─ head tracker → pose prevista → matrizes por olho
 │                   ├─ panorama + painel/captura + jogos → framebuffer estéreo
 │                   └─ distortion mesh → tela física
 ├─ ARCore Session (único dono da câmera)
 │    ├─ pose opcional de objetos 6DoF
 │    └─ acquireCameraImage → reserva atômica → worker MediaPipe
 │                  → YUV downsample → inferência → filtro → snapshot → GL
 ├─ MediaProjection + foreground service → SurfaceTexture OES → painel
 ├─ Shizuku → user service AIDL shell (separado da captura)
 └─ política de performance → escala / cadência de mãos

:core (JVM)       filtros, pinça, geometria, histerese, estatística
:dev-api (JVM)    contratos de jogos e exemplo; host externo ainda pendente
:runtime (APK)   Broker ContentProvider → loader OpenXR → teste de instância
```

## Cardboard

O bootstrap fixa `googlevr/cardboard` em `6eea12f99ba825086838554d7702217d780282be` (v1.30.0). O módulo `:cardboard` compila o Java/protobuf do SDK. O CMake do app compila as fontes nativas **desse mesmo checkout**, sem AAR baixado de origem desconhecida, sem plugin Unity/Vulkan.

A API fornece projeção por olho, eye-from-head e malha de distorção. O renderer inverte as matrizes para construir raios por pixel e escreve cada olho no framebuffer. A API de distorção do Cardboard faz a composição final. Alterar a escala do framebuffer não altera o tamanho físico do display usado pelo cálculo das lentes. Há checagem de completude do framebuffer e logs de falha de shader.

Sem QR salvo, usa parâmetros oficiais Cardboard V1; isso é fallback, não calibração universal. QR é lido pela Activity oficial do SDK, com autorização de câmera.

## Coordenadas e limites

- Objetos: metros, mão direita, +X direita, +Y acima, -Z frente.
- ARCore usa `displayOrientedPose`, relativa à primeira pose após inicialização/recenter. Não existe fusão de orientação Cardboard/ARCore: quando ARCore está tracking, usa-se sua pose completa; na perda, há fallback. Transição/relocalização pode produzir salto e precisa ser melhorada.
- Panorama: amostra direção somente, ignorando a posição. Uma fotografia 360° não ganha profundidade com ARCore.
- Mãos: coordenadas normalizadas da imagem convertidas para a viewport, com profundidade relativa do MediaPipe **não usada como metros**. Linhas de 1 pixel por olho são visualização/diagnóstico 2D; calibração intrínseca, oclusão, posição métrica e disparidade precisam de outra etapa.
- Painel: interseção analítica com plano z=-2 ou cilindro de raio 2 m; CPU e shader usam o mesmo mapeamento UV. Resize mantém altura e adapta largura, limitada para conforto. Aspectos extremos podem ser comprimidos nesta alpha.

## Threads e propriedade

| Recurso | Dono |
|---|---|
| UI, permissões, bind de serviços | Main looper |
| EGL, renderizador, texturas, update do ARCore | GL thread |
| MediaPipe GPU/CPU + filtros | Um worker, inicialização/inferência/close na mesma thread |
| LLM | Worker separado na Activity de chat; sem execução no loop VR |
| Shizuku shell | Processo user service `:windows`, comandos AIDL restritos |

A reserva de hand tracking é adquirida antes da aquisição de imagem. Se há trabalho em voo, não se adquire novo frame. O bitmap e o array de conversão são reutilizados. Publica-se um pequeno array imutável por resultado; não se alega zero alocações. Imagens e MPImage são fechados, inclusive no caminho de erro. Landmarks velhos (>150 ms) não são desenhados.

`GLSurfaceView.onPause()` suspende o loop antes de pausar ARCore. SurfaceTexture é atualizada apenas no GL thread. Se o contexto EGL é perdido durante captura, a captura é interrompida: o token de consentimento não é reutilizado ilegalmente.

## Apps Android

Há **dois caminhos diferentes**, não intercambiáveis:

1. **Implementado:** MediaProjection pede consentimento visível; no Android 14+ pode capturar um app. Serviço foreground mantém notificação e botão Parar. Frames vão a uma Surface GPU e são amostrados no painel. Resize usa `onCapturedContentResize`. Nenhuma injeção de toque.
2. **Parcial:** Shizuku pede sua própria permissão e oferece um serviço shell com `am start --display` e `wm size -d` somente em display não primário. Falta criar/gerenciar displays privados compatíveis com cada Android, transporte Surface e entrada por display. Não há promessa de que `wm size` seja respeitado por todos os apps/OEMs.

Lançar um app pelo catálogo de apps instalados abre a Activity **fora do VR**. A UI explicita isso. Não se contorna `FLAG_SECURE`, DRM ou diálogos protegidos.

## Limites de segurança

Sem servidor remoto, analytics, autenticação, shell arbitrário, instalação silenciosa ou modelo baixado automaticamente. URI de catálogo é HTTPS. O AIDL é exposto pelo protocolo autenticado do Shizuku, não por um service exportado do launcher. AIDL valida componentes, display e dimensões e não passa strings ao interpretador de shell.

Modelos neurais importados devem ser de fonte confiável. Um modelo é dado consumido por bibliotecas nativas; isso também tem superfície de ataque. Não distribua modelos sem revisar licença, checksum e compatibilidade.
