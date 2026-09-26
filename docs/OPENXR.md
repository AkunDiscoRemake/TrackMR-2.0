# OpenXR, Monado, Runtime Broker e WebXR

## Não confundir os componentes

- **Cardboard SDK** renderiza e corrige lentes do shell TrackMR. Não é runtime OpenXR.
- **OpenXR Loader** encaminha a API para um runtime descoberto. Não implementa tracking/compositor.
- **Runtime Broker oficial Khronos** permite ao usuário escolher o runtime Android. Não é substituído por provider próprio.
- **Monado** fornece o runtime/compositor. O suporte ao nosso perfil de visor e tracking precisa de port/driver.
- **TrackMR Runtime Companion** é o segundo APK: consulta o Broker, faz sondagem real de loader/instância/sistema e oferece acesso à sessão GLES do módulo `:openxr`. É um cliente, não um provedor de runtime.

O companion deliberadamente **não** declara `OpenXRRuntimeService`, caminho para uma `.so` inexistente nem metadata falsa. Instalar apenas os APKs TrackMR não faz jogos OpenXR funcionarem.

## Cliente de sessão implementado (`:openxr`)

- Inicialização Android do loader; extensões disponíveis; instance/system e requisitos GLES; contexto EGL e sessão.
- Espaços local/view e stage quando disponível; views estéreo; seleção de formato e swapchains; imagens/FBOs por olho.
- Actions e bindings de perfis, poses de controles, seleção e háptica; `XR_EXT_hand_tracking` opcional com validação de joints. Erro opcional de mão é registrado e desativa o tracker afetado.
- Eventos/estados de sessão; `xrWaitFrame → xrBeginFrame → locate/acquire/wait/render/release → xrEndFrame`; zero layers quando não deve renderizar. Verificador de protocolo é executado pelo cliente, não só pelo teste.
- Pedido de saída, drenagem de eventos até STOPPING (limite de dois segundos e erro registrado), destruição de recursos e relatório privado `openxr-session.txt` retornado à UI espacial.
- Matemática compartilhada com o renderer Cardboard e validação de shader/protocolo no CI.

**Limites:** a cena OpenXR é alvo de integração e pontos de tracking reais quando o runtime os fornece. O shell completo, browser, loja, MR e janelas ainda não são renderizados por esse backend. Nenhuma execução em runtime/HMD físico foi realizada nesta entrega. A thread de stop não consegue interromper um runtime travado dentro de wait de frame/swapchain; falhas parciais precisam de stress em runtime real. Não alegar conformidade ou compatibilidade de jogos a partir do build.

A entrada pelo shell pausa o Cardboard e a câmera antes de iniciar a Activity da sessão. Ausência de runtime é reportada; não é escondida por uma sessão simulada.

## Fluxo de runtime que ainda depende do port

```
Jogo OpenXR Android
  → loader oficial
  → Runtime Broker oficial → runtime escolhido pelo usuário
  → Monado APK out-of-process + libopenxr_monado.so
  → driver TrackMR (a implementar: Cardboard + ARCore + mãos)
  → compositor Monado (a integrar: ótica, display, sincronização)
```

Não fazer dois compositores brigarem pelo mesmo display nem duas sessões disputarem a câmera. O shell Cardboard e a sessão Monado precisam de uma política de ownership/foreground. Enviar uma pose por broadcast não resolve shared swapchains, fences, lifecycle de sessão, input/action spaces ou o protocolo IPC nativo do Monado.

## Como testar a sondagem

1. Instale APK oficial assinado do [OpenXR Android Broker](https://github.com/KhronosGroup/OpenXR-Android-Broker/releases), não uma cópia renomeada.
2. Compile/instale um Monado Android out-of-process de fonte confiável e ABI arm64 compatível. Fonte canônica: [Monado](https://gitlab.freedesktop.org/monado/monado).
3. Selecione Monado no Broker (escolha do usuário).
4. Abra TrackMR Runtime Companion e consulte/teste; depois abra **Sessão OpenXR** nele ou pelo Sistema do shell. Veja erro `XrResult` real caso não exista runtime ou HMD.
5. Antes de anunciar integração, executar `hello_xr` GLES, lifecycle e teste de renderização com visor. Passar criação de instância é insuficiente.

O app consulta `content://org.khronos.openxr.runtime_broker/openxr/1/abi/arm64-v8a/runtimes/active` e o equivalente system broker. Não escreve preferências privadas e não toma a authority do Khronos.

Referências normativas:
- [Loader e descoberta Android](https://github.com/KhronosGroup/OpenXR-SDK-Source/blob/main/specification/loader/runtime.adoc)
- [Broker oficial e orientações para fabricantes](https://github.com/KhronosGroup/OpenXR-Android-Broker)
- [Monado](https://monado.freedesktop.org/)

## Checklist do port Monado (pendente)

- Fixar revisão upstream e toolchain reproduzível, sem baixar APKs aleatórios.
- Adaptar driver HMD/`xrt_device` com ótica Cardboard, orientação e posição ARCore, tempos monotônicos coerentes.
- Input profiles, action bindings e espaços, offset pescoço/câmera/olhos, extensões de mão somente após reconstrução e validação.
- Integrar display/acquire/present com compositor Android; importar/exportar buffers e fences, não copiar frames via Binder.
- Publicar pacote runtime separado com service/metadata corretos e biblioteca de negociação real.
- Validar descoberta, permissões, suspend/resume, perda de câmera, limites térmicos, Android 10–15+, HMD tracking e frames.
- Rever licenças, CTS/conformidade e uso de marcas. Não alegar certificação.

## Navegador WebXR

A referência identificada é [Wolvic](https://github.com/Igalia/wolvic), mantido pela Igalia, com origens no Firefox Reality. Não é correto chamá-lo de um browser open source lançado pela Meta sem o link exato do projeto pretendido. Os SDKs web abertos da Meta também não são o mesmo que o código completo de um navegador.

Wolvic continua sendo link externo, não incorporação. Há um WebView espacial privado com teclado e ponteiro, mas `android.webkit.WebView` não substitui um navegador XR completo nem implementa aqui sessões immersive-vr. Para embarcar Wolvic:

1. Revisar MPL-2.0 e licenças de engine/terceiros, fixar revisão e planejar atualizações de segurança.
2. Criar backend/dispositivo para o runtime e actions/input disponíveis.
3. Testar `navigator.xr.isSessionSupported`, entrada/saída immersive-vr, poses, stereo, permissões e origem segura.
4. Integrar teclado, vídeo, áudio e interrupção/retomada sem sequestrar câmera/display.
5. Build separado: o browser é um projeto grande, não uma dependência Maven pequena.

A pesquisa de Monado-ALVR e monado-phone está em [DECISIONS](research/DECISIONS.md). Nenhum foi tratado como runtime standalone pronto só por compilar para Android.
