# SmartTube Upmix PCM 5.1 — V0.1b probe

## Motivo desta versão

O teste real da V0.1 na TCL mostrou no log `UPMIX51` que a fonte era PCM16 estéreo
48 kHz, mas o upmix foi bloqueado antes do DSP porque o firmware não expôs um
dispositivo HDMI que anunciasse explicitamente PCM16 com máscara 5.1 (`0xfc`). O
SmartTube experimental portanto reproduziu o mesmo estéreo do app original.

A V0.1b muda somente a política de detecção/ensaio. Ela não altera o algoritmo de
upmix: `FL=L`, `FR=R`, `C=(L+R)/2`, `LFE=BL=BR=0`.

## Mudança de estratégia

Na V0.1, a ausência de anúncio HDMI/direct playback era um veto. Na V0.1b esses
dados passam a ser evidência de diagnóstico. Se `AudioTrack.getMinBufferSize` aceitar
PCM16 5.1 na taxa da fonte, o app permite que o pipeline configure seis canais e
tenta criar o `AudioTrack` real. A própria inicialização do `AudioTrack` é o probe
final.

Fluxo:

1. Fonte original precisa continuar sendo PCM16 estéreo, sem channel map e sem tunneling.
2. `getMinBufferSize(rate, CHANNEL_OUT_5POINT1, PCM16)` precisa retornar valor positivo.
3. Capacidades HDMI/direct playback são registradas, mas ausência/informação incompleta
   não bloqueia mais o teste.
4. O sink tenta criar `AudioTrack` PCM16 5.1.
5. Se `STATE_INITIALIZED` for obtido, o DSP permanece ativo.
6. Se criação/inicialização falhar, o código já existente desativa o upmix para esse
   sink e reconstrói a saída estéreo antes de consumir PCM do decoder.

Isso não força fisicamente eARC nem prova que a soundbar recebeu LPCM 5.1. Mesmo um
`AudioTrack` de seis canais pode ser remixado posteriormente pelo Android/firmware.
Por isso devem ser observados o log da rota real e, quando disponível, o indicador
da soundbar.

## Logs esperados

Em uma tentativa não anunciada pelo firmware:

```text
UPMIX51: PCM 5.1 capability unconfirmed: ...; attempting six-channel AudioTrack probe ...
UPMIX51: Stereo source detected; output PCM16 6ch; Upmix enabled
UPMIX51: Attempting PCM16 5.1 AudioTrack: 48000Hz mask=0xfc ...
```

Se funcionar:

```text
UPMIX51: AudioTrack initialized: PCM16 6ch 48000Hz mask=0xfc ...
UPMIX51: Processing PCM: first buffer converted ...
UPMIX51: Actual AudioTrack route: ...; track channels=6
```

Se a plataforma rejeitar:

```text
UPMIX51: AudioTrack 5.1 initialization failed - falling back to stereo
UPMIX51: Stereo fallback configured; upmix disabled for this sink
```

## Identidade da build

A build debug V0.1b usa sufixo de pacote `.upmix51b` e nome `SmartTube Upmix V0.1b`
para poder coexistir com a V0.1 e com o SmartTube original, inclusive quando a APK
for assinada por outra máquina/runner.

## Teste recomendado

Capture o log com:

```cmd
adb logcat -c
adb logcat -v threadtime -s UPMIX51:I
```

Depois abra um vídeo AAC/Opus 2ch 48 kHz. A primeira meta é confirmar simultaneamente:

- `Upmix enabled`;
- `AudioTrack initialized: PCM16 6ch`;
- `Processing PCM: first buffer converted`;
- `Actual AudioTrack route` com `track channels=6`.

Só depois disso vale avaliar separação acústica por canal. Para eliminar qualquer
DSP externo da TV/soundbar, a etapa seguinte pode usar uma build de diagnóstico que
roteia sinal para apenas um canal por vez.
