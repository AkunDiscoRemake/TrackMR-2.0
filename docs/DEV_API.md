# TrackMR Dev API v1 — experimental

Módulo JVM `:dev-api`, sem dependência Android: `TrackMrGame`, `GameHost`, `XrFrame`, `XrInput`, `Scene`, `Primitive`.

```kotlin
class Demo : TrackMrGame {
    override val metadata = GameMetadata("meuestudio.demo", "Demo")
    private lateinit var host: GameHost
    override fun onStart(host: GameHost) { this.host = host; host.setEnvironment("loft") }
    override fun onFrame(frame: XrFrame) {
        host.submit(Scene(listOf(Primitive(Shape.SPHERE,
            Pose(0f, 0f, -2f, 0f, 0f, 0f, 1f), .2f, 0xff82efcc.toInt()))))
    }
    override fun onInput(input: XrInput) {
        if (input is XrInput.Select && input.pressed) host.haptic(10)
    }
    override fun onStop() {}
}
```

Há exemplo compilável `dev.trackmr.api.examples.OrbitExample`. O artefato pode ser gerado com `./gradlew :dev-api:jar`.

## Contrato

- Metros, espaço right-handed, -Z à frente; quaternion XYZW normalizado.
- `predictedDisplayTimeNs`: monotonic boot-time no host Android, não horário civil.
- Checar `positionalTracking` antes de exigir movimento físico. Nunca deslocar a foto 360° para simular profundidade inexistente.
- Entrada de mão são landmarks normalizados de câmera; não usar `z` como metros.
- Callback em thread do host; não fazer I/O ou inferência no `onFrame`.
- Sem permissão de câmera, instalação, shell ou rede implícita por jogo.

## Limite atual

É um **contrato fonte experimental**, não um motor/SDK de distribuição pronto. O host Android que converte `Scene` em comandos GL ainda está pendente. Os três minijogos desta alpha estão implementados diretamente no renderizador nativo e não carregam plugins via este contrato. Não há carregamento dinâmico de APKs, sandbox, ABI C estável ou ponte Unity/Godot. Não é uma API OpenXR.

Próxima etapa: host testável, pool de cena/draw commands, assets glTF, API de áudio/input, exemplo APK e testes de compatibilidade por versão. Não adicionar carregamento de código baixado sem modelo de segurança/assinatura.
