# Alpha04 — Hand Landmarker embutido e controle por mãos

> **Superada pela [alpha05](ALPHA05-HANDS.md):** o usuário confirmou falha das mãos. Foi encontrado uso de Bitmap reciclado pelo MediaPipe no caminho de inferência. A aprovação do CI abaixo não comprovava funcionamento físico.

## Modelo real, incluído no APK

O modelo oficial **MediaPipe Hand Landmarker float16 v1** é baixado no build pelo GitHub Actions. Nenhum download/importação de pesos é necessário no celular; inferência é local/offline após instalar o APK e conceder câmera.

- Fonte: `https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`
- Tamanho verificado: **7.819.105 bytes**.
- SHA-256 fixado em `scripts/hand_model.sha256`:
  `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1`
- O build valida ZIP, os dois modelos TFLite internos e o hash; depois compara **byte por byte** com `assets/hand_landmarker.task` dentro do APK final. Ausência, arquivo trocado ou corrupção falham o CI.
- O artefato inclui `HAND_MODEL.json` com proveniência. A inicialização verifica acesso/tamanho do asset e usa `HandLandmarker.createFromOptions`/`detectForVideo`, duas mãos, no worker de inferência. Não há landmarks simulados.
- O download direto deste sandbox foi bloqueado por TLS; download e verificação acima foram executados e aprovados no runner do Actions, não alegados como sucesso local.

## Interação pedida: mãos, não toque físico na tela

1. Permita a câmera pelo diálogo do Android (fora do visor). Esses diálogos protegidos continuam sendo do sistema.
2. Mostre a mão com indicador e polegar separados para armar a interação. Aponte com o indicador.
3. **Pinça** seleciona um controle espacial. Sobre browser/app, inicia um contato virtual.
4. **Mantenha a pinça e mova a mão** para arrastar; solte para liberar. O gesto fica vinculado à janela original mesmo se o ponteiro sair dela.
5. Dois dedos estendidos fazem rolagem sobre a janela apontada. Palma continua sendo o gesto do dock; duas mãos continuam disponíveis para manipular janelas fora de um arraste de conteúdo.

Tocar fisicamente na tela não seleciona itens do shell XR. Volume+/controle A permanecem apenas como recuperação/acessibilidade, não como requisito da interação. Os apps Android recebem eventos de entrada **gerados pela mão**: isso não exige tocar no telefone nem significa que o app Android passe a entender landmarks nativamente.

### Correções do caminho de entrada

- Hit/UV são calculados com a mão do **frame atual**, antes de despachar a ação. A pinça não seleciona mais o hover velho do olhar/frame anterior.
- IDs temporais atravessam filtro/gestos/UI; a ordem das duas mãos devolvida pelo detector não troca automaticamente a mão controladora.
- É necessário observar dedos separados antes de aceitar a primeira pinça. Mão que entra já fechada não dispara um clique involuntário.
- Perda/baixa confiança/troca da mão, conteúdo encerrado e background cancelam contatos. Nova mão não assume uma pinça antiga.
- Browser recebe DOWN/MOVE/UP/CANCEL contínuos. Shizuku usa Binder + InputManager do serviço shell para esses eventos, em vez de criar um processo `input` para cada movimento. Rolagem de mãos também usa eventos diretos.
- O serviço verifica propriedade do display a cada chamada, limita a entrada ao display do TrackMR e possui cancelamento de segurança após 800 ms sem renovação. Nenhum evento é enviado ao display principal.
- Fila limitada: movimentos intermediários são substituídos pelo mais recente; bordas de pressionar/soltar são preservadas. Overflow cancela, não deixa o app permanentemente pressionado.
- Método de sistema não disponível/permissão recusada é erro explícito. Não há fallback silencioso que exija tocar na tela. Back/texto e o caminho de recuperação por botão ainda usam comandos shell limitados.

## Otimizações concretas, sem ganho inventado

- Índices Y/U/V de crop, rotação e strides são pré-calculados e reutilizados. Saiu a divisão/rotação em ponto flutuante de cada pixel do laço de conversão.
- Compensação de luma usa tabela de 256 entradas.
- Buffer `Image` é liberado **antes** da inferência, evitando reter imagem da câmera durante toda a execução do modelo.
- Reserva não aceita imagens enquanto o backend ainda está indisponível/inicializando; continua um frame de inferência em voo.
- Arrays do overlay de uma/duas mãos são reutilizados. Saiu o `flatMap/asList` com boxing e array novo a cada frame GL.
- Predição visual limitada não desloca o alvo durante pinça/arraste. Decisão de gesto usa landmarks observados/filtrados.
- Cadência/resolução/térmica, GPU opcional com fallback CPU e relatórios de pré-processamento/inferência/filtro são mantidos.

Não significa “tudo otimizado”, 60 FPS de mãos ou latência zero. Snapshots, filtros, UI e eventos ainda alocam. **Nenhum benchmark físico de latência, potência, FPS ou temperatura foi realizado nesta sessão.** CPU/GPU e APIs Shizuku variam por aparelho/OEM.

## Testes e entrega

- `YuvSamplingPlanTest`: offsets das quatro rotações, crop, padding e planos com pixel stride 1/2.
- `HandPointerTest`: seleção única, alvo atual, arraste/liberação, perda da mão, troca da mão, reorder e encerramento do alvo.
- `PointerMailboxTest`: coalescência, limites, preservação das bordas e cancelamento no overflow.
- Suites core/xr/handtracking, geometria/curva/protocolo OpenXR, shaders GLES, APKs, lint e integridade do modelo passaram.

**[Baixar alpha04 + companion + Dev API](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36281125532/artifacts/10918952304)**

Instale `TrackMR-2.0-alpha04-arm64.apk`. Código: `4ecb02428554d9974a11b86d36aca51805f4f467`. [Actions 36281125532 aprovado](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36281125532), job `108513005058`, **3m17s**. Relatórios: [verification-reports](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36281125532/artifacts/10918967144). O commit posterior de documentação não altera esse binário.

O pacote contém `SHA256SUMS.txt`, `SOURCE_REVISION.txt` e `HAND_MODEL.json`. Expiração informada pelo GitHub: `2026-10-11T00:02:54Z` (10/10 à noite em Brasília). São APKs debug; certificados entre builds podem diferir. Se houver conflito ao atualizar, não remova a instalação anterior sem preservar modelos de IA importados/ajustes, pois a remoção apaga dados locais.

Ainda é necessário testar no celular: apontar/pinçar cada canto, segurar e arrastar listas, perder/reentrar a mão, trocar ordem das mãos, aquecer/resfriar, alternar CPU/GPU, parar Shizuku e colocar o app em background. Um app Android ativo por vez permanece o limite do transporte atual. OpenXR, reconstrução métrica, WebXR e demais pendências da alpha03 não foram declaradas concluídas.

Se falhar: **Sistema → Copiar diagnóstico** inclui versão, backend, frames, tempos e bytes do modelo no APK, sem imagens/landmarks. Aponte também se falha na detecção (esqueleto ausente), pinça do dock ou apenas no app Shizuku; são caminhos diferentes.
