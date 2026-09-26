# Desempenho e medições · alpha02

**Sem benchmark em aparelho nesta entrega.** Metas de latência/FPS/potência não são resultados. Shader/teste/build verde não mede uso real de GPU, bateria ou conforto.

## Implementação atual

- GLSurfaceView com render independente do worker de mãos; framebuffer Cardboard adaptativo, sem alterar ótica física.
- ARCore `LATEST_CAMERA_IMAGE`; Camera2 fallback. Fonte única compartilhada com inferência, não duas câmeras concorrentes.
- MediaPipe VIDEO, até duas mãos, GPU → CPU; inicialização/inferência/close no mesmo worker. Uma reserva em voo, sem fila crescente.
- YUV downsample em bitmap/array reutilizado; largura/cadência adaptadas (192–512), não aumentar resolução como substituto de filtro/calibração.
- Associação de pulsos e handedness como desempate; handedness não é probabilidade de cada joint. One Euro/Kalman/EMA/RAW no domínio, guard de median/outlier e reset temporal. Não empilhar filtros pesados por padrão.
- Amostras >150 ms não controlam o ponteiro. Previsão do ponteiro limitada a 18 ms, nunca usada para decidir gestos.
- Dock/janelas instanciados em lote; atlas limitado, upload de tiles só quando mudam. Não há promessa de zero alocações: layout, snapshots, filtros e AR queries ainda geram trabalho/alocações.
- Depth opt-in; aquisição/upload limitado em cadência, buffer reaproveitado quando dimensões não mudam. Sem depth em aparelhos sem suporte.
- GPU elapsed: quatro queries EXT, resultado só se disponível, sem espera bloqueante. Unsupported/disjoint = **N/D**, nunca zero inventado; queries afetadas por disjoint são invalidadas.
- Qualidade depende de histórico/cadência, bateria e térmica; limita mãos em calor severo e desliga câmera em crítico. Religar exige ação explícita. Não há guardian térmico certificado.
- Browser/captura são mutuamente exclusivos; encerrados no background/fechamento/minimização. LLM separado pode competir por GPU/RAM; não há evidência de ganho ao executá-lo junto com MR.

## O que cada número significa

| Métrica | Significado / exclusões |
|---|---|
| Callbacks/s e p95 | Intervalo entre callbacks GL, não frames efetivamente apresentados pelo SurfaceFlinger |
| CPU frame | Tempo decorrido dentro de `onDrawFrame`, incluindo chamadas feitas ali; não CPU exclusivo do processo |
| GPU | Query do trabalho de render no contexto GLES, se disponível; não display scanout/motion-to-photon |
| Pré-processamento | Conversão/downsample da imagem antes da inferência |
| Inferência | Chamada MediaPipe, sem confundir com toda a cadeia câmera→display |
| Filtro | Pós-processamento temporal/gestos do snapshot |
| Chegada da câmera | Só quando clock sensor REALTIME é conhecido; origem desconhecida → N/D |
| Timestamp ARCore da UI | Idade local desde aquisição de um frame novo; não é latência física de captura |
| Depth freshness | Idade local desde imagem depth com timestamp novo; validação temporal/calibração em dispositivo pendentes |
| RAM | Memória livre do sistema, não heap/RSS exclusivo do app |
| E2E/display | Não medidos; dependem de instrumentação externa/FrameTimeline |

Camera2 REALTIME e relógios do sistema devem ser conferidos no aparelho; `Frame.timestamp` do ARCore não é presumido comparável ao relógio do sistema. Números fora de domínio ou inválidos não podem virar latência negativa/zero falsa.

## Policy neural opcional

Sem pesos incluídos ou ganho comprovado. Importação até 1 MiB, entrada/saída float32 com shapes `[1,5]`/`[1,2]`; opt-in nos ajustes avançados. Determinística/térmica sempre prevalece.

```
input = [frame_ms / 33.3, hand_ms / 50, thermal / 6, battery_0_to_1, current_scale]
output = [suggested_scale, hand_interval_ms / 100]
```

A segunda saída está reservada. Escala limitada a `[0.60, baseline]`, execução ≤1 Hz, uma thread. Ainda executa no GL: medir custo e migrar para worker se demonstrar gargalo. Modelos inválidos não devem derrubar o renderer.

`scripts/train_performance.py` é ferramenta separada, não treino no app. Use dados reais, splits por **aparelho/sessão**, compare com controlador sem NN e não anuncie ganho a partir de dataset sintético.

## Protocolo físico necessário

1. Baseline VR sem câmera; Camera2 MR; ARCore; mãos CPU/GPU; depth; browser/captura; IA separada e combinada.
2. Mesma cena, temperatura inicial, bateria, lentes, Android e Hz; sessões de 10–20 minutos, pelo menos três SoCs.
3. Perfetto/FrameTimeline/SurfaceFlinger: p50/p95/p99, scheduling, missed frames, CPU/GPU, frequências e RSS/GC.
4. Tracking: jitter parado, erro de movimento, reacquisição/oclusão/cruzamento, luz/framerate, idade de amostra e erros de gesto.
5. Potência/corrente e temperatura, não somente indicador térmico; comparar modos de qualidade.
6. Motion-to-photon e câmera→overlay com alta velocidade/fotodiodo; relógio do app sozinho não basta.
7. Documentar modelo, build, revisão, visor e configuração junto com cada resultado.

Pendente: AHardwareBuffer/zero-copy de inferência, pacing Swappy/ADPF, Vulkan, foveation, timewarp/late latching próprio, calibração métrica câmera/olhos/mãos, ROI controlável, benchmark de NEON/libyuv e SLAM custom. Nenhuma dessas otimizações é declarada implementada só por haver biblioteca candidata.
