# Offline player regression fixture

`player-fit.mp4` is a generated, silent, four-second H.264 baseline 320×240 clip (~3 KB).
It has red/blue/yellow/cyan borders and a green center. No third-party media is included.
The instrumented test decodes it through ExoPlayer → MediaSession → MediaController →
Shinobi's real TextureView, rotates the locale-wrapped host and checks all four edge colors.
Debug-only: neither the clip nor the empty test-host activity ships in the release APK.

Recreate with FFmpeg:

```sh
ffmpeg -f lavfi -i 'color=c=green:s=320x240:r=15:d=4' \
  -vf 'drawbox=x=0:y=0:w=16:h=240:color=red:t=fill,drawbox=x=304:y=0:w=16:h=240:color=blue:t=fill,drawbox=x=0:y=0:w=320:h=16:color=yellow:t=fill,drawbox=x=0:y=224:w=320:h=16:color=cyan:t=fill' \
  -c:v libx264 -profile:v baseline -pix_fmt yuv420p -an -movflags +faststart player-fit.mp4
```

2.0 adds two generated silent H.264 baseline fixtures (no third-party content):
`player-landscape.mp4` (320×180, 180 seconds, navy), and `player-short.mp4`
(180×320, 45 seconds, purple). Both use 10 fps with a keyframe every second.
The seek regression uses all three timelines and early/middle/end targets.

```sh
ffmpeg -f lavfi -i 'color=c=navy:s=320x180:r=10:d=180' -c:v libx264 \
  -profile:v baseline -g 10 -pix_fmt yuv420p -an -movflags +faststart player-landscape.mp4
ffmpeg -f lavfi -i 'color=c=purple:s=180x320:r=10:d=45' -c:v libx264 \
  -profile:v baseline -g 10 -pix_fmt yuv420p -an -movflags +faststart player-short.mp4
```
