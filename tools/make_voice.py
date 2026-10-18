"""Pre-render Sofia's fixed lines with a free, offline neural Italian voice via sherpa-onnx.
Default: Kokoro-82M (Apache-2.0, commercial use allowed), Italian female voice if_sara (speaker id 35).
Each sentence is synthesized separately so the page knows exact sentence timings for lip-sync and subtitles.
Writes <out>/<id>.mp3 and <out>/voice.json (sentence timings + 50 Hz loudness envelope)."""
import json, re, subprocess, sys, os, wave, struct, math
tts_bin, model_dir, lines_path, out = sys.argv[1:5]
GAP = 0.28  # pause between sentences (s)
lines = json.load(open(lines_path)); os.makedirs(out, exist_ok=True); tmp = out + '/_tmp'; os.makedirs(tmp, exist_ok=True)
M = model_dir.rstrip('/'); KOKORO = 'kokoro' in os.path.basename(M); name = os.path.basename(M).replace('vits-piper-', '')
def synth(text, path):
    # Piper reads "VisionOttica" better split; the "Ray-Ban" brand needs the English pronunciation hint
    say = text.replace('VisionOttica', 'Vision Ottica').replace('Ray-Ban', 'Rèi Ben').replace('POV', 'P O V').replace('AI Lab', 'Ei Ai Lab').replace('Vision AI', 'Vision Ei Ai').replace('LED', 'led').replace('Hey Meta', 'Ehi Meta')
    if KOKORO:
        args = [tts_bin, f'--kokoro-model={M}/model.onnx', f'--kokoro-voices={M}/voices.bin', f'--kokoro-tokens={M}/tokens.txt',
                f'--kokoro-data-dir={M}/espeak-ng-data', '--kokoro-lang=it', '--sid=35', '--kokoro-length-scale=0.97']
    else:
        args = [tts_bin, f'--vits-model={M}/{name}.onnx', f'--vits-tokens={M}/tokens.txt', f'--vits-data-dir={M}/espeak-ng-data',
                '--vits-length-scale=1.06', '--vits-noise-scale=0.6']
    subprocess.run(args + [f'--output-filename={path}', say], check=True, capture_output=True)
def read_wav(p):
    w = wave.open(p); n = w.getnframes(); sr = w.getframerate(); sw = w.getsampwidth(); raw = w.readframes(n)
    if sw == 2: s = struct.unpack('<%dh' % n, raw); s = [v / 32768 for v in s]
    else: raise SystemExit('unexpected sample width')
    return s, sr
manifest = {}
for key, text in lines.items():
    sentences = re.findall(r'[^.!?]+[.!?]*', text)
    sentences = [x.strip() for x in sentences if x.strip()]
    all_samples, sr, timing = [], None, []
    for i, snt in enumerate(sentences):
        p = f'{tmp}/{key}_{i}.wav'; synth(snt, p); smp, sr = read_wav(p)
        # trim leading/trailing silence
        thr = 0.01; a = next((j for j, v in enumerate(smp) if abs(v) > thr), 0); b = len(smp) - next((j for j, v in enumerate(reversed(smp)) if abs(v) > thr), 0)
        smp = smp[max(0, a - int(0.02 * sr)):min(len(smp), b + int(0.04 * sr))]
        start = len(all_samples) / sr
        timing.append({'text': snt, 'start': round(start, 3), 'dur': round(len(smp) / sr, 3)})
        all_samples += smp + [0.0] * int(GAP * sr)
    # loudness envelope at 50 Hz, normalized 0..1
    hop = sr // 50; env = []
    for j in range(0, len(all_samples), hop):
        seg = all_samples[j:j + hop]; env.append(math.sqrt(sum(v * v for v in seg) / max(1, len(seg))))
    peak = max(env) or 1; env = [round(min(1, v / (peak * 0.6)), 2) for v in env]
    wavp = f'{tmp}/{key}.wav'
    w = wave.open(wavp, 'w'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
    w.writeframes(b''.join(struct.pack('<h', max(-32767, min(32767, int(v * 32767)))) for v in all_samples)); w.close()
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', wavp, '-af', 'loudnorm=I=-16:TP=-1.5', '-ac', '1', '-ar', '24000', '-b:a', '64k', f'{out}/{key}.mp3'], check=True)
    manifest[key] = {'file': f'{key}.mp3', 'text': text, 'sentences': timing, 'duration': round(len(all_samples) / sr, 3), 'env': env}
    print(key, manifest[key]['duration'], 's')
json.dump({'voice': 'Kokoro-82M if_sara (Apache-2.0) via sherpa-onnx' if KOKORO else 'Piper it_IT paola (medium) via sherpa-onnx', 'envRate': 50, 'lines': manifest}, open(f'{out}/voice.json', 'w'), ensure_ascii=False, separators=(',', ':'))
import shutil; shutil.rmtree(tmp)
