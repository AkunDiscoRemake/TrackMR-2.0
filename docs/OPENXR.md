# OpenXR, Monado, Runtime Broker e WebXR

## Não confundir os componentes

- **Cardboard SDK** renderiza e corrige lentes do shell TrackMR. Não é runtime OpenXR.
- **OpenXR Loader** encaminha a API para um runtime descoberto. Não implementa tracking/compositor.
- **Runtime Broker oficial Khronos** permite ao usuário escolher o runtime Android. Não é substituído por provider próprio.
- **Monado** fornece o runtime/compositor. O suporte ao nosso perfil de visor e tracking precisa de port/driver.
- **TrackMR Runtime Companion** é o segundo APK desta entrega: ajuda na instalação, consulta o Broker e faz uma sondagem real com loader, `xrInitializeLoaderKHR`, enumeração, `xrCreateInstance`, `xrGetInstanceProperties`, `xrGetSystem` e destruição. Isso NÃO testa criação de sessão, renderização ou compatibilidade de jogo.

O companion deliberadamente **não** declara `OpenXRRuntimeService`, caminho para uma `.so` inexistente nem metadata falsa. Instalar apenas os APKs TrackMR não faz jogos OpenXR funcionarem.

## Fluxo esperado, ainda parcialmente implementado

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
4. Abra TrackMR Runtime Companion e consulte/teste. Veja erro `XrResult` real caso não exista runtime ou HMD.
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

Nesta alpha há link para o projeto, não incorporação. `android.webkit.WebView` não substitui um navegador XR completo. Para embarcar Wolvic:

1. Revisar MPL-2.0 e licenças de engine/terceiros, fixar revisão e planejar atualizações de segurança.
2. Criar backend/dispositivo para o runtime e actions/input disponíveis.
3. Testar `navigator.xr.isSessionSupported`, entrada/saída immersive-vr, poses, stereo, permissões e origem segura.
4. Integrar teclado, vídeo, áudio e interrupção/retomada sem sequestrar câmera/display.
5. Build separado: o browser é um projeto grande, não uma dependência Maven pequena.

Se houver outro repositório específico da Meta desejado, é preciso identificá-lo antes de prometer esse port.
