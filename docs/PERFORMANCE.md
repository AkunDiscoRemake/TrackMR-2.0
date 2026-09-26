# Latência, mãos e desempenho

## O que está implementado

- Pose Cardboard buscada **depois** da preparação de texturas/ARCore, perto do draw.
- Timestamp de previsão em `CLOCK_BOOTTIME`, o domínio exigido pelo SDK; horizonte limitado a 30 ms. Usa o período do display como estimativa — ainda não usa apresentação real do SurfaceFlinger.
- FBO com escala 0,60–1,00, recalculado somente ao mudar tamanho/escala/perfil.
- Shaders sem ray marching: uma consulta panorâmica, uma interseção de painel ou seis interseções esfera-raio. Materiais procedurais dos jogos.
- Sem decodificar panoramas/painel por frame; sem screenshot CPU na captura de apps.
- ARCore em `LATEST_CAMERA_IMAGE`, sem planos, profundidade ou iluminação quando não necessários.
- MediaPipe VIDEO, GPU primeiro / CPU como fallback, 1 mão para limitar custo. Inicialização GPU ocorre no mesmo worker da inferência.
- Câmera YUV → bitmap reutilizado com largura máxima 384; sem compressão JPEG. Medir se SIMD/libyuv supera este caminho Kotlin antes de integrar.
- Backpressure de capacidade 1 e descarte implícito antes de adquirir a imagem, em vez de fila de atraso crescente.
- One Euro adaptativo; Kalman de velocidade constante alternativo no módulo core. **Não se empilham ambos por padrão**: mais filtros podem aumentar atraso.
- Rejeição de timestamps repetidos/regressivos, NaN/Inf, reset após lacuna de 250 ms, limite de saltos; histerese e debounce de pinça relativos à largura da palma.
- Snapshot deixa de ser desenhado após 150 ms. Não há predição de mão além do frame nesta alpha: é preferível assumir o atraso a extrapolar mão errada.
- Estatísticas p50/p95 em janela de 240 frames; sorting só no diagnóstico de 1 Hz.
- Política com EMA e ajuste a cada 120 frames para reduzir oscilações; throttling adicional por temperatura. Sob status térmico severo a segurança continua ativa mesmo com ajuste automático desligado.

## Rede neural: experimental, sem marketing de ganho

O app aceita um TFLite de até 1 MiB com entrada `[1,5]` e saída `[1,2]`. O modelo **não está incluído**. `scripts/train_performance.py` treina um pequeno MLP com dados rotulados de sessões reais. Não há ganho comprovado; uma rede pequena ainda adiciona custo.

Contrato float32:

```
input = [frame_ms / 33.3, hand_ms / 50, thermal / 6, battery_0_to_1, current_scale]
output = [suggested_scale, hand_interval_ms / 100]
```

A segunda saída é reservada, não controla cadência nesta alpha. A escala neural nunca ultrapassa a permitida pelo controlador determinístico e nunca cai abaixo de 0,60. Inferência ≤ 1 Hz, 1 thread, fallback em ausência/erro. Um modelo com shape inválido não é carregado.

Para produzir dados úteis, faça varreduras controladas de escala/cadência, meça tempo de GPU, térmica, jitter e atraso sob a mesma carga, e rotule a melhor combinação segura. Separe treino/validação por **aparelho e sessão**, não frames adjacentes. Não use dados sintéticos para anunciar ganho real. Não usar NN para "aumentar FPS" falsificando estatísticas ou reduzindo tracking silenciosamente.

```sh
# Ambiente de treino separado, TensorFlow não faz parte do build Android.
python -m venv .venv
. .venv/bin/activate
pip install tensorflow==2.16.1 numpy
python scripts/train_performance.py session-train.csv session-validation.csv performance.tflite
# Importe o arquivo pelos Ajustes; habilite o consultor experimental.
```

## Como medir

O número `frame p95` na UI é intervalo entre callbacks do GL. **Não mede CPU exclusivo, GPU nem motion-to-photon.** InferenceMs começa depois da conversão YUV; não é latência completa da mão. Timestamp da câmera e `elapsedRealtimeNanos` precisam ter compatibilidade confirmada por OEM; frames fora da janela temporal são descartados.

Medições necessárias em hardware:

1. Perfetto: scheduling, CPU/GPU, frequência, SurfaceFlinger, FrameTimeline e gargalos de memória.
2. Baseline sem ARCore/mãos; depois 6DoF, mãos CPU/GPU, captura e cada combinação.
3. P50/p95/p99, frames perdidos, idade da amostra, jitter em mão imóvel, erro durante movimento rápido, corrente e status térmico em sessões de 10–20 minutos.
4. Motion-to-photon com câmera externa de alta velocidade/fotodiodo, não com `System.nanoTime` sozinho.
5. Testar 60/90/120 Hz, pouca luz, oclusão, troca de mão, retorno de background e bateria baixa.
6. Comparar bruto / One Euro / Kalman. Escolher parâmetros pelo compromisso entre atraso e jitter, não por quantidade de filtros.

## Próximas otimizações dependentes de medição

Choreographer/Swappy, GPU timestamps, late latching real, calibração intrínseca mãos/câmera, uso direto de buffers GPU quando suportado, libyuv/NEON, dupla mão com associação estável, ROI temporal explicitamente controlável, watchdog térmico de sessão, telemetria **local e opt-in**, dataset de política neural. Não há timewarp assíncrono próprio nem foveated rendering nesta alpha.
