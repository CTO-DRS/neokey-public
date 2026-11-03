#!/bin/bash
# make_promo.sh — 30s Play promo from the 8 screenshot keyframes.
# Two-pass to stay light on small CI boxes: (1) encode each scene as a short
# Ken-Burns segment (veryfast), (2) one xfade-chain + audio mux pass.
# Output: play/promo/neokey-promo-30s.mp4 (1080x1920@30fps H.264+AAC).
set -euo pipefail
cd "$(dirname "$0")/../.."   # repo root

python3 play/media/make_audio.py

S=play/screens
T=play/promo/tmp
OUT=play/promo/neokey-promo-30s.mp4
L=4.25   # seconds per scene
F=0.5    # crossfade
D=$(python3 -c "print(int(30*$L))")   # zoompan frames per scene
mkdir -p "$T"

for n in 1 2 3 4 5 6 7 8; do
  f=$(printf "%02d" $n)
  if   [ $((n % 2)) -eq 1 ]; then Z="min(1+0.07*on/$D,1.07)"
  else                            Z="max(1.07-0.07*on/$D,1.0)"; fi
  ffmpeg -nostdin -y -loop 1 -t $L -i $S/$f-*.png -filter_complex \
    "zoompan=z='$Z':d=$D:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=1080x1920:fps=30,format=yuv420p,setsar=1" \
    -an -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p "$T/seg$f.mp4" -loglevel error
  echo "seg$f ok"
done

ffmpeg -nostdin -y \
  -i $T/seg01.mp4 -i $T/seg02.mp4 -i $T/seg03.mp4 -i $T/seg04.mp4 \
  -i $T/seg05.mp4 -i $T/seg06.mp4 -i $T/seg07.mp4 -i $T/seg08.mp4 \
  -i play/promo/pad-30s.wav -filter_complex "
[0:v][1:v]xfade=transition=fade:duration=$F:offset=3.75[x1];
[x1][2:v]xfade=transition=fade:duration=$F:offset=7.50[x2];
[x2][3:v]xfade=transition=fade:duration=$F:offset=11.25[x3];
[x3][4:v]xfade=transition=fade:duration=$F:offset=15.00[x4];
[x4][5:v]xfade=transition=fade:duration=$F:offset=18.75[x5];
[x5][6:v]xfade=transition=fade:duration=$F:offset=22.50[x6];
[x6][7:v]xfade=transition=fade:duration=$F:offset=26.25[xv];
[xv]fade=t=in:st=0:d=0.6,fade=t=out:st=29.4:d=1.0,format=yuv420p[vout]" \
  -map "[vout]" -map 8:a -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p \
  -c:a aac -b:a 128k -shortest -movflags +faststart "$OUT" -loglevel error

rm -rf "$T"
echo "PROMO_OK $OUT ($(du -h "$OUT" | cut -f1), $(ffprobe -v quiet -show_entries format=duration -of csv=p=0 "$OUT")s)"
