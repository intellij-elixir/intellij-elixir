#!/bin/bash
# judge.sh <elixir> <otp> <out.tsv> [rows...]: each row in a fresh copy and a fresh process; ANSWER or the raise's class.
EX=$1; OTP=$2; OUT=$3; shift 3
HERE=$(cd "$(dirname "$0")" && pwd)
ROWS=${*:-$(ls "$HERE/rows" | sort -V)}
WORK=$(mktemp -d)
: > "$OUT"
for row in $ROWS; do
  cp -r "$HERE/rows/$row" "$WORK/$row"
  cmd=$(sed -n 's/^run: //p' "$WORK/$row/.row")
  ( cd "$WORK/$row" && mise exec elixir@"$EX" erlang@"$OTP" -- bash -c "$cmd" > out.log 2>&1 ; echo "exit $?" >> out.log )
  answer=$(grep -a 'ANSWER' "$WORK/$row/out.log" | tail -1 | sed 's/^ANSWER: //')
  exit=$(tail -1 "$WORK/$row/out.log")
  if [ -z "$answer" ]; then
    answer="$exit raises $(grep -a -o -E '\*\* \([A-Za-z.]+\)' "$WORK/$row/out.log" | head -1 | sed -E 's/\*\* \((.*)\)/\1/')"
  fi
  printf '%s\t%s\n' "$row" "$answer" >> "$OUT"
  rm -rf "${WORK:?}/${row:?}"
done
rm -rf "${WORK:?}"
