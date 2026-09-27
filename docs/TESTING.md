# Validação

## Automatizada

- `:core:test`: One Euro em repouso/ruído/gap, timestamps repetidos, Kalman em velocidade constante, descarte NaN, pinça/histerese, política térmica, percentis, mesh curva/plana.
- `:xr:test`: MR-first/fallback, dock hover, janelas/pins/limites, restauração e maximize reversível, relógios, térmica, notificações/recovery, orçamento de atlas/packet com teclado.
- `:handtracking:test`: filtros, spikes/timestamps, associação e gestos/histerese/conflitos/previsão, conforme casos em `handtracking/src/test`.
- `tests/openxr_protocol_test.cpp`: ordem de frame, zero layers, dois olhos, sequências inválidas e abort. É checker de protocolo, não mock de runtime/conformidade.
- `scripts/check_shaders.py`: extração, compilação e link dos shaders ESSL app/spatial/OpenXR com glslangValidator.
- `tests/native_math_test.cpp`: inversão, translação/quaternion, raios de painel e esfera, rejeição de interseções atrás/fora.
- Android assemble + lint em CI. CMake compila Cardboard e renderer contra NDK real; runtime liga loader OpenXR real.
- APKs verificadas com `apksigner`; checksums publicadas junto.

**Build/testes unitários aprovados não substituem teste no celular nem comprovam baixa latência.** Nesta alpha não há teste instrumentado de câmera, frames de referência ou hardware-in-the-loop.

## Roteiro manual obrigatório

| Área | Casos | Critério |
|---|---|---|
| Instalação | Android 10/12/14/15+, arm64; páginas 4/16 KiB; sem ARCore | Abre superfície XR, solicita câmera para MR; recusa mostra fallback espacial explícito |
| Cardboard | QR salvo/ausente/inválido; dois perfis | Projeção/distorção corretas, escala não altera ótica |
| Orientação | Yaw/pitch/roll, recenter, retorno de QR | Mundo estável sem eixo invertido; sem NaN |
| 360° | Dois ambientes, costura/polos | Imagens certas, foto sem parallax artificial |
| EGL | Home, bloqueio, rotação, pressão de memória | Recupera render; captura perdida é encerrada com segurança |
| Jogos | Volume+, botão A, toque; erro na sequência | Pontos respondem e Menu retorna |
| ARCore | Instalação, recusa de câmera, sem suporte, pouca luz | Fallback 3DoF e estado claro, sem crash |
| Mãos | CPU/GPU, oclusão, mão esquerda/direita, timestamps | Sem fila, sem ponteiro velho >150ms; nenhum gesto após perda |
| Captura | Recusa, um app, tela inteira, resize, sistema encerra | Notificação, stop, correto aspect; sem reutilizar consentimento |
| Proteção | FLAG_SECURE/DRM | Continua protegido; nunca tentar contornar |
| Shizuku | Ausente, negado, autorizado, morte do binder | Erro útil, sem promoção silenciosa ou comandos no display 0 |
| IA | Sem modelo, incompatível, sem espaço, RAM insuficiente, Activity fecha | Sem nuvem; erro claro; close ordenado |
| Térmica | Status alto, alternância rápida de carga | Escala/cadência limitadas, sem oscilação a cada frame |
| OpenXR | Sem Broker/runtime, ABI incorreta, runtime selecionado, READY/STOPPING/EXITING/LOSS, swapchain/hand failures | Erro real, frames/cleanup corretos, sem runtime falso; shell suspenso durante sessão |
| Browser | HTTPS inválido/redirect, toque/scroll/teclado, sem WebView, EGL/background | Sem crash/fonte órfã; câmera/mic/download negados; não anunciar WebXR |
| Depth | Sem suporte, imagens repetidas/atrasadas, UV/orientação, perto/longe | Opcional; sem oclusão fantasma; mapa correto validado fisicamente |
| Janelas | 5 abertas, pins mistos, teclado, hover rápido, maximize 100 vezes | Não exceder atlas/packet; fechar/minimizar fonte ativa encerra transporte |
| Screenshot | Sem Surface, confirmação expira, IO falha, fechar Activity durante PixelCopy | Sem foto antes da confirmação; sem item pendente/buffer vazado |
| Privacidade | Background/desligar câmera/retorno; fechar browser/capture; stop do sistema | Indicadores e fontes coerentes, projeção para mesmo enquanto serviço bound |

## Critérios de maturidade antes de release

Pelo menos três aparelhos de SoCs diferentes; sessões de 20 minutos; Perfetto; conforto ótico; teste de leitura/tamanho de UI dentro das lentes; auditoria de ciclo de vida/permissões; comparação de filtros com dados reais; política neural validada em aparelhos não usados no treino; scan de dependências/licenças; assinatura release e atualizações de segurança. Relatar modelo do aparelho, Android, visor/perfil, frequência, temperatura e revisão do código em cada resultado.

## Evidência de CI já obtida

- MR-first: `7c8177e`, Actions `36275949354`, aprovado.
- Cliente OpenXR/shaders/protocolo: `7b738e2`, Actions `36276368463`, aprovado.
- Browser/IA/depth/diagnósticos: `fe3070c`, Actions `36276988561`, aprovado (job 4m15s).
- Correções de lifecycle/depth/budgets: `7324be1`, Actions `36277281705`, aprovado (job 4m07s); [artefatos e proveniência](DELIVERY-alpha02.md).

Correções posteriores exigem o resultado da própria revisão no Actions. Estes registros não são resultados de teste de câmera/HMD, potência ou latência física.

## Regressões alpha03

- `ImageOrientationTest`: rotação 0/90/180/270 e remapeamento inverso, expiração adaptativa limitada (350 ms absoluto).
- `OwnedDisplayPolicyTest`: rejeita display principal, UID/pacote/nome alheios e componentes com argumentos injetados. Não executa shell nem simula permissões reais do OEM.
- `tests/curved_window_test.cpp`: UV/ray hit correspondentes à superfície plana/cilíndrica, tamanhos/raios e misses. Incluído no CI.
- Primeiro bloco mãos/MR: `9a203e0`, Actions `36278195081`, aprovado.
- UI/Shizuku/tipografia/diagnóstico: `99abb9f`, Actions `36278778732`, aprovado. Revisões posteriores exigem o respectivo build.

Falta executar a matriz física da [alpha03](ALPHA03.md), inclusive tocar cada canto da janela curva, alternar planos/curvas, parar Shizuku no meio de um comando, apps não redimensionáveis, foreground/background e legibilidade pelo visor. Testes de matemática/política não comprovam sucesso de `am start` ou input num aparelho.

- Alpha03 final: `594bcee`, Actions `36279394546`, aprovado (3m09s), incluindo limpeza de lançamento recusado e política de remoção de display.

## Alpha04

`4ecb024` / Actions `36281125532` aprovado (3m17s), incluindo hash fixado e igualdade byte a byte do Hand Landmarker dentro do APK. Novos testes de sampling YUV, captura de pinça/identidade/perda e fila de input. [Evidência e roteiro físico](ALPHA04-HANDS.md). Sem prova de execução de câmera/input no dispositivo.

## Alpha05

[Defeito, testes e matriz física](ALPHA05-HANDS.md). `:app:testDebugUnitTest` obrigatório no CI: 3 casos Robolectric com containers MediaPipe reais (erro de reciclagem antigo, 200 ciclos de wrapper/buffer, exceção/resize) e 9 casos JVM de saúde CPU/handoff. Não executam inferência nativa nem câmera de aparelho. Primeira correção `162081d`, Actions `36281686717`, aprovada; revisão completa `ce8cfe5` em Actions `36282093962`, aprovada (4m51s). O usuário confirmou falha física na alpha04, apesar do CI anterior aprovado.
