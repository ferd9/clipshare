import { useEffect, useState } from 'react';

const WAVEFORM_SAMPLES = 200;

/**
 * Forma de onda real del audio (pedido del usuario, referencia tipo Audacity) — se genera
 * enteramente en el navegador con la Web Audio API, sin tocar el backend: se decodifica el
 * mismo archivo que ya sirve /media/audio/** (público, ver SecurityConfig) y se reduce a
 * WAVEFORM_SAMPLES picos de amplitud (máximo absoluto por bloque) para dibujar como barras en
 * AudioTrimmer. Best-effort: si falla (códec no soportado por decodeAudioData, archivo muy
 * pesado, etc.) el trimmer sigue funcionando igual, solo sin la visualización.
 */
export function useWaveform(audioUrl: string | null) {
  const [peaks, setPeaks] = useState<Float32Array | null>(null);

  useEffect(() => {
    setPeaks(null);
    if (!audioUrl) return;

    let cancelled = false;
    const AudioContextClass = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    const audioContext = new AudioContextClass();

    (async () => {
      const response = await fetch(audioUrl);
      const arrayBuffer = await response.arrayBuffer();
      const audioBuffer = await audioContext.decodeAudioData(arrayBuffer);
      if (cancelled) return;

      // Un solo canal alcanza para la forma visual (no hace falta mezclar estéreo para esto).
      const channelData = audioBuffer.getChannelData(0);
      const blockSize = Math.max(1, Math.floor(channelData.length / WAVEFORM_SAMPLES));
      const result = new Float32Array(WAVEFORM_SAMPLES);
      for (let i = 0; i < WAVEFORM_SAMPLES; i++) {
        const start = i * blockSize;
        const end = Math.min(start + blockSize, channelData.length);
        let max = 0;
        for (let j = start; j < end; j++) {
          const abs = Math.abs(channelData[j]);
          if (abs > max) max = abs;
        }
        result[i] = max;
      }
      if (!cancelled) setPeaks(result);
    })()
      .catch(() => {
        // Silencioso a propósito: sin forma de onda el recorte sigue siendo usable (queda el
        // fondo liso de siempre) — no es un error que el usuario necesite ver.
      })
      .finally(() => {
        audioContext.close().catch(() => {});
      });

    return () => {
      cancelled = true;
    };
  }, [audioUrl]);

  return peaks;
}
