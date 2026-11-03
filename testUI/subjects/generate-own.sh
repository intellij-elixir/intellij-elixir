#!/usr/bin/env bash
# Generates and compiles the testUI subject `own` outside this repository, for `-PtestUiSubject=<root>/own`:
#   generate-own.sh <root>      writes <root>/own and <root>/own-mix
# Its deps are the ones `own.mix.lock`, beside this script, pins; `mix deps.get --check-locked` fails if they would move.
# Elixir and Erlang are the homes `./gradlew resolveElixirErlangSdks` wrote to build/elixir-erlang-sdks.properties, the
# ones `testUI` gives the IDE, so the SDKs that compile the subject are the SDKs that analyse it.
# `phx.new` and `phx.gen.live` write a LiveView and a schema. They write no Logger call and no module imported by another,
# so those are added by hand.
set -eu
root=${1:?root}
here=$(cd "$(dirname "$0")" && pwd)
props=$here/../../build/elixir-erlang-sdks.properties
if [ ! -f "$props" ]; then
  echo "generate-own.sh: $props not found; run ./gradlew resolveElixirErlangSdks first" >&2
  exit 1
fi
elixir_home=$(sed -n 's/^elixir\.sdk\.path=//p' "$props")
erlang_home=$(sed -n 's/^erlang\.sdk\.path=//p' "$props")
if [ -z "$elixir_home" ] || [ -z "$erlang_home" ]; then
  echo "generate-own.sh: $props has no elixir.sdk.path or erlang.sdk.path; run ./gradlew resolveElixirErlangSdks" >&2
  exit 1
fi
# `mix` starts with `#!/usr/bin/env elixir`, so Elixir's bin goes in front of Erlang's.
export PATH=$elixir_home/bin:$erlang_home/bin:$PATH
elixir --version
if [ -e "$root/own" ]; then
  echo "generate-own.sh: $root/own exists; remove it or pick another root" >&2
  exit 1
fi
mkdir -p "$root"
# Absolute, as the script changes directory before Mix reads its homes.
root=$(cd "$root" && pwd)
export MIX_HOME=$root/own-mix HEX_HOME=$root/own-mix/hex MIX_ENV=dev
cd "$root"
start=$(date +%s)

mix local.hex --force --if-missing
mix local.rebar --force --if-missing
mix archive.install hex phx_new 1.8.13 --force
mix phx.new own --no-install
cd own
cp "$here/own.mix.lock" mix.lock
mix deps.get --check-locked
mix phx.gen.live Catalog Item items name:string count:integer

# After the pipeline, as a route takes only the pipelines piped through above it.
awk '{ print } /^  scope "\/", OwnWeb do$/ { root = 1 } /^  end$/ { root = 0 }
  root && /^    pipe_through :browser$/ { print "    live \"/items\", ItemLive.Index, :index\n    live \"/items/new\", ItemLive.Form, :new\n    live \"/items/:id\", ItemLive.Show, :show\n    live \"/items/:id/edit\", ItemLive.Form, :edit"; found++ }
  END { exit found != 1 }' \
  lib/own_web/router.ex >router.ex.new || {
  echo "generate-own.sh: no single \`pipe_through :browser\` in \`scope \"/\", OwnWeb do\` in lib/own_web/router.ex to add the item routes after" >&2
  exit 1
}
mv router.ex.new lib/own_web/router.ex

mkdir -p lib/own/text lib/own_web/live
cat >lib/own/text/words.ex <<'EOF'
defmodule Own.Text.Words do
  @moduledoc "Splits text into words."
  require Logger

  @doc "The words of `text`."
  def words(text) do
    Logger.debug("splitting")
    String.split(text, ~r/\s+/, trim: true)
  end

  @doc "How many words `text` has."
  def count(text, minimum \\ 0) do
    max(length(words(text)), minimum)
  end
end
EOF
cat >lib/own/text/summary.ex <<'EOF'
defmodule Own.Text.Summary do
  @moduledoc "Uses `Own.Text.Words` through an import."
  import Own.Text.Words

  def summary(text) do
    local_prefix() <> Integer.to_string(count(text))
  end

  defp local_prefix, do: "words: "
end
EOF
cat >lib/own_web/live/probe_live.ex <<'EOF'
defmodule OwnWeb.ProbeLive do
  use OwnWeb, :live_view

  import Own.Text.Words

  alias Own.Catalog.Item

  @impl true
  def mount(_params, _session, socket) do
    socket = if connected?(socket), do: assign(socket, :live, true), else: assign(socket, :live, false)
    {:ok, assign(socket, :words, words("a b"))}
  end

  def item, do: %Item{name: "a"}

  def path, do: ~p"/items"

  @impl true
  def render(assigns) do
    ~H"""
    <p>{length(@words)}</p>
    """
  end
end
EOF

mix compile
echo "own: compiled in $(($(date +%s) - start)) s"
