# Open source, componentes externos e atribuições

**Não confundir dependência embarcada, integração parcial e candidato futuro.** Incluir dezenas de engines no APK prejudicaria justamente latência, tamanho, manutenção e segurança. Abaixo há mais de 30 projetos relevantes; somente os necessários à alpha entram no build.

## Dependências utilizadas nesta alpha

| Projeto | Versão/revisão | Uso | Licença / atenção |
|---|---|---|---|
| [Google Cardboard](https://github.com/googlevr/cardboard) | v1.30.0 / `6eea12f99ba825086838554d7702217d780282be` | Sensores, lentes, projeções e distorção; QR | Apache-2.0 no SDK usado. API Unity possui termos separados e está **desabilitada**. |
| [MediaPipe](https://github.com/google-ai-edge/mediapipe) | vision 0.10.21; genai 0.10.24 | HandLandmarker e LLM local | Apache-2.0 no código; revisar termos dos pesos separadamente. |
| [ARCore Android SDK](https://github.com/google-ar/arcore-android-sdk) | 1.48.0 | Posição/orientação e imagem de câmera | SDK/serviços Google têm termos próprios; não classificar o runtime ARCore como engine totalmente open source. |
| [Shizuku API](https://github.com/RikkaApps/Shizuku-API) | 13.1.5 | Permissão e user service AIDL | Apache-2.0; exige app/serviço Shizuku separado. |
| [OpenXR SDK / Loader](https://github.com/KhronosGroup/OpenXR-SDK-Source) | 1.1.36 | Sondagem no segundo APK | Licenciamento upstream Apache-2.0/MIT conforme arquivo; não implica certificação. |
| [TensorFlow Lite](https://github.com/tensorflow/tensorflow) | 2.16.1 | Consultor neural opcional | Apache-2.0 no código; nenhum peso de policy incluído. |
| [AndroidX](https://android.googlesource.com/platform/frameworks/support/) | Core 1.15.0, Activity 1.9.3, AppCompat 1.7.0 | Activity/permissões/SDK Cardboard | Apache-2.0; dependências transitivas resolvidas pelo Gradle. |
| [Material Components Android](https://github.com/material-components/material-components-android) | 1.12.0 | UI do leitor QR upstream | Apache-2.0. |
| [Protocol Buffers](https://github.com/protocolbuffers/protobuf) | 3.19.4 no Cardboard | Parâmetros de lente | BSD-3-Clause no código upstream. |
| [Kotlin](https://github.com/JetBrains/kotlin) | 2.0.21 | Linguagem, stdlib e build | Apache-2.0. |
| [Gradle](https://github.com/gradle/gradle) | 8.9 | Build / wrapper | Apache-2.0. Wrapper proveniente do checkout Cardboard. |
| [JUnit 4](https://github.com/junit-team/junit4) | 4.13.2 | Testes JVM, não embarcado no APK | EPL-1.0. |
| [Android NDK / LLVM](https://android.googlesource.com/platform/ndk/) | NDK 27.2.12479018 | C++/libc++, linker 16 KiB | Licenças upstream por componente. |
| Google Play Services Vision | 20.1.3 | Leitor QR do Cardboard upstream | Componente Google, termos próprios; não é anunciado como código aberto. |

O modelo HandLandmarker versão 1 é buscado do [bucket oficial MediaPipe](https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task). O script valida o arquivo e imprime SHA-256 para proveniência. Isso não é uma assinatura nem substitui a verificação jurídica dos termos do modelo. Pesos de chat devem ser escolhidos/importados pelo usuário com licença compatível; não são redistribuídos aqui.

## Integrações planejadas / referenciadas, NÃO embarcadas

| Projeto | Finalidade possível | Estado |
|---|---|---|
| [Monado](https://gitlab.freedesktop.org/monado/monado) | Runtime e compositor OpenXR | Driver/port TrackMR pendente; BSL-1.0 predominante, revisar componentes |
| [OpenXR Android Broker](https://github.com/KhronosGroup/OpenXR-Android-Broker) | Descoberta e escolha de runtime | Usa pacote oficial externo; nunca tomar authority do Khronos; licenças por arquivo/REUSE |
| [Wolvic](https://github.com/Igalia/wolvic) | Browser XR completo | Port pendente; MPL-2.0 e termos de terceiros |
| [GeckoView](https://mozilla.github.io/geckoview/) | Engine de navegador | Alternativa de engine; não habilita sozinho backend XR TrackMR |
| [Chromium](https://chromium.googlesource.com/chromium/src/) | Engine de navegador | Grande custo de build/manutenção; revisar licenças e atualizações |
| [Meta Immersive Web SDK](https://github.com/meta-quest/immersive-web-sdk) | Experiências WebXR | SDK web, **não código completo do Quest Browser**; avaliar quando houver browser |
| [immersive-web WebXR samples](https://github.com/immersive-web/webxr-samples) | Testes de sessões WebXR | Link no catálogo; suite futura de compatibilidade |
| [three.js](https://github.com/mrdoob/three.js) | Conteúdo/jogos WebXR | Para apps web no browser futuro |
| [Babylon.js](https://github.com/BabylonJS/Babylon.js) | Engine e conteúdo WebXR | Alternativa, não embarcar com outra engine sem motivo |
| [A-Frame](https://github.com/aframevr/aframe) | Conteúdo XR declarativo | Exemplo web futuro |
| [Godot](https://github.com/godotengine/godot) | Jogos e exportação OpenXR | Após validar runtime e bindings Android |
| [StereoKit](https://github.com/StereoKit/StereoKit) | UI/conteúdo XR | Avaliar port Android/OpenXR e licença da revisão escolhida |
| [Filament](https://github.com/google/filament) | Render PBR/mobile | Alternativa ao renderer mínimo, não segunda engine no loop atual |
| [Khronos glTF](https://github.com/KhronosGroup/glTF) | Formato de modelos | Contrato de assets futuro, hoje geometria procedural |
| [cgltf](https://github.com/jkuhlmann/cgltf) | Importador glTF leve | Candidato para modelos próprios |
| [meshoptimizer](https://github.com/zeux/meshoptimizer) | Otimização de malhas/assets | Pipeline offline futuro |
| [KTX-Software](https://github.com/KhronosGroup/KTX-Software) | Texturas GPU/KTX2 | Pipeline futuro; conferir suporte por GPU |
| [Basis Universal](https://github.com/BinomialLLC/basis_universal) | Compressão/transcoding | Alternativa para reduzir memória e download |
| [libyuv](https://chromium.googlesource.com/libyuv/libyuv/) | YUV/SIMD | Substituir conversão Kotlin somente após benchmark |
| [Oboe](https://github.com/google/oboe) | Áudio de baixa latência | Ainda sem subsistema de áudio nativo nesta alpha |
| [Resonance Audio](https://github.com/resonance-audio/resonance-audio) | Áudio espacial | Avaliar manutenção/compatibilidade antes de integrar |
| [Perfetto](https://android.googlesource.com/platform/external/perfetto/) | Profiling de CPU/GPU/frames | Ferramenta de medição, não biblioteca de app |
| [Android Games SDK](https://android.googlesource.com/platform/frameworks/opt/gamesdk/) | Swappy/ADPF | Pacing e performance hints futuros |
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | Backend local GGUF | Alternativa ao MediaPipe LLM, **não suportado atualmente** |
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | STT local | Voz futura, após orçamento de RAM/energia |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | STT/TTS offline | Alternativa futura; pesos/licenças independentes |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | Backend de inferência | Alternativa a TFLite, não duplicar runtimes sem medir |
| [OpenCV](https://github.com/opencv/opencv) | Calibração da câmera | Ferramenta/calibração offline; evitar biblioteca enorme no hot path |

Antes de adotar qualquer candidato, fixe revisão, leia LICENSE/NOTICE daquela revisão, avalie transientes, tamanho, ABI, segurança e compatibilidade. O link é um mapa de pesquisa, não atribuição de uso já realizado. GPL/LGPL, MPL, BSL, MIT, Apache e modelos com termos próprios não são intercambiáveis.

## Recursos visuais e código novo

- Os três PNGs da raiz vieram do commit inicial `66e28b7`. Autor/licença das imagens não foram declarados no repositório. Foram preservados; os JPEGs/ícone em `app/src/main` são derivados para empacotamento. **A licença Apache do código não concede direitos sobre essas imagens**. Confirmar direitos antes de distribuição pública/comercial.
- Orbes/materiais procedurais, UI e código novo desta implementação: Apache-2.0, sem recursos extraídos do Quest/Meta.
- Cardboard, ARCore, Android, OpenXR, Meta, Quest e demais nomes são marcas de seus titulares. Projeto independente, sem afiliação ou certificação implícita.
- O build integra o SDK Cardboard com seus avisos. Antes de release público, gerar SBOM/relatório das licenças de **todas** as dependências resolvidas e incluir avisos obrigatórios no app. Esta tabela é um inventário humano inicial, não uma auditoria jurídica completa.
