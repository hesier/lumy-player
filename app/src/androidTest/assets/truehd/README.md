# Synthetic TrueHD fixtures

These are generated sine waves, not excerpts from Plex media. Each fixture is
48 kHz, 0.08 seconds (96 access units, 40 samples per unit). FFmpeg's TrueHD
encoder uses a major sync every 16 units. Each channel has a different tone.

Generated with FFmpeg 8.x, using this Python snippet (run at the repository root):

```python
import subprocess
for channels, layout in [(1, 'mono'), (2, 'stereo'), (6, '5.1(side)')]:
    expr = '|'.join(f'0.1*sin(2*PI*{440 + 110*c}*t)' for c in range(channels))
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i',
                    f'aevalsrc={expr}:s=48000:d=0.08:c={layout}',
                    '-c:a', 'truehd', '-strict', '-2', '-f', 'truehd', '-y',
                    f'app/src/androidTest/assets/truehd/{channels}ch.thd'], check=True)
```
