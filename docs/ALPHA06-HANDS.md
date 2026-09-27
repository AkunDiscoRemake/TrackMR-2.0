# Alpha06 — orientação Camera2 e validação do detector

O usuário reportou **mãos ainda sem funcionar e câmera invertida na alpha05**. A correção de ownership do Bitmap da alpha04 não bastou. Esta revisão não apresenta build/lint como prova de funcionamento no aparelho.

## Download e execução validada

**[Baixar alpha06 — APKs e proveniência](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36284687600/artifacts/10920341568)**

Instale `TrackMR-2.0-alpha06-arm64.apk`. O companion OpenXR e Shizuku não são requisitos de detecção de mãos. Modelo já incluído; não importar pesos.

- Fonte: `d008b6cce681f52f9c7969f6c010b47a33490ca6`.
- [Actions 36284687600](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36284687600): **ambos os jobs aprovados**.
- Build/testes/lint/APKs: job `108523089822`, 5m09s.
- Inferência Android nativa: job `108523089968`, 5m27s; um teste instrumentado, **0 falhas, 0 erros, 0 ignorados**, 49,312 s incluindo geração de frames/emulação.
- ZIP `TrackMR-2.0-debug-arm64`: artifact `10920341568`, 30.374.866 bytes, expiração `2026-10-11T01:14:52Z`.
- SHA-256 do ZIP informado pelo GitHub: `9e301f60fc6d1778792dc795325d87f3cc0c828c47c2649fb84b0742e35fa0f1`.
- Relatórios nativos: artifact `10920720372`; JVM/lint: `10920610807`. As anotações públicas do job registram resultados por rotação; não dependem de alegar inspeção local do APK.

APKs debug podem ter assinatura diferente da instalação anterior. Se Android recusar atualização, preserve dados/modelos locais antes de considerar desinstalar; desinstalação apaga dados.

## Orientação: dois contratos diferentes

O preview OES do Camera2 recebe `SurfaceTexture.getTransformMatrix()`. Essa matriz já inclui a orientação do sensor e a inversão entre convenções GL/buffer. O código anterior multiplicava por outra rotação `sensor - display`, repetindo a compensação do sensor e deixando preview diferente do input do detector.

- Preview: matriz do SurfaceTexture × compensação **apenas do display** (`-display`). Sem espelhar a câmera traseira.
- ImageReader YUV: os pixels estão no referencial do sensor; mantém rotação **sensor - display** e transformação inversa dos landmarks.
- `CameraOrientation` centraliza esses contratos. Os testes verificam cantos/pontos assimétricos nas 16 combinações de sensor/display (0/90/180/270), comparando o preview ao caminho CPU.
- Em Android 12+, solicita `SCALER_ROTATE_AND_CROP_NONE` quando suportado, evitando outra rotação automática do HAL. Autofoco só seleciona um modo anunciado pela câmera.
- O backend ARCore mantém sua transformação própria, não recebe a correção Camera2.

Referências de implementação Android consultadas:
- [CameraUtils.getRotationTransform](https://github.com/LineageOS/android_frameworks_av/blob/lineage-22.2/camera/CameraUtils.cpp), espelho AOSP.
- [GLConsumer.computeTransformMatrix](https://github.com/LineageOS/android_frameworks_native/blob/lineage-22.2/libs/gui/GLConsumerUtils.cpp), espelho AOSP.

A geometria está testada, mas a orientação final no telefone/visor do usuário ainda precisa ser confirmada.

## Entrada e inicialização das mãos

O tracker agora usa o caminho **Bitmap ARGB_8888 documentado para HandLandmarker Android**. Cada MPImage recebe um **Bitmap novo**, fechado/reciclado após a inferência síncrona. Não se reutiliza o Bitmap reciclado da alpha04. O caminho direto RGBA fica restrito aos testes de storage; não é o default do tracker.

A conversão YUV mantém offsets/strides/crop e downsampling. Há um array ARGB reutilizado, mas o Bitmap por frame implica alocação/cópia: **não é uma alegação de zero-copy, zero alocação ou ganho medido**. A métrica de inferência inclui a preparação do wrapper/Bitmap neste caminho.

Antes de liberar frames ao tracker, executa três inferências locais com uma pequena imagem de referência do exemplo oficial MediaPipe, uma vez por delegate/processo. O resultado exige mão com 21 pontos. Depois o grafo é **fechado e recriado**, sem publicar landmarks, gestos ou contadores desse teste. Não é uma demonstração de mãos falsas; o visor só recebe resultados da câmera.

- `Modelo OK BITMAP`: o detector executou a referência no próprio aparelho. Não comprova câmera/orientação/foco ou interação.
- Falha de modelo/JNI/ABI: erro explícito. `LinkageError` passa a ser tratado na inicialização, não fica como “carregando” indefinidamente.
- GPU continua opcional, com fallback CPU para falhas de inicialização convencionais. Não é possível recuperar um SIGABRT nativo com `catch` Java.
- O relógio de validade é lido **depois** do snapshot, evitando rejeitar como “futuro” um resultado concluído durante o update da câmera.
- Limites de amostra e proteção térmica continuam; não mantém ponteiros velhos para fingir rastreamento.

## Primeira abertura / interface

Uma migração única na alpha06 liga mãos e esqueleto e seleciona **Camera2 + CPU, sem ARCore**. Não apaga arquivos/modelos/layout. A autorização de câmera do Android continua necessária. Mudanças explícitas de preferências após essa migração persistem; ARCore/6DoF pode ser reativado em Tracking.

Três painéis espaciais separados substituem o relatório espremido numa única textura de 256 pixels:
1. **Câmera:** fonte, 3DoF/6DoF e quantidade YUV entregue.
2. **Modelo:** autoteste, inferências ou erro.
3. **Mãos:** quantidade bruta/filtrada, pausa térmica, expiração ou instrução de pinça.

Não é necessário conseguir clicar com a mão para ver esses estados. Toque na tela não foi reintroduzido. Botões físicos/controle de recuperação anteriores continuam opcionais.

## Validação registrada

- Testes JVM/Robolectric: contratos de rotação, cores RGBA→ARGB, 20 Bitmaps independentes reciclados corretamente, 200 ciclos do storage RGBA, câmera/handoff, filtros/input e regressões anteriores.
- APK arm64, lint, shaders/nativos e verificação byte a byte do modelo continuam obrigatórios no CI.
- Novo módulo **`tracking-validation`** compila o tracker de produção, recebe imagens Android reais via ImageReader/ImageWriter e chama o JNI do MediaPipe. Não usa detector mockado nem landmarks pré-gravados.
- Roteiro desse teste: seis frames YUV com mão por rotação + um frame preto sem mão, nas quatro rotações; exige 21 pontos finitos e saída filtrada, e verifica que o autoteste não preenche `latest`.

### Falhas do ambiente que NÃO são sucesso de teste

1. Emulador x86_64 API29: dependência sem `libmediapipe_tasks_vision_jni.so` para x86_64.
2. API30 Google APIs com tradução ARM64: aborto do tradutor em `intrinsics_impl_x86_64.cc:86`, `CHECK failed: 524288 == 0`, SIGABRT. Ocorreu com entrada RGBA e Bitmap. **Não demonstra que Bitmap/RGBA ou o celular sejam a causa desse crash.**
3. **API35 Google APIs: aprovado** na execução acima. Executou a biblioteca ARM64 real por `libndk_translation.so`; ABI anunciada `x86_64,arm64-v8a`. Os abortos anteriores permanecem registrados e não contam como inferências aprovadas.

Resumo das anotações do teste aprovado:

```text
PASS rotation=0:   6 YUV frames detected real landmarks; black frame=0; OK BITMAP
PASS rotation=90:  6 YUV frames detected real landmarks; black frame=0; OK BITMAP
PASS rotation=180: 6 YUV frames detected real landmarks; black frame=0; OK BITMAP
PASS rotation=270: 6 YUV frames detected real landmarks; black frame=0; OK BITMAP
```

São **24 frames positivos + 4 frames pretos negativos**, além do autoteste inicial. Isso valida conversão → JNI/modelo → landmarks → filtro em Android emulado, não a câmera HAL física, a seleção por pinça no visor ou a latência do celular.

### Pendente no aparelho

Confirmar modelo do telefone/Android; se a inversão era 90°, 180° ou espelhamento; conferir câmera, contador YUV, autoteste e inferências. Testar mão aberta/pinça, perda/reentrada, CPU antes de GPU, Camera2 antes de ARCore, retorno de background e sessões longas. Não há benchmark físico de latência/FPS/temperatura nem teste com o aparelho do usuário.

## Proveniência da referência

Imagem de 33.051 bytes, SHA-256 `7584b748aa0c57a8cce3acd9e40149f5d4d7317f7db47c8a5a5f4a8fba9090ec`, revisão `c2518ec444c3a3a99689e5d31eddadc240c83a0c` do MediaPipe Samples. Caminho, crédito e finalidade em `app/src/main/assets/HAND_SELF_TEST.txt`, `tracking-validation/README.md` e `THIRD_PARTY.md`. Processamento local, sem upload de câmera/landmarks.
