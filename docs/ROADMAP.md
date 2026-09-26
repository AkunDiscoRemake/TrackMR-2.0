# Escopo entregue e pendências

A alpha03 evolui a transformação o projeto em MR-first com dock/janelas espaciais e preserva Cardboard, conteúdo e integrações úteis. **Não conclui os 147 extras nem todos os sistemas de um OS XR.** A matriz do README descreve o que existe; teste automatizado não equivale a validação física.

## P0 — bloquear afirmações de maturidade até validar

- Aparelhos físicos, perfis Cardboard, câmera/FOV/crop/rotação, hand mapping, depth UV e offset câmera/olhos.
- Latência E2E, jitter, frames apresentados, GPU, RAM, bateria e térmica sob carga e por 20 minutos.
- Ciclo de vida ARCore/Camera2/MediaProjection/WebView, background, perda EGL, encerramento do Android, permissões e bind concorrente.
- Sessão OpenXR em runtime compatível: eventos, perda de sessão/instância, waits, ações/háptica, EXT hands e limpeza parcial. Não testado em hardware.
- Transição 3DoF↔6DoF e relocalização; segurança do layout perto do usuário; acessibilidade/conforto real.
- Licenças/transitivas/SBOM, permissões e assinatura release; confirmar direitos das imagens originais.

## Funcionalidades prioritárias ainda incompletas

| Sistema solicitado | Falta |
|---|---|
| Runtime separado Monado + Broker | Driver/port HMD de telefone e compositor out-of-process; cliente OpenXR/companion não substitui runtime. Monado-ALVR/monado-phone pesquisados, não incorporados como solução standalone. |
| Shell integral sobre OpenXR | Portar dock, janelas, browser, MR e input à sessão; hoje a sessão é integração de API com cena de alvo/pontos. |
| Apps Shizuku | Validar em aparelho o novo display/input/curvatura Shizuku (um app ativo); completar múltiplos apps independentes, input contínuo/Unicode e compatibilidade por OEM. |
| Tracking completo | Métrica 3D/estéreo, joint confidence real, calibração, ROI, oclusão prolongada/cruzamento robusto, backend OpenCV/custom executável; contrato de backend não é implementação. |
| Gestos extensíveis | Parte dos reconhecedores existe; bindings completos de back/home/confirm/cancel/env/screenshot, editor e conflito/prioridades generalizados ainda faltam. |
| MR/SLAM | Mapa persistente, malhas/objetos, piso/parede/teto semânticos, anchors persistentes, guardian confiável, sombras e oclusão de mãos/UI. |
| Marketplace | Manifestos/pacotes assinados, compatibilidade verificável, screenshots/trailers reais, download retomável, instalação/update com confirmação, segurança e recuperação. |
| Browser/WebXR | Abas/janelas independentes, engine WebXR compatível, 180/360/estéreo, fullscreen e integração Wolvic. WebView atual não fornece isso. |
| Ambientes | Sete tipos, GLB/glTF/KTX2, skybox/lightmap/LOD, personalização/importação; há dois panoramas originais. |
| Áudio | HRTF/head-world locking, zonas, ducking e áudio de objetos. Sons UI estéreo não equivalem a áudio 3D completo. |
| Performance | Vulkan, AHardwareBuffer para inferência, foveation, pacing, timewarp/late latching e benchmarks físicos. |
| Extras | STT/TTS, vídeo/recording, notificações Android autorizadas, downloads/storage manager, guardian/calibração avançada, media controls, backup/restore, objetos editáveis e health monitor de produção. |
| Dev API | Host/plugin IPC/transporte de cena, APK de exemplo interoperável e SDK versionado; existem contratos v1. |

## Princípios para continuar

Compilar, testar, corrigir, medir e revisar regressões a cada etapa. Não aumentar dependências/resolução sem dados; não ligar todos os subsistemas por padrão. Preservar fallback espacial e transparência sobre capacidade real. Novo código de rede/download deve exigir confirmação/verificação, sem telemetria/câmera/landmarks enviados por padrão.
