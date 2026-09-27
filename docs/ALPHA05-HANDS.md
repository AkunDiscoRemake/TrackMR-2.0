# Alpha05 — falha de memória das mãos e isolamento de ARCore

O usuário confirmou que **alpha04 não rastreou mãos no aparelho**. CI verde anterior não contradiz esse relato. Esta revisão corrige um defeito concreto de código e permite investigar a hipótese de conflito com ARCore sem depender de uma mão funcionando para entrar no modo de isolamento.

> **Feedback posterior:** o usuário confirmou que as mãos continuaram sem funcionar e que a câmera estava invertida. Veja a [revisão alpha06](ALPHA06-HANDS.md). O CI desta página não comprova detecção física.

## Download

**[Baixar alpha05 — APKs e proveniência](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36282093962/artifacts/10918324614)**

Instale `TrackMR-2.0-alpha05-arm64.apk` (Android 10+, arm64). O companion OpenXR e Shizuku não são necessários para detectar mãos. O modelo de mãos já está no APK.

- Fonte do binário: `ce8cfe5e13e0347b6067de85b700d424de605800`.
- Actions `36282093962`, job `108515748104`: **aprovado, 4m51s**.
- ZIP `TrackMR-2.0-debug-arm64`: 30.337.188 bytes; artifact `10918324614`; expira em `2026-10-11T00:23:03Z`.
- SHA-256 do ZIP, metadado do GitHub: `4406ef11c108a7dbbe3e0695ffdbbb95f70d5e507da2b9d6cbdcff1a58523948`.
- SHA de cada APK em `SHA256SUMS.txt` dentro do ZIP. Relatórios: artifact `10918204686` da mesma execução.
- O download dos relatórios neste sandbox falhou com EOF no endpoint Azure. A evidência disponível aqui é o estado aprovado das etapas do Actions e seus metadados; não alegamos inspeção local do APK ou dos HTMLs de resultados.

Os APKs são debug; builds podem ter chaves diferentes. Se Android recusar atualização por assinatura, preserve dados/modelos locais antes de considerar desinstalar. Desinstalar apaga dados do app.

## Defeito encontrado e corrigido

O tracker guardava um `Bitmap`, passava-o a `BitmapImageBuilder`, fechava o `MPImage` após `detectForVideo` e reutilizava o Bitmap no próximo frame. O container do MediaPipe **recicla esse Bitmap no close**. Assim, a próxima conversão podia chamar `setPixels` em um Bitmap já reciclado. A exceção era capturada, mas os resultados de mãos paravam. Este defeito independe de usar ARCore ou Camera2.

Agora `HandInputImage` mantém um buffer RGBA direto de tamanho exato. O worker escreve os pixels, cria um wrapper MPImage por inferência síncrona, fecha-o e só então reutiliza a memória. A ordem R/G/B/A é explícita, independente do byte order do host. Mudança de tamanho recria o buffer. Nenhum Bitmap é usado no caminho de entrada do detector.

**Não é zero-copy:** o JNI MediaPipe consultado copia pixels para seu ImageFrame nativo. A imagem de câmera continua sendo liberada antes da inferência. Não há medição física de ganho de latência, FPS, bateria ou calor.

Fontes verificadas (upstream v0.10.21):
- [BitmapImageContainer.close](https://github.com/google-ai-edge/mediapipe/blob/v0.10.21/mediapipe/java/com/google/mediapipe/framework/image/BitmapImageContainer.java): `bitmap.recycle()`.
- [ByteBufferImageContainer](https://github.com/google-ai-edge/mediapipe/blob/v0.10.21/mediapipe/java/com/google/mediapipe/framework/image/ByteBufferImageContainer.java): close sem reciclar o buffer.
- [packet_creator_jni.cc](https://github.com/google-ai-edge/mediapipe/blob/v0.10.21/mediapipe/java/com/google/mediapipe/framework/jni/packet_creator_jni.cc): `nativeCreateCpuImage` → `CreateImageFrameFromByteBuffer` → `CopyPixelData`.

O teste de regressão usa as dependências efetivamente resolvidas pelo Gradle, não uma implementação falsa desses containers.

## ARCore isolado, não acusado sem evidência

Na primeira abertura desta revisão, o app seleciona **Camera2 + MediaPipe CPU**, sem construir sessão ARCore. Essa migração não exige apagar dados nem entrar numa tela 2D. O passthrough MR e a orientação Cardboard continuam; nesse modo **não há SLAM/posição 6DoF, planos, âncoras ou depth ARCore**. A preferência de mãos ligadas/desligadas é respeitada.

Em **Tracking → Ativar ARCore 6DoF**, o usuário pode testar o caminho combinado. A escolha fica salva. O controle inverso retorna ao isolamento Camera2. CPU/GPU continua sendo uma escolha separada; não altere GPU e ARCore ao mesmo tempo ao comparar resultados.

Não foi comprovado que a causa no aparelho seja incompatibilidade ARCore/MediaPipe. ARCore prevê aquisição de imagens CPU para ML ([documentação oficial](https://developers.google.com/ar/develop/java/machine-learning)); a disponibilidade/compatibilidade no aparelho ainda precisa ser testada.

### Câmera e recuperação

- Preview ativo já não é considerado prova de que o detector recebeu imagem CPU.
- ARCore registra tentativas, entregas, `NotYetAvailable` e exceções reais separadamente; não repete inferência no mesmo timestamp de frame ARCore entregue.
- `NotYetAvailable` isolado durante aquecimento não é falha fatal. Após aquecimento, ausência prolongada de imagens CPU com tentativas recentes permite fallback para Camera2, mesmo com preview ativo.
- Zero mãos numa inferência concluída não provoca fallback. Worker ocupado sem novas tentativas de aquisição também não é atribuído à câmera.
- Troca espera o worker antigo drenar e o feed antigo fechar antes de iniciar outro. Camera2 mantém sua thread de callbacks viva enquanto uma abertura assíncrona está pendente. Falha de liberação bloqueia a nova abertura com erro, em vez de abrir dois donos.
- O motivo do fallback e a última exceção de aquisição entram no diagnóstico local. Não são enviados pixels, landmarks ou telemetria.

## Como observar sem tocar na tela

A linha de estado espacial exibe a fonte real (`Camera2`/`ARCore`), 3DoF/6DoF, contador YUV e estado das mãos, automaticamente. O controle XR continua por mãos; toque físico não foi reintroduzido. Diálogo de permissão do Android continua sendo do sistema.

Em Tracking/Diagnósticos e **Sistema → Copiar diagnóstico** há mais detalhes:

| Observação | Interpretação |
|---|---|
| Carregando modelo / MediaPipe indisponível | Inicialização do modelo/backend, antes da captura para inferência |
| YUV parado em 0 | Nenhuma imagem entregue ao tracker; ver aquisição e permissões |
| YUV cresce, falhas de tracking crescem | Conversão/inferência/pós-processamento está lançando exceção; mensagem inclui tipo e causa |
| Inferências crescem, brutas = 0 | Modelo executa, mas não encontrou mãos; investigar orientação, imagem, distância e iluminação |
| Brutas > 0, filtradas = 0 | Resultado foi rejeitado no pós-processamento |
| Resultado expirado | Resultado excedeu o limite de idade; não inventar mão nem prolongar contato indefinidamente |
| Filtradas > 0, sem seleção | Investigar confiança, alinhamento/raycast e abrir a mão antes da pinça, separadamente do detector |

Os botões físicos/controle de recuperação anteriores continuam opcionais. Não é necessário navegar para desativar ARCore no primeiro teste: isso já é o padrão desta revisão.

## Validação e limites

- Correção de buffer `162081d`: [Actions 36281686717](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36281686717), aprovado (5m04s).
- Revisão completa `ce8cfe5`: [Actions 36282093962](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36282093962). **Aprovado (4m51s)**, incluindo testes, APKs/lint, shaders/nativos e verificação do modelo no APK.
- `:app:testDebugUnitTest` agora faz parte obrigatória de `scripts/ci-build.sh`.
- `HandInputImageTest` (3 testes Robolectric): reproduz reciclagem/erro do Bitmap antigo, verifica **200 reutilizações do buffer e wrappers**, ordem dos canais, proibição de escrita durante uso, resize e recuperação após exceção.
- `CameraPipelineStateTest` (9 testes JVM): aquecimento, inicialização tardia do modelo, ausência/stall de imagens CPU, inferência ocupada, entregas sem mãos e exclusão de abertura durante liberação/pause/falha.
- Continuam testes core/xr/handtracking, shaders, matemática nativa, APKs/lint, hash e igualdade do modelo embutido.

**Os 200 ciclos são de transporte/lifecycle do buffer, não 200 inferências do modelo nativo nem frames de câmera real.** Robolectric não comprova funcionamento da câmera ou detecção no seu celular. Não houve teste físico nesta entrega.

### Aceitação física ainda pendente

1. Confirmar alpha05, permissão de câmera, Camera2/3DoF e CPU. Mostrar uma mão aberta, depois duas. Contadores YUV e inferências devem continuar crescendo por pelo menos 60 segundos.
2. Registrar se há mãos brutas/filtradas, overlay e ponteiro. Abrir dedos, fazer pinça, manter/arrastar e soltar; oclusão deve cancelar contato.
3. Só depois ativar ARCore com o mesmo backend CPU e iluminação. Comparar contadores/erros, não apenas preview ou SLAM. Caso volte sozinho para Camera2, registrar o motivo do fallback.
4. Alternar modos/reconectar, background/retorno e bloquear/desbloquear, inclusive durante início da câmera. Verificar ausência de `CAMERA_IN_USE`, imagem congelada, contato preso e callbacks após fechamento.
5. Medir pré/infer/filtro, idade e p95 junto do modelo de celular/Android. Testar GPU separadamente após estabelecer CPU como baseline.

Se ainda falhar, modelo do aparelho + Android e a linha de estado/diagnóstico identificam em qual etapa parou. Não declarar detecção resolvida no aparelho com base apenas no build.
