#!/bin/bash
# judge.sh <out dir>: Elixir's reads of every row on both legs, <out dir>/<leg>.tsv, the oracle for BooleanRowsHeavyTest.
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$1
mkdir -p "$OUT"
run() {
  leg=$1; shift
  work=$(mktemp -d)
  ( cd "$work" && unset X Y && mise exec "$@" -- elixir "$HERE/judge.exs" "$HERE/rows" "$work" > "$OUT/$leg.tsv" 2> "$OUT/$leg.err" )
  echo "$leg exit $? lines $(wc -l < "$OUT/$leg.tsv")"
  rm -rf "${work:?}"
}
run 120 elixir@1.20.4-otp-29 erlang@29.0.6 &
run 111 elixir@1.11.4-otp-24 erlang@24.3.4.6 &
wait
