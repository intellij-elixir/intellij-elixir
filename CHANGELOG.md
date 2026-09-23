# Changelog

## [Unreleased]

### Breaking changes

- [#4038](https://github.com/intellij-elixir/intellij-elixir/pull/4038) [@joshuataylor](https://github.com/joshuataylor)
  - **The minimum supported IDE is now 2026.1 (build 261.22158.277), raised from 2025.3.** The plugin
    ships against 2026.1.5 and supports 2026.1 through 2026.2. Update to a 2026.1 or newer IDE to keep
    receiving plugin updates.

    > 2026.1 was released in March 2026, 2026.2 was released in July 2026.

    This allows us to integrate with Mise plugin directly for example, which is only available in 2026.1 (261) and later.

### Enhancements

- [#4169](https://github.com/intellij-elixir/intellij-elixir/pull/4169) [@sh41](https://github.com/sh41)
  - **Completion now inserts a call's parameters as editable, tabbable placeholders instead of an empty
    `()`.** Refs [#4150](https://github.com/intellij-elixir/intellij-elixir/issues/4150).

- [#4134](https://github.com/intellij-elixir/intellij-elixir/pull/4134) [@georgeguimaraes](https://github.com/georgeguimaraes)
  - **The plugin icon is now the new official Elixir logo.**

- [#4126](https://github.com/intellij-elixir/intellij-elixir/pull/4126) [@sh41](https://github.com/sh41)
  - **Decompiled `.beam` files now show full function bodies, including private functions in large modules and
    functions with long bodies or many clauses.** Refs [#4122](https://github.com/intellij-elixir/intellij-elixir/issues/4122).

- [#4115](https://github.com/intellij-elixir/intellij-elixir/pull/4115) [@sh41](https://github.com/sh41)
  - **Decompiled `.beam` files show real parameter names instead of `x0` for most Elixir functions with default
    arguments.** Refs [#4046](https://github.com/intellij-elixir/intellij-elixir/issues/4046).

- [#4090](https://github.com/intellij-elixir/intellij-elixir/pull/4090) [@sh41](https://github.com/sh41)
  - **Syntax the module's Elixir release rejects, such as `..` before 1.14 or `foo//bar` from 1.12, is now reported as an
    error.** Refs [#4080](https://github.com/intellij-elixir/intellij-elixir/issues/4080).

- [#4089](https://github.com/intellij-elixir/intellij-elixir/pull/4089) [@sh41](https://github.com/sh41)
  - **Constructs that Elixir rejects, such as `:foo.Bar`, `[foo 1, 2]`, `fn x end`, `% {}` and `"\u{D800}"`, are now
    reported as errors.** Refs [#4080](https://github.com/intellij-elixir/intellij-elixir/issues/4080).

- [#4088](https://github.com/intellij-elixir/intellij-elixir/pull/4088) [@sh41](https://github.com/sh41)
  - **Words and numbers that Elixir's tokenizer rejects, such as `Foo?`, `foo@bar`, `1var`, `__block__` and
    `foo:bar`, are now reported as errors.** Refs [#4080](https://github.com/intellij-elixir/intellij-elixir/issues/4080).

- [#4084](https://github.com/intellij-elixir/intellij-elixir/pull/4084) [@sh41](https://github.com/sh41)
  - **Elixir's Unicode security errors are now reported for the module's Elixir version: bidirectional and line
    break characters in comments and strings, and mixed-script or restricted identifiers.** Refs
    [#4080](https://github.com/intellij-elixir/intellij-elixir/issues/4080).

- [#4081](https://github.com/intellij-elixir/intellij-elixir/pull/4081) [@sh41](https://github.com/sh41)
  - **Every source file shipped with Elixir 1.11 through 1.20, and every valid snippet in Elixir's parser tests,
    now parses without error.**

- [#4035](https://github.com/intellij-elixir/intellij-elixir/pull/4035) [@sh41](https://github.com/sh41)
  - **The plugin now parses Elixir 1.11 and 1.12 correctly, and CI covers both.**

- [#3983](https://github.com/intellij-elixir/intellij-elixir/pull/3983) [@sh41](https://github.com/sh41)
  - **Elixir's `Tools` menu actions are grouped under `Tools > Elixir`.**
  - **New `Tools > Elixir > Sync Dependency Libraries` Action**. Rescans every content root's
    `deps` and `_build`.
  - **Mix libraries are re-checked when a project opens**, picking up changes made while it was
    closed.

- [#3954](https://github.com/intellij-elixir/intellij-elixir/pull/3954) [@sh41](https://github.com/sh41)
  - **`~E` sigils now get EEx highlighting and completion**, behind the same experimental
    HTML-injection setting as `~L`. Refs [#1257](https://github.com/intellij-elixir/intellij-elixir/issues/1257).

- [#3925](https://github.com/intellij-elixir/intellij-elixir/pull/3925) [@sh41](https://github.com/sh41)
  - **File and line are now linked inside an inspected stack trace**, where a frame's location sits in
    a keyword list - `[file: 'lib/gald/phase.ex', line: 75]`. Refs [#510](https://github.com/intellij-elixir/intellij-elixir/issues/510).
  - **Stack traces are now linked in the Terminal tool window too**, for `iex -S mix`, `mix test` and
    `mix phx.server`, and in every console the IDE builds for a project with an Elixir module or SDK.
  - **Erlang, HEEx and LEEx locations in a trace are now linked**, not just `.ex`, `.exs` and `.eex`.
  - **An Erlang SDK now exposes its own sources** from `lib/<app>-<version>/src`, so an OTP frame such
    as `gen_server.erl` is navigable. Existing SDKs pick the roots up on the next refresh.
  - **External Libraries now shows an SDK application's source beside its beams.**

- [#3923](https://github.com/intellij-elixir/intellij-elixir/pull/3923) [@sh41](https://github.com/sh41)
  - **SDK auto-detection now covers many more install layouts.** Newly found: `/usr/lib64` (Gentoo,
    and Erlang on Fedora); `/usr/share/elixir/<version>` (Fedora, RHEL), each version offered
    separately; Homebrew on Apple Silicon, on Linux and WSL, and installs since 2024-09-22 that nest
    the home in `lib/elixir`; version-pinned formulae such as `erlang@25` through `erlang@28`; and the
    `elixir-install` script on macOS. Every platform now scans the same sources. Refs
    [#312](https://github.com/intellij-elixir/intellij-elixir/issues/312),
    [#3670](https://github.com/intellij-elixir/intellij-elixir/issues/3670).
  - **Selecting an install prefix in the SDK chooser now finds the home inside it**, such as a Homebrew
    version directory, `/usr` or `/usr/local`.

- [#3914](https://github.com/intellij-elixir/intellij-elixir/pull/3914) [@sh41](https://github.com/sh41)
  - **Elixir 1.13 through 1.20 are now fully supported**, including syntax whose meaning changed
    between releases.
  - **`.beam` files compiled by OTP 24 through 29 decompile to valid Elixir.**

- [#3696](https://github.com/intellij-elixir/intellij-elixir/pull/3696) [@mwnciau](https://github.com/mwnciau), [@sh41](https://github.com/sh41)
  - **`.heex` files are now recognized as HEEx**, with dedicated syntax highlighting for `{@assigns}`/
    `{expressions}`, `<% %>`/`<%= %>` tags, and `<%!-- ... --%>` HEEx comments.
  - **`~H` sigils now use the same HEEx language as `.heex` files**, with separate HTML and Elixir
    PSI roots, instead of being treated as plain HTML text. Opt-in via Settings -> Languages &
    Frameworks -> Elixir -> Experimental -> "Enable ~H Sigil HEEx language injection".
  - **`<.component>` and `<Module.component>` tags now resolve** to their `def`/`defp` definition -
    local components, components brought in via an explicit `import`, and components brought in via
    `use MyAppWeb, :html`.
  - **Find Usages and Rename on a `def`/`defp` include its HEEx component tags**, in `.heex` files
    and inside `~H` sigils.
  - **Rename works with the caret on a component tag**, not only on the `def`/`defp`.
  - **Quick Docs and hover on a component tag show the function's documentation**, including inside
    a `~H` sigil.
  - **Find Usages finds a plain Elixir call embedded in a `~H` sigil**, e.g. `{some_function()}`.

### Bug Fixes

- [#4178](https://github.com/intellij-elixir/intellij-elixir/pull/4178) [@sh41](https://github.com/sh41)
  - **New Mix, IEx Mix, ExUnit, ESpec and Distillery run configurations start without the Build step, which fails because a Mix module has no output path, while Elixir and IEx configurations keep it.**
- [#4180](https://github.com/intellij-elixir/intellij-elixir/pull/4180) [@sh41](https://github.com/sh41)
  - **The Distillery run configuration's file choosers no longer accept folders, and its labels are in sentence case, with "sys.config File" renamed "Config file (sys.config)".**
- [#4182](https://github.com/intellij-elixir/intellij-elixir/pull/4182) [@sh41](https://github.com/sh41)
  - **Removing an SDK while the IDE watches SDK version files no longer reports an internal error or stops that watch.**
- [#4177](https://github.com/intellij-elixir/intellij-elixir/pull/4177) [@sh41](https://github.com/sh41)
  - **Decompiled OTP 29 modules show the built-in `record()` type and remote native record types instead of `missing_name()`.**
- [#4183](https://github.com/intellij-elixir/intellij-elixir/pull/4183) [@sh41](https://github.com/sh41)
  - **Removing a module, closing a project or changing SDKs while their versions are being read no longer reports an internal error.**
- [#4184](https://github.com/intellij-elixir/intellij-elixir/pull/4184) [@sh41](https://github.com/sh41)
  - **Dependency sync no longer deletes a project's `(consolidated)` library, or a dependency re-fetched as it syncs, and no longer rewrites the project when nothing changed; two Mix projects whose directories share a name each get their own `(consolidated)` library.**
  - **A Mix project that is not an umbrella now gets its consolidated protocols as a `(consolidated)` library; only umbrellas did before.**
  - **An SDK, working directory or path in a WSL distribution that is no longer installed no longer stalls the IDE each time it is read; running from one reports the missing distribution.**
- [#4185](https://github.com/intellij-elixir/intellij-elixir/pull/4185) [@sh41](https://github.com/sh41)
  - **An SDK with no home, or a relative one, is no longer read as though the IDE's own directory were its installation.**
  - **An Erlang SDK read while its installation was still writing `OTP_VERSION` is read again once the file is complete, instead of showing no version until the IDE restarts.**
  - **An SDK installed or upgraded while the IDE is open now shows its version, instead of none or a stale one until the IDE restarts.**
- [#4187](https://github.com/intellij-elixir/intellij-elixir/pull/4187) [@sh41](https://github.com/sh41)
  - **Closing a project or the IDE while `mix deps` checks its dependencies no longer reports an internal error, and a `mix deps` that never finishes no longer holds up later checks.**
  - **Stopping a run configuration on Windows no longer tries a `kill` that cannot signal it, and elsewhere signals the BEAM found when it is stopped, not when it started.**
- [#4167](https://github.com/intellij-elixir/intellij-elixir/pull/4167) [@sh41](https://github.com/sh41)
  - **Go to Declaration on a compiled function with more than one clause for the same arity now lands on the first clause, not the last.** Refs [#4164](https://github.com/intellij-elixir/intellij-elixir/issues/4164).
- [#4157](https://github.com/intellij-elixir/intellij-elixir/pull/4157) [@sh41](https://github.com/sh41)
  - **Go to Definition, completion, highlighting, Find Usages and Rename now agree on which calls define a function and what it can do.** Fixes [#4052](https://github.com/intellij-elixir/intellij-elixir/issues/4052).
  - **Guards have their own colour settings, Calls > Guard and Declarations > Guard, which fall back to the function colours.** Refs [#4052](https://github.com/intellij-elixir/intellij-elixir/issues/4052).
  - **Ctrl+Click on a `defdelegate`'s own name shows its usages, and quick documentation there shows its own `@doc`, as on a `def`.** Fixes [#4043](https://github.com/intellij-elixir/intellij-elixir/issues/4043).
  - **`import only:` and `except:` now apply to each arity a function declares.** Refs [#4052](https://github.com/intellij-elixir/intellij-elixir/issues/4052).
  - **Completing an EEx function inserts one placeholder per argument.** Refs [#4052](https://github.com/intellij-elixir/intellij-elixir/issues/4052).
  - **Renaming anything defined in a compiled `.beam` is refused with a message instead of failing with an exception.** Refs [#4052](https://github.com/intellij-elixir/intellij-elixir/issues/4052).
  - **An `import` inside an `if` in a module body, written after a call, or injected by a `use` after it, no longer reaches calls outside its block or before it.** Refs [#4052](https://github.com/intellij-elixir/intellij-elixir/issues/4052).
  - **Definitions, attributes and types in a module body now belong to their module when under `if`, `case`, `with` or Elixir's other block forms, piped, or in a function passed to any call.** Refs [#4052](https://github.com/intellij-elixir/intellij-elixir/issues/4052).
  - **A call at an arity nothing declares names the arities that exist, and a misspelled remote call suggests what the compiler would.** Refs [#4052](https://github.com/intellij-elixir/intellij-elixir/issues/4052).
- [#4146](https://github.com/intellij-elixir/intellij-elixir/pull/4146) [@sh41](https://github.com/sh41)
  - **Building a project no longer fails with `UnsupportedClassVersionError` from the plugin's JPS classes when the project SDK is older than the IDE's JDK.** Fixes [#3996](https://github.com/intellij-elixir/intellij-elixir/issues/3996).
- [#4160](https://github.com/intellij-elixir/intellij-elixir/pull/4160) [@sh41](https://github.com/sh41)
  - **Editing a module with a large `@doc` or `@moduledoc` block no longer freezes the IDE.** Fixes [#2813](https://github.com/intellij-elixir/intellij-elixir/issues/2813).
- [#4141](https://github.com/intellij-elixir/intellij-elixir/pull/4141) [@sh41](https://github.com/sh41)
  - **A `@doc` whose value is an expression starting with a string, such as `@doc "text" <> "more"`, no longer reports an error on every highlighting pass.** Fixes [#4131](https://github.com/intellij-elixir/intellij-elixir/issues/4131).
- [#4140](https://github.com/intellij-elixir/intellij-elixir/pull/4140) [@sh41](https://github.com/sh41)
  - **Errors follow the module's exact Elixir release, release candidates included, and the Erlang/OTP it runs on, so `maybe` is quoted from OTP 27.** Refs [#4080](https://github.com/intellij-elixir/intellij-elixir/issues/4080).
- [#4135](https://github.com/intellij-elixir/intellij-elixir/pull/4135) [@sh41](https://github.com/sh41)
  - **Quick Documentation for a `@macrocallback` now shows its `@doc`.** Fixes [#3997](https://github.com/intellij-elixir/intellij-elixir/issues/3997).
- [#4132](https://github.com/intellij-elixir/intellij-elixir/pull/4132) [@sh41](https://github.com/sh41)
  - **Opening the Elixir SDK or Credo settings no longer reports a slow operation on the EDT.** Fixes [#4130](https://github.com/intellij-elixir/intellij-elixir/issues/4130).
- [#4133](https://github.com/intellij-elixir/intellij-elixir/pull/4133) [@sh41](https://github.com/sh41)
  - **Selecting an SDK in the Elixir SDKs settings no longer reports a threading error.** Fixes [#4129](https://github.com/intellij-elixir/intellij-elixir/issues/4129).
- [#4124](https://github.com/intellij-elixir/intellij-elixir/pull/4124) [@sh41](https://github.com/sh41)
  - **Parameter hints and completion show the parameters of functions defined in a `.beam`.** Fixes [#4048](https://github.com/intellij-elixir/intellij-elixir/issues/4048).
- [#4121](https://github.com/intellij-elixir/intellij-elixir/pull/4121) [@sh41](https://github.com/sh41)
  - **Deeply nested code, complete or half-typed, no longer takes minutes to parse.** Fixes [#4114](https://github.com/intellij-elixir/intellij-elixir/issues/4114).

- [#4112](https://github.com/intellij-elixir/intellij-elixir/pull/4112) [@sh41](https://github.com/sh41)
  - **Switching a mise config between builds of one Elixir version for different OTP releases, such as
    `1.20.5-otp-27` to `1.20.5-otp-28`, now offers to configure the matching SDK.** Fixes
    [#4109](https://github.com/intellij-elixir/intellij-elixir/issues/4109).
  - An Elixir SDK with no Erlang SDK chosen for it is now paired with one built for the OTP release it was compiled
    against, rather than the first one registered, so a machine with several Erlang SDKs may pair differently than
    before. An Elixir build that records no OTP release still takes the first one in the same environment.
  - A mise config pinning a version that is not installed now reports it and names the `mise install` to run, and the
    SDK is offered as soon as that install finishes.

- [#4110](https://github.com/intellij-elixir/intellij-elixir/pull/4110) [@sh41](https://github.com/sh41)
  - **A `.beam` rebuilt on disk is now decompiled afresh instead of showing the code decompiled from its old contents.** Fixes [#4045](https://github.com/intellij-elixir/intellij-elixir/issues/4045).

- [#4107](https://github.com/intellij-elixir/intellij-elixir/pull/4107) [@sh41](https://github.com/sh41)
  - **A line that does not parse in a `do` block is now an error on that line instead of taking the rest of the module
    with it, and an interpolation missing its `}` is marked at its `#{`.** Refs [#4080](https://github.com/intellij-elixir/intellij-elixir/issues/4080).

- [#4102](https://github.com/intellij-elixir/intellij-elixir/pull/4102) [@sh41](https://github.com/sh41)
  - **An invalid escape or an unterminated heredoc no longer breaks the rest of the file: documentation keeps its
    highlighting, the module keeps its structure, and the error gives Elixir's message.** Refs [#4080](https://github.com/intellij-elixir/intellij-elixir/issues/4080).

- [#4104](https://github.com/intellij-elixir/intellij-elixir/pull/4104) [@sh41](https://github.com/sh41)
  - **Running the Dialyzer inspection on a project with `.class` files no longer raises an IDE internal error.** Fixes
    [#3077](https://github.com/intellij-elixir/intellij-elixir/issues/3077).

- [#4103](https://github.com/intellij-elixir/intellij-elixir/pull/4103) [@sh41](https://github.com/sh41)
  - **Rendered documentation now also shows `@doc`, `@moduledoc` and `@typedoc` nested inside expressions, such as an
    `if` or an `Enum.each` over a `fn` in a module body, a `quote` in a `__using__` macro, or a module defined inside a
    function.**
    Fixes [#4058](https://github.com/intellij-elixir/intellij-elixir/issues/4058),
    [#3135](https://github.com/intellij-elixir/intellij-elixir/issues/3135) and
    [#3675](https://github.com/intellij-elixir/intellij-elixir/issues/3675).

- [#4090](https://github.com/intellij-elixir/intellij-elixir/pull/4090) [@sh41](https://github.com/sh41)
  - **Line continuations next to an operator, and words directly after a number, now parse as Elixir reads them.**
    Refs [#4080](https://github.com/intellij-elixir/intellij-elixir/issues/4080).

- [#4084](https://github.com/intellij-elixir/intellij-elixir/pull/4084) [@sh41](https://github.com/sh41)
  - **Opening a file, or gathering Mix dependencies, with a heredoc whose closing `"""` follows its content on the
    same line no longer leaves a background thread looping forever.** Refs [#4080](https://github.com/intellij-elixir/intellij-elixir/issues/4080).

- [#4065](https://github.com/intellij-elixir/intellij-elixir/pull/4065) [@sh41](https://github.com/sh41)
  - **A module's Structure View tree and its caret sync now come from one list, so an ExUnit `test`
    written outside a `describe` gets its own node and an `EEx.function_from_*` node follows the
    caret.** Fixes [#4056](https://github.com/intellij-elixir/intellij-elixir/issues/4056).
  - **A struct or exception in the Structure View is named after its module instead of part of the
    file path.** Fixes [#4069](https://github.com/intellij-elixir/intellij-elixir/issues/4069).

- [#4063](https://github.com/intellij-elixir/intellij-elixir/pull/4063) [@sh41](https://github.com/sh41)
  - **An `alias` naming a module that does not exist no longer crashes the IDE with a
    `StackOverflowError`.** Fixes [#3978](https://github.com/intellij-elixir/intellij-elixir/issues/3978).

- [#4042](https://github.com/intellij-elixir/intellij-elixir/pull/4042) [@sh41](https://github.com/sh41)
  - **Completing after `Mod.` now offers the module's functions where another qualified call follows the
    caret, in a struct's field value, and for declarations written below a bare `Mod.` in the same module,
    and no longer offers them in the first argument of an `alias`, `import`, `require`, `use`,
    `defmodule`, `defimpl` or `defprotocol`.** Fixes
    [#4041](https://github.com/intellij-elixir/intellij-elixir/issues/4041).

- [#4062](https://github.com/intellij-elixir/intellij-elixir/pull/4062) [@sh41](https://github.com/sh41)
  - **A version manager's SDK is offered once instead of once per version alias.** mise publishes
    `26`, `26.2` and `26.2.5` alongside `26.2.5.21`, which listed the same install four times.
  - **A file that is not a BEAM no longer raises an IDE error report.** Any `*.beam` reaches the
    decompiler on its name alone, so a placeholder or a half-written file blamed the plugin.
  - **A corrupt BEAM file no longer crashes indexing.**

- [#4039](https://github.com/intellij-elixir/intellij-elixir/pull/4039) [@sh41](https://github.com/sh41)
  - **Go To Declaration, autocomplete and Quick Documentation now work for functions declared with
    `defdelegate`, including delegations into compiled modules such as `Map.values/1`.** Fixes
    [#1613](https://github.com/intellij-elixir/intellij-elixir/issues/1613).

- [#4031](https://github.com/intellij-elixir/intellij-elixir/pull/4031) [@sh41](https://github.com/sh41)
  - **A definition inside a quote bound by destructuring now resolves at the `unquote` or `use` that
    splices it.** Fixes [#4025](https://github.com/intellij-elixir/intellij-elixir/issues/4025).

- [#4020](https://github.com/intellij-elixir/intellij-elixir/pull/4020) [@sh41](https://github.com/sh41)
  - **Variable resolution, Find Usages and rename no longer report errors or miss bindings for code
    shapes they did not model.** Fixes [#4019](https://github.com/intellij-elixir/intellij-elixir/issues/4019).
  - **A variable read on the right of `=` resolves to its earlier binding instead of to itself.**
    Fixes [#354](https://github.com/intellij-elixir/intellij-elixir/issues/354).
  - **Rename and Find Usages of a rebound variable follow the chain the resolver sees: not into a
    preceding branch, and across top-level statements.**
  - **A read in a `when` guard, a pinned name in a pattern, and the subject of `case` or `if` are
    reads, so rename carries them with the variable they read instead of leaving them behind.**
  - **What `for` and `with` bind stays inside them, and a match inside a function's arguments,
    `f(x = 1)`, binds `x` after the call.**
  - **A variable bound inside string interpolation, such as `x` in `"#{x = 1}"`, can be found, renamed
    and navigated to from the code after the string.** Fixes [#4023](https://github.com/intellij-elixir/intellij-elixir/issues/4023).
  - **Find Usages and rename of a variable bound in an EEx tag reach its uses in the tags after it.**
    Fixes [#3970](https://github.com/intellij-elixir/intellij-elixir/issues/3970).

- [#4016](https://github.com/intellij-elixir/intellij-elixir/pull/4016) [@sh41](https://github.com/sh41)
  - **Go To Symbol now finds types defined in a decompiled `.beam` instead of crashing.** Fixes [#4000](https://github.com/intellij-elixir/intellij-elixir/issues/4000).

- [#4015](https://github.com/intellij-elixir/intellij-elixir/pull/4015) [@sh41](https://github.com/sh41)
  - **Resolving, highlighting, documentation and Go to Symbol no longer report an error for code
    shapes they do not model**, such as an unlisted `@doc` metadata key, a pinned Ecto query option,
    a `def` outside a module or a capture in a `@type`. Refs [#3985](https://github.com/intellij-elixir/intellij-elixir/issues/3985).
  - **A dep named by a quoted atom, `{:"my-dep", "~> 1.0"}`, is no longer dropped.**
  - **Renaming an operator now says it cannot be renamed** instead of silently doing nothing.

- [#4014](https://github.com/intellij-elixir/intellij-elixir/pull/4014) [@sh41](https://github.com/sh41)
  - **Resolving a variable or type no longer reports an error for code shapes the resolver does not
    declare from**, such as a `do` block met on the walk or a `@type` head with a literal argument.
    Refs [#3985](https://github.com/intellij-elixir/intellij-elixir/issues/3985).

- [#4007](https://github.com/intellij-elixir/intellij-elixir/pull/4007) [@sh41](https://github.com/sh41)
  - **Typing a heredoc's opening `"""` now closes it at the same indentation.** Fixes [#806](https://github.com/intellij-elixir/intellij-elixir/issues/806).

- [#4005](https://github.com/intellij-elixir/intellij-elixir/pull/4005) [@sh41](https://github.com/sh41)
  - **The IDE no longer freezes while a terminal produces output.** Fixes [#4004](https://github.com/intellij-elixir/intellij-elixir/issues/4004).
  - **A Windows path printed with its drive letter now links from the drive letter.**

- [#4001](https://github.com/intellij-elixir/intellij-elixir/pull/4001) [@sh41](https://github.com/sh41)
  - **A Mix project opened in a small IDE is configured as an Elixir project again.** Fixes [#3999](https://github.com/intellij-elixir/intellij-elixir/issues/3999).
  - **A Mix app below the opened directory is attached to the project again**, in RubyMine, GoLand and other small IDEs that support attaching.

- [#3998](https://github.com/intellij-elixir/intellij-elixir/pull/3998) [@sh41](https://github.com/sh41)
  - **Completion offers the Elixir and Erlang standard libraries again in WebStorm, RubyMine,
    PyCharm, GoLand and CLion.** `String`, `Application`, `:math` and everything else that ships
    with the SDK were missing from the popup even when the SDK was set up correctly. Existing
    projects are repaired when they next open.
    Fixes [#3486](https://github.com/intellij-elixir/intellij-elixir/issues/3486).

- [#3993](https://github.com/intellij-elixir/intellij-elixir/pull/3993) [@sh41](https://github.com/sh41)
  - **Function names are offered after a `.` even when another statement follows it.** Fixes [#3219](https://github.com/intellij-elixir/intellij-elixir/issues/3219).

- [#3991](https://github.com/intellij-elixir/intellij-elixir/pull/3991) [@sh41](https://github.com/sh41)
  - **The `+++` and `---` operators are now parsed.** Fixes [#2755](https://github.com/intellij-elixir/intellij-elixir/issues/2755).

- [#3983](https://github.com/intellij-elixir/intellij-elixir/pull/3983) [@sh41](https://github.com/sh41)
  - **Dependency libraries are scoped relative to the project**, so `.idea/libraries` and `.iml` can
    be shared again. Refs [#3926](https://github.com/intellij-elixir/intellij-elixir/issues/3926).
  - **Dependency scanning waits for the IDE's Project Model to be fully loaded to avoid lost
    writes**, however it was triggered.
  - **External Libraries shows a dependency's name and location again.**
  - **A dependency's optional and non-`:prod` dependencies no longer become empty libraries.**
  - **A `sparse:` and/or `subdir:` git dependency is now located correctly.** Refs [#3928](https://github.com/intellij-elixir/intellij-elixir/issues/3928).
  - **A dependency's own dependencies are located correctly.** Refs [#3928](https://github.com/intellij-elixir/intellij-elixir/issues/3928).
  - **Bracketed dependency options such as `[path: "../dep"]` are read.**
  - **An umbrella app's dependencies resolve when each app is its own module.** Refs
    [#3986](https://github.com/intellij-elixir/intellij-elixir/issues/3986).
  - **A dependency an umbrella app reaches through an `in_umbrella:` sibling now resolves.** Refs
    [#3990](https://github.com/intellij-elixir/intellij-elixir/issues/3990).

- [#3982](https://github.com/intellij-elixir/intellij-elixir/pull/3982) [@sh41](https://github.com/sh41)
  - **A parameter of an `fn` that has no `->` yet no longer reports "Use scope for parameter not found
    before reaching file scope".** Fixes [#2491](https://github.com/intellij-elixir/intellij-elixir/issues/2491).

- [#3979](https://github.com/intellij-elixir/intellij-elixir/pull/3979) [@sh41](https://github.com/sh41)
  - **A variable whose scope search reaches the top of the file no longer reports "Don't know how to
    find variable use scope".** Fixes [#3358](https://github.com/intellij-elixir/intellij-elixir/issues/3358).

- [#3977](https://github.com/intellij-elixir/intellij-elixir/pull/3977) [@sh41](https://github.com/sh41)
  - **Resolving `__MODULE__` inside a module that `use`s another no longer reports a non-idempotent
    computation.** Completion and Go to Declaration were the usual triggers. Fixes
    [#2205](https://github.com/intellij-elixir/intellij-elixir/issues/2205), [#3320](https://github.com/intellij-elixir/intellij-elixir/issues/3320), [#3467](https://github.com/intellij-elixir/intellij-elixir/issues/3467),
    [#3477](https://github.com/intellij-elixir/intellij-elixir/issues/3477), [#3495](https://github.com/intellij-elixir/intellij-elixir/issues/3495), [#3517](https://github.com/intellij-elixir/intellij-elixir/issues/3517),
    [#3592](https://github.com/intellij-elixir/intellij-elixir/issues/3592) and [#3593](https://github.com/intellij-elixir/intellij-elixir/issues/3593).

- [#3976](https://github.com/intellij-elixir/intellij-elixir/pull/3976) [@sh41](https://github.com/sh41)
  - **An `fn` whose body has no `->` no longer reports "Don't know how to check if variable".** Fixes
    [#2376](https://github.com/intellij-elixir/intellij-elixir/issues/2376).

- [#3975](https://github.com/intellij-elixir/intellij-elixir/pull/3975) [@sh41](https://github.com/sh41)
  - **A hexadecimal or octal number in a type no longer reports "Cannot highlight types".** Fixes
    [#1796](https://github.com/intellij-elixir/intellij-elixir/issues/1796) and [#3142](https://github.com/intellij-elixir/intellij-elixir/issues/3142).

- [#3974](https://github.com/intellij-elixir/intellij-elixir/pull/3974) [@sh41](https://github.com/sh41)
  - **A qualified call in a `@type` parameter list no longer raises an error report**, such as
    `@type date(spec.date_type)`. Fixes [#1835](https://github.com/intellij-elixir/intellij-elixir/issues/1835).

- [#3973](https://github.com/intellij-elixir/intellij-elixir/pull/3973) [@sh41](https://github.com/sh41)
  - **HTML inside a `~L` or `~E` sigil is now highlighted.** Still behind the experimental
    sigil-injection setting. Fixes [#1827](https://github.com/intellij-elixir/intellij-elixir/issues/1827).

- [#3972](https://github.com/intellij-elixir/intellij-elixir/pull/3972) [@sh41](https://github.com/sh41)
  - **Quick Documentation now works on a captured function**, such as `&Callee.multiply/2`.
  - **Go To Definition on a capture of a decompiled function now navigates**, such as `&:queue.new/0`.
    Refs [#1810](https://github.com/intellij-elixir/intellij-elixir/issues/1810).

- [#3971](https://github.com/intellij-elixir/intellij-elixir/pull/3971) [@sh41](https://github.com/sh41)
  - **A module attribute declared in a `__using__` macro's `quote` block resolves again.** Broken since
    24.0.0 for Go To Declaration, Find Usages and completion. Refs [#1783](https://github.com/intellij-elixir/intellij-elixir/issues/1783),
    [#1292](https://github.com/intellij-elixir/intellij-elixir/issues/1292).

- [#3968](https://github.com/intellij-elixir/intellij-elixir/pull/3968) [@sh41](https://github.com/sh41)
  - **"Align operands of pipe operator (|)" no longer staircases a multi-operand union.** `a | b | c`
    now aligns the way `mix format` produces. Fixes [#1787](https://github.com/intellij-elixir/intellij-elixir/issues/1787).

- [#3965](https://github.com/intellij-elixir/intellij-elixir/pull/3965) [@sh41](https://github.com/sh41)
  - **Renaming a variable no longer rewrites an inner binding that shadows it** - an `fn` parameter,
    `case` clause pattern or `for` generator reusing an outer name. Fixes [#1479](https://github.com/intellij-elixir/intellij-elixir/issues/1479).
  - **Renaming from a rebinding inside an `fn` or `case` body now covers the whole variable.**

- [#3964](https://github.com/intellij-elixir/intellij-elixir/pull/3964) [@sh41](https://github.com/sh41)
  - **"Remove space between function name and parentheses" now removes the space instead of
    throwing.** Fixes [#3107](https://github.com/intellij-elixir/intellij-elixir/issues/3107).

- [#3963](https://github.com/intellij-elixir/intellij-elixir/pull/3963) [@sh41](https://github.com/sh41)
  - **A `@spec` whose head has no function name no longer crashes the structure view**, such as
    `@spec foo.() :: term` and `@spec bar not in baz :: term`. Fixes [#1564](https://github.com/intellij-elixir/intellij-elixir/issues/1564).

- [#3962](https://github.com/intellij-elixir/intellij-elixir/pull/3962) [@sh41](https://github.com/sh41)
  - **Ctrl+Click and highlighting no longer throw on a function the decompiler could not recreate.**
    Refs [#3309](https://github.com/intellij-elixir/intellij-elixir/issues/3309).

- [#3957](https://github.com/intellij-elixir/intellij-elixir/pull/3957) [@sh41](https://github.com/sh41)
  - **A dep with `allow_pre:` no longer reports an unknown Mix dep option.** Fixes [#2487](https://github.com/intellij-elixir/intellij-elixir/issues/2487).

- [#3953](https://github.com/intellij-elixir/intellij-elixir/pull/3953) [@sh41](https://github.com/sh41)
  - **"Cannot find enclosing Modular" no longer fires for a `def` inside a map-held `quote`, or for a
    `@callback` behind an infix operator.** Refs [#1695](https://github.com/intellij-elixir/intellij-elixir/issues/1695) and [#1438](https://github.com/intellij-elixir/intellij-elixir/issues/1438).

- [#3951](https://github.com/intellij-elixir/intellij-elixir/pull/3951) [@sh41](https://github.com/sh41)
  - **`defstruct do ... end` and `defexception do ... end` no longer crash the structure view.** Fixes
    [#2107](https://github.com/intellij-elixir/intellij-elixir/issues/2107) and [#1095](https://github.com/intellij-elixir/intellij-elixir/issues/1095).
  - **A piped `defdelegate` with a `do` block no longer throws while resolving variables.**

- [#3948](https://github.com/intellij-elixir/intellij-elixir/pull/3948) [@sh41](https://github.com/sh41)
  - **Auto-Indent Lines and paste no longer throw on a map update with a literal on the left**, such
    as `%{%{a} | k: 1}`.

- [#3947](https://github.com/intellij-elixir/intellij-elixir/pull/3947) [@sh41](https://github.com/sh41)
  - **Large integer literals in a decompiled BEAM now show their real value**, and the Code tab no
    longer throws on some files.

- [#3946](https://github.com/intellij-elixir/intellij-elixir/pull/3946) [@sh41](https://github.com/sh41)
  - **Configuring an Elixir SDK no longer crashes PhpStorm, WebStorm and the other small IDEs** when
    another plugin registers a root type of its own.

- [#3934](https://github.com/intellij-elixir/intellij-elixir/pull/3934) [@sh41](https://github.com/sh41)
  - **A trailing comma in a call's argument list no longer breaks the rest of the file.** Applies to
    `foo(a,)`, `Mod.fun(a,)` and `fun.(a,)`.
  - **Parameter hints no longer describe functions you are not calling.** Each arity of the called
    function still gets its own hint.
  - **Parameter hints now appear when a completion is accepted.** Accepting a name from the completion
    popup left the caret between the inserted parentheses with no hint there.
  - **Pressing Enter after a delimiter now indents the caret where an element goes** rather than back
    to the enclosing statement: after `[`, `{`, `%{`, `%S{`, `<<` and `Foo.{`, after a comma, after
    `->` in a clause, after the `rescue`, `after`, `else` and `catch` keywords, and inside empty
    parentheses. Every column is the one `mix format` produces. Refs
    [#799](https://github.com/intellij-elixir/intellij-elixir/issues/799).

- [#3925](https://github.com/intellij-elixir/intellij-elixir/pull/3925) [@sh41](https://github.com/sh41)
  - **A console path written with backslashes now links on Windows.**

- [#3924](https://github.com/intellij-elixir/intellij-elixir/pull/3924) [@sh41](https://github.com/sh41)
  - **Variable completion no longer shows a parameter's name twice.** Variables bound by a match still
    show that match. Fixes [#496](https://github.com/intellij-elixir/intellij-elixir/issues/496).

- [#3923](https://github.com/intellij-elixir/intellij-elixir/pull/3923) [@sh41](https://github.com/sh41)
  - **Homebrew Erlang SDKs no longer report an unknown version**, and sort and label correctly in the
    SDK list.

- [#3696](https://github.com/intellij-elixir/intellij-elixir/pull/3696) [@mwnciau](https://github.com/mwnciau), [@sh41](https://github.com/sh41)
  - **`\{` and `\}` in HEEx are now literal braces** instead of affecting `{...}` expression nesting.
  - **A component tag's attribute after a `{...}`-valued one no longer gets swallowed into that
    value**, e.g. `<.tag one={""} class=""/>` now parses `class` as its own attribute.
  - **`{` inside `<script>`/`<style>` stays literal after an embedded `<% %>` tag**, and
    `</script>`/`</style>` still close the tag.
  - **A self-closing `<script .../>` or `<style .../>` no longer swallows the rest of the file** as
    script or style content.
  - **`<% %>` tags that produce no output (comments, `<% if %>`) no longer inject placeholder text**
    into the HTML tree.
  - **HEEx's special attributes and `phx-` bindings are no longer reported as "not allowed here"
    on HTML tags**: `:let`, `:if`, `:for`, `:key`, `:type` and every `phx-*` binding.
  - **`<:slot>` tags are no longer validated as HTML elements.** `<:col>` was checked against HTML's
    `<col>` and `<:action>` reported as an unknown tag.
  - **A component tag that doesn't resolve no longer shows "Cannot resolve symbol".**
  - **Components brought into scope by a `use` nested inside `use MyAppWeb, :html` now resolve**, such
    as `Phoenix.Component`'s `<.link>` and `<.live_title>`.
  - **Rename and Find Usages on a component tag no longer miss the tag**, which varied between IDE
    sessions.
  - **An unqualified call inside a `~H` sigil now resolves to a `def` in the surrounding module.**

- [#3914](https://github.com/intellij-elixir/intellij-elixir/pull/3914) [@sh41](https://github.com/sh41)
  - **Reformat Code no longer changes what a capture expression means.** Since Elixir 1.15,
    `&1` and `& 1` are different expressions; the formatter was inserting that space, silently
    turning `&(&1 + &2)` into code meaning `&(&(1 + &2))`.
  - **`& 1` now parses the way the containing module/project's configured Elixir SDK parses it**. The
    plugin previously always used the pre-1.15 meaning.
    - On Elixir 1.15 and higher: `&` applied to the whole following expression.
    - On Elixir 1.14 and lower: the single capture argument `&1`.
  - **The stepped-range atom `:..//` no longer shows a parse error.** It was previously read as
    `:..` followed by a stray `//`.
  - **Decompiled `.beam` files no longer show a parse error when a `cond` or `case` clause condition
    ends in `end`**. This fixes decompilation of `Mix.Project`, `Mix.Release`, and `Mix.Task` on
    Elixir 1.20+.

- [#3919](https://github.com/intellij-elixir/intellij-elixir/pull/3919) [@sh41](https://github.com/sh41)
  - **New Elixir projects get the right compiler output directory** when `--app` is left blank in the
    New Project wizard; it now falls back to the project name, as `mix new` does.
  - **`--sup` is no longer offered for umbrella projects**, where `mix new` silently ignores it.
  - **New umbrella projects no longer get a stray empty `lib/` or an output directory that never
    exists.**
  - **New Elixir projects and modules are no longer reported as having "an outdated format".**
  - **Umbrella sub-apps now get their `lib/` and `test/` marked.** The wizard, *Reconfigure Elixir
    Module Setup* and the module-setup check now refresh the IDE's view of the app first.

- [#3921](https://github.com/intellij-elixir/intellij-elixir/pull/3921) [@sh41](https://github.com/sh41)
  - **Variables bound by a macro call in a match now resolve**, such as `session(id, user) = raw`.
    Calls that resolve to a function are unaffected.

- [#3918](https://github.com/intellij-elixir/intellij-elixir/pull/3918) [@sh41](https://github.com/sh41)
  - **Saved run configurations no longer record the module twice**, which the platform logged as
    "Module serialized more than one time".
  - **"Include system environment variables" is now remembered.** The setting was accepted in the
    run configuration editor but silently discarded when the configuration was saved.
  - **A run configuration whose module is not loaded yet keeps it.**

- [#3915](https://github.com/intellij-elixir/intellij-elixir/pull/3915) [@sh41](https://github.com/sh41)
  - **The IDE no longer starts every WSL distro that hosts a registered Erlang or Elixir SDK, and will
    no longer freeze if WSL is slow or hangs while starting.**

### Threading / Platform Hygiene

- [#4180](https://github.com/intellij-elixir/intellij-elixir/pull/4180) [@sh41](https://github.com/sh41)
  - **The plugin no longer uses the deprecated platform APIs javac reports, apart from `CommonProgramParametersPanel()`, which waits for 2026.1.1.**
  - **The plugin compares the Elixir module type by one ID instead of naming its class.**
- [#4179](https://github.com/intellij-elixir/intellij-elixir/pull/4179) [@sh41](https://github.com/sh41)
  - **Building a file's stubs no longer reaches `CachedValuesManager` for the language level or for quoting, which the platform flags inside a stub build.**
- [#4062](https://github.com/intellij-elixir/intellij-elixir/pull/4062) [@sh41](https://github.com/sh41)
  - **The Erlang SDK's OTP-mismatch warning is detected without holding the settings dialog's read
    lock.** Refs [#3955](https://github.com/intellij-elixir/intellij-elixir/issues/3955).
  - **The Elixir SDK's compiled-against OTP release is read once per install**, rather than on every
    Erlang SDK selection in the settings dialog.

- [#4002](https://github.com/intellij-elixir/intellij-elixir/pull/4002) [@sh41](https://github.com/sh41)
  - **Quick Docs on a HEEx component tag no longer uses an internal platform API.** A `def`/`defp`
    clause's own name identifier now resolves to that clause's documentation, so the tag's resolved
    target renders through the platform's default documentation target instead of one built with
    `com.intellij.lang.documentation.psi.UtilKt.createPsiDocumentationTarget`.

- [#3950](https://github.com/intellij-elixir/intellij-elixir/pull/3950) [@sh41](https://github.com/sh41)
  - **The formatter's `Block` is clean under IntelliJ's inspections** - 18 warnings down to 3. Four
    nullable-node dereferences now state the `AbstractBlock` non-null invariant,
    `ContainerBlockListReducer` stops declaring an `Indent` non-null that callers pass as null, and the
    interpolation overload no longer takes two parameters that were always the same constants.

### Build / CI

- [#4183](https://github.com/intellij-elixir/intellij-elixir/pull/4183) [@sh41](https://github.com/sh41)
  - **A test that logs an error or a plugin warning, or leaves an exception uncaught, that it does not expect now fails.**
- [#4186](https://github.com/intellij-elixir/intellij-elixir/pull/4186) [@sh41](https://github.com/sh41)
  - **A light test's changes to the shared test module and project are undone after it, and a test that leaves anything else changed there fails.**
- [#4188](https://github.com/intellij-elixir/intellij-elixir/pull/4188) [@sh41](https://github.com/sh41)
  - **The SDK stdlib sweep fails for each SDK whose beams make the decompiler log a warning.**
- [#4171](https://github.com/intellij-elixir/intellij-elixir/pull/4171) [@sh41](https://github.com/sh41)
  - **The test suite runs on the JUnit Platform in parallel test JVMs sized to the machine's spare cores and memory, and no longer starts shells in real WSL distributions.**
- [#4181](https://github.com/intellij-elixir/intellij-elixir/pull/4181) [@sh41](https://github.com/sh41)
  - **Each CI leg restores only its own IDEA version's Gradle caches, instead of another's that it then rebuilt, and every job reports its runner's hardware.**
- [#4154](https://github.com/intellij-elixir/intellij-elixir/pull/4154) [@sh41](https://github.com/sh41)
  - **Changelog entries merge without conflicting, and change notes are truncated at the descriptor's character cap instead of failing the build.**
- [#4148](https://github.com/intellij-elixir/intellij-elixir/pull/4148) [@sh41](https://github.com/sh41)
  - **A pull request whose code is unchanged since its last passing run no longer re-runs the test matrix or plugin verification.**
- [#4146](https://github.com/intellij-elixir/intellij-elixir/pull/4146) [@sh41](https://github.com/sh41)
  - **The parsing tests no longer fail when the reference quoter is merely slow to answer.**
- [#4155](https://github.com/intellij-elixir/intellij-elixir/pull/4155) [@sh41](https://github.com/sh41)
  - **The SDK stdlib test suite decompiles each resolved Elixir/Erlang SDK once per CI leg instead of three times.**
- [#4163](https://github.com/intellij-elixir/intellij-elixir/pull/4163) [@sh41](https://github.com/sh41)
  - **The SDK stub-signature sweep no longer rescans every sibling definition per compared function.** Refs [#4162](https://github.com/intellij-elixir/intellij-elixir/issues/4162).
- [#4120](https://github.com/intellij-elixir/intellij-elixir/pull/4120) [@sh41](https://github.com/sh41)
  - **CI caches now skip the work they exist to skip, and the Windows test leg no longer times out saving them.**
- [#4116](https://github.com/intellij-elixir/intellij-elixir/pull/4116) [@sh41](https://github.com/sh41)
  - **Repository links in the docs and the plugin's vendor URL point at `intellij-elixir/intellij-elixir`.**
- [#4083](https://github.com/intellij-elixir/intellij-elixir/pull/4083) [@sh41](https://github.com/sh41)
  - **The tests quote with quoter
    [v3.0.0](https://github.com/intellij-elixir/intellij-elixir-quoter/tree/v3.0.0) from
    [intellij-elixir/intellij-elixir-quoter](https://github.com/intellij-elixir/intellij-elixir-quoter).**

- [#4006](https://github.com/intellij-elixir/intellij-elixir/pull/4006) [@sh41](https://github.com/sh41)
  - **CI is faster: Gradle's build and configuration caches are kept, Elixir is no longer rebuilt from
    source on the Windows leg, and the runners no longer reclaim disk space they were not short of.**
    The dependency cache stays off deliberately - it is gigabytes to save seconds.

- [#4003](https://github.com/intellij-elixir/intellij-elixir/pull/4003) [@sh41](https://github.com/sh41)
  - **Plugin verification now fails on an internal platform API usage that is not on the approved list.**

- [#3994](https://github.com/intellij-elixir/intellij-elixir/pull/3994) [@sh41](https://github.com/sh41)
  - **A pull request build's plugin artifact is now stamped with a commit that exists.**

- [#3969](https://github.com/intellij-elixir/intellij-elixir/pull/3969) [@sh41](https://github.com/sh41)
  - **The quoter daemon is now started on every test run, not just the first in a checkout.** Its files
    made `startQuoter` look up to date, but the daemon it starts is stopped again when the build ends,
    so from the second run onwards the task was skipped while its marker still reported
    `quoter.available=true` and every test that quotes failed on a PID timeout. The task is now always
    out of date and never cached.
  - **The quoter's Erlang distribution and `epmd` now bind loopback rather than every interface.** Both
    listeners bound the wildcard address despite the nodes being local, which is what made Windows
    Firewall prompt for the release's `erl.exe` and the IDE's `java.exe`, once per worktree. An `epmd`
    that is already running is used as it is; only a fresh start asks for loopback.

- [#3960](https://github.com/intellij-elixir/intellij-elixir/pull/3960) [@sh41](https://github.com/sh41)
  - **`epmd` is now started from the Erlang SDK rather than the quoter's own bundled ERTS.** A
    distributed node starts `epmd` from the ERTS of whatever release is starting, detached, so the one
    the quoter left behind ran from under `cache/` inside the checkout. On Windows a running executable
    pins its own directory, which made deleting a checkout or a git worktree fail with "Device or
    resource busy" long after the build finished. The build now starts `epmd` from the SDK first and
    passes `-start_epmd false`, falling back to the previous behaviour when no SDK `epmd` is found.

- [#3913](https://github.com/intellij-elixir/intellij-elixir/pull/3913) [@sh41](https://github.com/sh41)
  - **Quoter tests now respect the quoting rules for the Elixir version under test.** Six quoted-form
    divergences are gated on the release that introduced each, so the same fixtures pass on every
    Elixir/OTP pair CI tests.
  - **A quoter that fails to build no longer blocks the whole test suite.** Tests that require the
    quoter fail immediately with the recorded reason; all other tests run as normal.
    `-PquoterRequired=true` restores the hard stop for anyone debugging the quoter itself.
  - **CI gets the quoter cache paths from the build** (a `quoterCachePaths` task) instead of
    re-deriving them in bash, so the workflow can no longer disagree with Gradle about where the
    cache lives.
  - **The quoter builds with an explicit `MIX_ENV=prod`**, and only prod dependencies are fetched. An exported `MIX_ENV` still overrides it.

- [#3914](https://github.com/intellij-elixir/intellij-elixir/pull/3914) [@sh41](https://github.com/sh41)
  - **All Elixir/OTP test legs now pass and are required for merge.** Getting there also gated two
    more quoted-form divergences by version (1.20's `__block__` line metadata, pre-1.17 parens
    metadata) and froze copies of stdlib files that newer Elixirs deleted, so the parsing corpus no
    longer shifts underneath the tests.

- [#3916](https://github.com/intellij-elixir/intellij-elixir/pull/3916) [@sh41](https://github.com/sh41)
  - **`testUI` builds and runs again.** `kodein-di-jvm` and `kotlinx-coroutines-core-jvm` were
    silently dropped from its dependencies by an earlier commit; both are genuinely required.
  - **The released plugin now compiles against 2025.3, its declared minimum supported version**,
    instead of a newer platform - closing a gap where only the post-release Plugin Verifier check
    stood between an incompatibility and a real user's install.
  - **The shipped plugin no longer accidentally bundles its own copy of `kotlin-stdlib`.**
  - Bumped `gradle-wrapper` to 9.7.0 (with its distribution checksum now verified),
    `org.jetbrains.changelog` to 2.5.0, and migrated `com.github.ben-manes.versions` to the
    maintained `io.github.ben-manes.versions` id at 0.61.0. Dropped the unused `qodana` version
    catalog entry (`qodana.yml`'s linter tag is the only declaration now, bumped to 2026.2).

- [#3887](https://github.com/intellij-elixir/intellij-elixir/pull/3887), [#3917](https://github.com/intellij-elixir/intellij-elixir/pull/3917) [@dependabot](https://github.com/dependabot)
  - Bumped `kodein-di-jvm` to 7.33.0 and `gradle-wrapper` to 9.7.1.

## [24.0.1] - 2026-08-09

### Enhancements

- [@sh41](https://github.com/sh41)
  - **The plugin's "What's New" now shows the most recent releases instead of the whole history.**
    Each version is listed with its release date, so upgrading after skipping a few releases shows
    what you missed rather than one undifferentiated list.

### Bug Fixes

- [@sh41](https://github.com/sh41)
  - **Adding an Elixir SDK registers it again.** [#3888](https://github.com/intellij-elixir/intellij-elixir/issues/3888) -
    the SDK list stayed empty and "Elixir Facet SDK is not defined" persisted, because setting up the
    SDK's paths read the SDK table without a read lock and the resulting error aborted registration
    before the SDK reached the list.
  - **A newly added Elixir SDK is paired with the Erlang SDK you picked,** rather than whichever one
    happened to be registered first. The chosen SDK is not committed until the dialog is applied, and
    the pairing was resolved without consulting the dialog's uncommitted SDKs.
  - **The "Configure from mise" action no longer goes missing from the SDK banner.** The startup scan
    request could be dropped before its collector was listening, leaving the project with no tool
    manager scan at all.
- [#3901](https://github.com/intellij-elixir/intellij-elixir/pull/3901) - [@sh41](https://github.com/sh41)
  - **Semantic syntax highlighting now appears in decompiled `.beam` files.** Function names, types and
    the rest were being coloured by the annotators and then discarded before they reached the editor, so
    decompiled code showed only the plain lexer colours. v24.0.0 announced this as working; it was not.
  - **Opening a decompiled `.beam` file no longer reports "PsiFile's context does not match the context
    of the editor",** which reached users as an IDE error report.

### Build / CI

- [@sh41](https://github.com/sh41)
  - `CHANGELOG.md` is now in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format and is the
    single source of the plugin change notes, rendered by the Gradle Changelog Plugin.
    `resources/META-INF/changelog.html`, which held a separately hand-written copy, is removed - it had
    gone stale, so 24.0.0 shipped with v23.9.0 as its newest section.
  - Every pull request now needs a `CHANGELOG.md` entry, checked by `changelog.yml`: it must land under
    `## [Unreleased]`, be a list item, and use a known group. Apply the `no-changelog` label
    when there is genuinely nothing to record.
  - The Tag Release workflow validates the tag shape against the `prerelease` input, that a release is
    cut from `main`, that the tag is new and increases, and that it matches `pluginVersion`.
  - Plugin verifier reports are now uploaded whenever a verification leg fails, including on pull
    requests. Previously the upload was gated on an input that is always empty for `pull_request`, so
    the reports were unobtainable on exactly the runs that needed them and the evidence had to be dug
    out of a raw job log. Green runs still upload nothing unless a caller asks.
  - Everything scoped to an Elixir/OTP pair is now keyed on the pair: `MIX_HOME` joins `MIX_ARCHIVES`,
    and the quoter's `_build`/`deps` join both. Two OTPs for one Elixir previously shared a build tree
    and overwrote each other, and switching versions re-downloaded hex and rebar. The first build per
    pair after this change reinstalls and rebuilds once.
  - CI now covers OTP 25, and runs a decompiler sweep against OTP 28 for the first time. Two
    `beam.additional` pairs were added, `1.13.4 / 25.3.2.21` and `1.18.4 / 28.4`, chosen to cover OTP
    majors rather than Elixir minors: most of the decompiled surface is Erlang and the BEAM chunk formats
    track OTP. **OTP 25 is now a supported pair** - measured locally at 6 560 tests, 0 failures, so its
    leg is required and any later change that breaks it fails the pipeline. OTP 28 stays informational:
    its 291 failures are all pre-existing Elixir-keyed quoting cases, with no OTP-28 decompiler
    failures.
  - Switching Elixir/OTP versions now re-runs the tests. The versions reach the test JVM as environment
    variables, which Gradle could not see, so `mise use erlang@X elixir@Y` followed by `check` reported
    the *previous* pair's results - the task was up to date, and the build cache would even restore
    those results after a `cleanTest`. Both test tasks now declare the versions as inputs.
  - A transient 5xx from JetBrains' artifact CDN no longer loses a whole test leg. Gradle already treats
    repository server errors as retryable, but its default budget is three attempts over about three
    seconds; `gradle.properties` now allows eight from a 3-second doubling backoff, roughly six minutes,
    for every build - CI and local alike.
  - CI restores the Elixir quoter cache again - it never had. `actions/cache` identifies an entry by key
    *and* by a version hashed from the literal `path:` lines, and the restore and save steps listed
    different paths, so they addressed different entries under one key. Every leg missed, rebuilt its
    quoter dependencies from Hex, then failed to save against the entry an earlier run had left there -
    reported as `Unable to reserve cache`, which reads as a harmless race between legs. Both steps now
    take the path list from one place.
  - The test results comment now leads with failures on the **required** legs, the only figure that
    decides whether a pull request can merge, and reports `tests per leg` in place of a cross-leg
    union that read 12,913 for a run in which no leg ran more than 6,659.

## [24.0.0] - 2026-08-04

### Enhancements

- [#3866](https://github.com/intellij-elixir/intellij-elixir/pull/3866) - [@sh41](https://github.com/sh41)
  - **Rename (Shift+F6) now works for functions, `name:arity` pairs, modules, module attributes, types, `@callback`s, protocol functions, and atoms**, propagating across every usage site -- including keyword-key usages like `import ... only:`, `@compile inline:`, `@dialyzer`, and `defoverridable`.
  - **New navigation:** `name: arity` function references support Go-to-Declaration/Find Usages/rename; `@callback`/`@macrocallback` definitions navigate to and from their implementations; protocol functions support Find Usages to call sites; Ctrl-click on a `GenServer.call/cast` or `Process.send`/`send_after` message now jumps to the matching `handle_call`/`handle_cast`/`handle_info` clause.
  - **Type variables** (e.g. `a` in `@type box(a) :: {:box, a}` or `@spec ... when a: term()`) now support Go-to-Declaration, Find Usages, and rename, scoped to the enclosing type/spec.
  - **Decompiled `.beam` files now navigate like source.** Ctrl-click and Find Usages work from and into decompiled definitions and their call/spec sites, and semantic syntax highlighting now runs over decompiled text.
  - **Completion improvements:** function-capture names (`&map_it/0`) filtered by arity; function names inside MFA-style atom references (`{Module, :function, args}`); module-attribute references; rebound/shadowed variables and implicitly-imported `Kernel` names no longer offered twice.
  - **Two new inspections:** "Type variable used once" flags a type/spec variable referenced only once; "Unresolvable type" flags type names in specs that don't resolve to any type definition.
- [#3868](https://github.com/intellij-elixir/intellij-elixir/pull/3868) - [@sh41](https://github.com/sh41)
  - **Decompiled BEAM files now show real `@type`/`@typep`/`@opaque` definitions** instead of collapsing them all to `term()`. For example, Erlang's `:queue` now decompiles its opaque type with its real parameters and body.
  - **Map comprehension generators (`m_generate`, OTP 26+) in decompiled code now render as a proper `{key, value} <- map` pattern** instead of an unparseable expression.
- [#3873](https://github.com/intellij-elixir/intellij-elixir/pull/3873) - [@sh41](https://github.com/sh41)
  - **Small IDE (RubyMine, PyCharm, etc.) users can now configure Elixir SDKs from mise.** Tool manager support -- including the Settings -> Elixir -> Tool Managers opt-in page -- is now available in small IDEs, not just IntelliJ IDEA. When mise has an Elixir installed for a module, a **"Configure from mise"** button appears on the "SDK is not defined" editor banner and next to that module's SDK selector in Settings -> Elixir. One click creates the Elixir and Erlang SDKs and selects them for that module.
  - **Settings -> Elixir now guides SDK setup.** The page explains that SDKs are added on the SDKs / Internal Erlang SDKs child pages and links directly to them. Each module's SDK selector shows a live status line (resolved Elixir and Erlang SDK names, or what is wrong -- no SDK, invalid SDK, missing Erlang SDK), matching the status bar widget's wording. In small IDEs the page now lists **all** modules, including projects originally created in IntelliJ IDEA, which previously had no way to pick a per-module SDK in small IDEs.
- [#3856](https://github.com/intellij-elixir/intellij-elixir/pull/3856) - [@sh41](https://github.com/sh41)
  - **Mise users: the plugin now watches your mise config files and re-detects SDKs automatically.** New opt-in per-project settings page (Settings -> Elixir -> Tool Managers) controls which tool managers are active. Running `mise trust` or editing `.mise.toml` triggers a re-scan without restarting the IDE. Errors like "untrusted config" are now shown in notifications instead of silently ignored.
  - **SDK setup on project open is more reliable.** The initial SDK notification scan now waits for the IDE's internal project model to finish loading, preventing a race where newly registered SDKs could disappear moments after being added.
- [#3846](https://github.com/intellij-elixir/intellij-elixir/pull/3846) - [@sh41](https://github.com/sh41)
  - **You'll now be warned if your Elixir SDK was compiled against a different OTP version than your configured Erlang SDK.** Warnings appear in Project Structure (Additional Data panel), the status bar widget balloon, and the "Refresh All Elixir SDK Paths" summary. You can suppress the warning per-SDK if the mismatch is intentional.
- [#3845](https://github.com/intellij-elixir/intellij-elixir/pull/3845) - [@sh41](https://github.com/sh41)
  - **SDK version detection is faster and works without a working Elixir installation.** The plugin now reads the version directly from the `elixir.app` file instead of running `elixir --short-version`. SDK names show the OTP major version (e.g. `mise Elixir 1.15.7-otp-26`). Mise detection now honours `.tool-versions` and parent-directory configs via `mise ls --current`.
- [#3851](https://github.com/intellij-elixir/intellij-elixir/pull/3851) - [@sh41](https://github.com/sh41)
  - **The External Libraries tree is easier to navigate.** SDK library roots now show their OTP app name (e.g. "phoenix" instead of a raw path), and `.ex` source files appear alongside `.beam` files.
- [#3861](https://github.com/intellij-elixir/intellij-elixir/pull/3861) - [@sh41](https://github.com/sh41)
  - **Run/Debug gutter icons now work in WSL-hosted projects.** Icons indicating test status also propagate from individual `test`/`describe` blocks up to the enclosing `describe` and `defmodule`, so you can see the status of the entire group from the gutter. The plugin reads simple `test_load_filters`/`test_paths` from `mix.exs` for correct test file detection.
- [#3858](https://github.com/intellij-elixir/intellij-elixir/pull/3858) - [@sh41](https://github.com/sh41)
  - **Autocomplete now includes functions from BEAM-only (Erlang) dependencies.** Decompiled exported functions appear in unqualified completion with `/arity` tail text and a proper icon.
  - **BEAM viewer improvement (View -> Tool Windows -> BEAM Viewer):** the StrT (string table) tab now shows individual strings with their lengths and auto-sizes columns.
- [#3852](https://github.com/intellij-elixir/intellij-elixir/pull/3852) - [@sh41](https://github.com/sh41)
  - **Fewer "unknown AST node" warnings when decompiling OTP 26+ BEAM files.** Map comprehension nodes (`m_generate`, `mc`) introduced in OTP 26 are now decompiled correctly.
  - Elixir settings consolidated -- renamed "Experimental Settings" to "Elixir Settings" and moved all settings into the top-level Elixir configurable (no more separate child page).
  - Mix deps checker setting -- added an "Enable automatic Mix deps checking" toggle (default enabled) under Elixir Settings. When disabled, no deps check runs on project open or file changes. The checker also skips with a debug log when no Elixir SDK is configured, instead of showing the unhelpful "Mix deps check failed" notification.
  - Erlang SDK prompt for mise Elixir SDKs -- when adding a mise-detected Elixir SDK without an Erlang SDK registered, a chooser dialog now lists valid mise-installed Erlang SDKs (sorted newest first, broken installations filtered out). The selected Erlang SDK is registered and linked automatically.
  - Status bar widget -- when no Elixir SDK is configured, the widget popup now shows a "Detected Elixir SDKs" section listing valid mise installations. Clicking one registers the Elixir SDK, prompts for Erlang if needed, and sets it as the project SDK.
  - **Adding an Elixir SDK in Project Structure now links an Erlang SDK that is already registered**, instead of leaving the Erlang SDK unset.
- [#3843](https://github.com/intellij-elixir/intellij-elixir/pull/3843) - [@sh41](https://github.com/sh41)
  - **The New Project Wizard now configures the Elixir SDK correctly.** SDK type handling was also split into single-responsibility objects.
- [#3891](https://github.com/intellij-elixir/intellij-elixir/pull/3891) - [@makoto-developer](https://github.com/makoto-developer)
  - **README typo fixes** (`Subcription`, `referneced`, `Configuations`, `Wih`) - a first contribution, thank you!

### Bug Fixes

- [#3873](https://github.com/intellij-elixir/intellij-elixir/pull/3873) - [@sh41](https://github.com/sh41)
  - **The "Setup Elixir Module SDK" / "Setup Elixir Facet SDK" editor banner links now work in small IDEs.** They previously called a Project Structure API that is a silent no-op in RubyMine and other small IDEs; they now open the Elixir SDK settings.
  - **Settings -> Elixir -> SDKs / Internal Erlang SDKs no longer show an empty list** when SDKs exist. The shared SDK model was initialised without a project, which loads nothing from the SDK table.
  - **Removing an SDK in Settings -> Elixir -> SDKs no longer throws** and the removed SDK no longer lingers as a "ghost" entry in the per-module SDK chooser.
  - **SDKs registered outside the settings dialog (e.g. via "Configure from mise") now appear in Settings without restarting the IDE.** The settings model refreshes when the SDK table changes.
- [#3849](https://github.com/intellij-elixir/intellij-elixir/pull/3849) - [@sh41](https://github.com/sh41)
  - **Ctrl-click on `div`, `rem`, `is_nil` etc. now navigates to Elixir source instead of landing on a `.beam` file.** The resolver now sorts source results before decompiled ones.
  - **`describe`/`test` blocks resolve correctly when using `ExUnit.CaseTemplate`.** Previously these showed `?` icons in the structure view and wouldn't navigate. Now the plugin follows `use MyApp.ConnCase` chains to find the underlying `ExUnit.Case` macros.
  - **Go-to-Declaration is less noisy in multi-module projects.** Resolver scope narrowed from the entire project to the current module's dependencies, reducing false matches from unrelated modules and/or SDKs.
  - **Navigation works from VCS diff views.** Synthetic files (e.g. the diff editor) now resolve to their real on-disk counterparts.
- [#3850](https://github.com/intellij-elixir/intellij-elixir/pull/3850) - [@sh41](https://github.com/sh41)
  - **Go-to-Declaration on the `String` module (and other modules with `\u{...}` in their docs) now works.** Previously the lexer mishandled Unicode escape sequences inside `~S"""..."""` heredocs, corrupting the parse tree for the rest of the file and causing navigation to fall through to `.beam`.
- [#3839](https://github.com/intellij-elixir/intellij-elixir/pull/3839) - [@sh41](https://github.com/sh41)
  - **IDE no longer freezes when dependencies change.** The dependency sync system has been rewritten from a legacy thread-based watcher to a coroutine pipeline. Changing `mix.lock` now re-scans only the affected project root instead of all roots. Notifications show which module is affected.
- [#3848](https://github.com/intellij-elixir/intellij-elixir/pull/3848) - [@sh41](https://github.com/sh41)
  - **Dialyzer and Credo inspections now analyse the latest code.** Open documents are saved before the inspection runs, so on-disk state matches what you see in the editor.
  - **Umbrella sub-app path dependencies are now classified correctly** during mix sync (previously could appear under the wrong module).
  - **Debugger no longer risks a threading crash** when gathering SDK paths at session start.
- [#3855](https://github.com/intellij-elixir/intellij-elixir/pull/3855) - [@sh41](https://github.com/sh41)
  - **Run configurations are more stable on 2025.3+.** Internal threading contracts are now enforced on the code paths that build Mix/Elixir/IEx command lines, preventing potential crashes when launching run configs.
- [#3841](https://github.com/intellij-elixir/intellij-elixir/pull/3841) - [@sh41](https://github.com/sh41)
  - **Decompiling `.beam` files built by newer OTP no longer fails on unknown instructions.** Adds `TypedRegister` (OTP 25+) and opcodes 177-191 (OTP 25-29) to the BEAM decompiler.
- [#3837](https://github.com/intellij-elixir/intellij-elixir/pull/3837) - [@sh41](https://github.com/sh41)
  - **Elixir code blocks inside `@doc`/`@moduledoc` heredocs inject correctly.** Credo and Dialyzer also gained module guards.
- [#3844](https://github.com/intellij-elixir/intellij-elixir/pull/3844) - [@sh41](https://github.com/sh41)
  - **An Elixir SDK keeps a stable identity across restarts,** keyed on its Erlang home path rather than a derived variant name.
- [#3834](https://github.com/intellij-elixir/intellij-elixir/pull/3834) - [@joshuataylor](https://github.com/joshuataylor)
  - Rethrow `ProcessCanceledException` in spell checking `Splitter` instead of swallowing it.
  - Fix intention preview for `ConvertMatchToTypeOperation`.
- [#3835](https://github.com/intellij-elixir/intellij-elixir/pull/3835) - [@joshuataylor](https://github.com/joshuataylor)
  - `ProgressManager.checkCanceled()` added to `while` loops in `@RequiresReadLock` methods.
  - Replace `Dispatchers.EDT` with `Dispatchers.UI` in `ElixirSdkStatusWidget` (EDT holds write-intent lock unnecessarily for pure UI updates).
  - Use no-arg `AnAction` constructor in `RefreshAllElixirSdksAction` to fix DevKit inspection.

### Threading / Platform Hygiene

- *No user-visible behaviour changes. These improve long-term stability and IDE responsiveness.*
- [#3858](https://github.com/intellij-elixir/intellij-elixir/pull/3858) - [@sh41](https://github.com/sh41)
  - `@RequiresReadLock` / `@RequiresBackgroundThread` annotations added across PSI, structure view, navigation, SDK, mix, and utility layers.
  - `ProgressManager.checkCanceled()` added in large-set iteration loops (improves cancellation responsiveness during long operations).
  - Numerous deprecated-API replacements for forward compatibility with future IDE versions.
  - Shadowed extension functions renamed to avoid GrammarKit member conflicts.

### Build / CI

- *No user-visible changes. These affect contributors and CI infrastructure only.*
- [#3855](https://github.com/intellij-elixir/intellij-elixir/pull/3855) - [@sh41](https://github.com/sh41)
  - GitHub Actions now summarizes the first ≤10 failed tests in the job summary.
  - Reverted to upstream `setup-beam` action (win25 runner issues resolved upstream).
- [#3850](https://github.com/intellij-elixir/intellij-elixir/pull/3850), [#3845](https://github.com/intellij-elixir/intellij-elixir/pull/3845) - [@sh41](https://github.com/sh41)
  - CONTRIBUTING.md: corrected JFlex regeneration instructions; `ElixirFlexLexer.java` moved from `src/` to `gen/`; documented 253 API constraints for debugger and terminal console.
- [#3863](https://github.com/intellij-elixir/intellij-elixir/pull/3863) - [@sh41](https://github.com/sh41)
  - Plugin artifact uploaded unpacked so downloading from GitHub Actions produces a ready-to-install zip without double-wrapping.
- [#3879](https://github.com/intellij-elixir/intellij-elixir/pull/3879) - [@sh41](https://github.com/sh41)
  - Consolidated internal usage of `JpsProjectLoadingManager` (SDK-sync API) to a single, documented call site.
- [#3877](https://github.com/intellij-elixir/intellij-elixir/pull/3877), [#3878](https://github.com/intellij-elixir/intellij-elixir/pull/3878) - [@joshuataylor](https://github.com/joshuataylor)
  - Verified compatibility against IntelliJ IDEA 2026.2 EAP (262.8665.258); dependencies pinned to bundled platform versions.
- [#3875](https://github.com/intellij-elixir/intellij-elixir/pull/3875) - [@sh41](https://github.com/sh41)
  - CI test results now published via `workflow_run` instead of the same-run job.
- [#3874](https://github.com/intellij-elixir/intellij-elixir/pull/3874), [#3864](https://github.com/intellij-elixir/intellij-elixir/pull/3864) - [@sh41](https://github.com/sh41)
  - Routine dependency bumps, including `org.jetbrains.qodana` to 2026.1.3.
- [#3882](https://github.com/intellij-elixir/intellij-elixir/pull/3882) - [@sh41](https://github.com/sh41)
  - Plugin verifier: explicitly declare the dependency on `intellij.testRunner.plugin` so verification doesn't fail on its absence.
- [#3847](https://github.com/intellij-elixir/intellij-elixir/pull/3847) - [@joshuataylor](https://github.com/joshuataylor)
  - `vendorName` and copyright headers updated to the maintainer's current name.
- [#3854](https://github.com/intellij-elixir/intellij-elixir/pull/3854) - [@joshuataylor](https://github.com/joshuataylor)
  - Canary releases built by a single workflow.
- [#3862](https://github.com/intellij-elixir/intellij-elixir/pull/3862) - [@sh41](https://github.com/sh41)
  - Dependency and Gradle bumps; added the `dependencyUpdates` task plugin.
- [#3884](https://github.com/intellij-elixir/intellij-elixir/pull/3884) - [@sh41](https://github.com/sh41)
  - `setup-env` defaults to the minimum supported platform (2025.3.6).
- [#3886](https://github.com/intellij-elixir/intellij-elixir/pull/3886) - [@dependabot](https://github.com/dependabot)
  - Bump `actions/checkout` from 7.0.0 to 7.0.1.
- [#3890](https://github.com/intellij-elixir/intellij-elixir/pull/3890) - [@joshuataylor](https://github.com/joshuataylor)
  - Worked around a `publish-unit-test-result-action` failure.
- [#3895](https://github.com/intellij-elixir/intellij-elixir/pull/3895) - [@sh41](https://github.com/sh41)
  - Test Results checks no longer fail the run when tests fail.

## [23.8.2] - 2026-05-16

### Enhancements

- [#3821](https://github.com/intellij-elixir/intellij-elixir/pull/3821) - [@sh41](https://github.com/sh41)
  - MFA tuple reference resolution: Go-to-Declaration, Find Usages, and hover documentation now work on `:function` atoms inside `{Module, :function, arity}` MFA tuples. Covers Supervisor child specs, `@doc delegate_to:` attributes, and general MFA references throughout Elixir code.
  - Supports both Elixir modules (`{Enum, :map, 2}`) and Erlang modules (`{:math, :sqrt, 1}`). Arity can be an integer literal, an args list (`[arg1, arg2]`), or a dynamic expression/wildcard (resolves with `isValidResult=false` for navigation support without error highlighting).
  - New `UnresolvableModuleQualifier` inspection (`enabledByDefault=true`, error level) flags unresolvable module qualifiers in qualified calls. Highlights the qualifier itself, not the entire call expression.
  - False-positive guards: dynamic qualifiers (module attributes, variables, function call results, bracket access, chained calls), injected doc code fragments (`@doc`/`@moduledoc` heredocs), non-source roots, Phoenix `Router.Helpers` (verifies parent Router module exists, handles both FQN and aliased forms), and opaque `use` calls where `__using__/1` macro cannot be traced (e.g. `ExUnit.CaseTemplate`).
  - Module and alias resolution now prefers a module's Elixir source over its decompiled `.beam`.

### Bug Fixes

- [#3832](https://github.com/intellij-elixir/intellij-elixir/pull/3832), [#3831](https://github.com/intellij-elixir/intellij-elixir/pull/3831) - [@joshuataylor](https://github.com/joshuataylor)
  - The IDE no longer crashes or freezes on 2025.2+ during bulk decompilation, project import, dependency scanning, reference search, Credo and Dialyzer runs, or status-bar SDK updates. Project-model and PSI access from background threads now takes a read lock, and the Dialyzer inspection no longer holds it while waiting for the external process.
  - Downgraded non-critical decompiler/documentation-provider errors from `Logger.error()` to `logger.warn()`. `Logger.error()` creates a `Throwable` and shows an IDE error notification in internal mode -- too noisy for situations where code simply doesn't recognise an element (unknown Erlang AST nodes from newer OTP versions, missing decompiled functions, unhandled element types).

### Threading / Platform Hygiene

- [#3833](https://github.com/intellij-elixir/intellij-elixir/pull/3833) - [@joshuataylor](https://github.com/joshuataylor)
  - Added `@RequiresReadLock` annotations to PSI-accessing methods across 10 files (`CallDefinitionClause`, `Definition`, `Implementation`, `Module`, `Protocol`, `ElixirPsiImplUtil`, `PsiElementImpl`, `PsiNamedElementImpl`, `CallImpl`, `CanonicallyNamedImpl`). Enables the `ThreadingConcurrency` inspection to statically detect callers that don't hold the read lock.

### Build / CI

- [#3822](https://github.com/intellij-elixir/intellij-elixir/pull/3822) - [@sh41](https://github.com/sh41)
  - Moved 108 hand-written `.kt`/`.java` files from `gen/` to `src/` -- these files were vulnerable to silent overwrite by parser regeneration. Regenerating `gen/` previously clobbered them with GrammarKit stubs, causing `StackOverflowError` at runtime when hand-written interface names matched BNF rule names (visitor generates self-recursive methods).
  - Renamed 3 PSI interfaces to avoid GrammarKit visitor collisions: `Heredoc` -> `HeredocLiteral`, `HeredocLine` -> `HeredocLineable`, `SigilHeredoc` -> `SigilHeredocLiteral`.
  - Consolidated ambiguous `ElixirPsiImplUtil` overloads (`processDeclarations`, `getNameIdentifier`, `getReference`) using Java 21 pattern matching switch expressions so that hand edits are no longer needed in generated files after parser regeneration.
  - Marked `gen/` as generated sources in Gradle (`idea.module.generatedSourceDirs`) to suppress inspections on GrammarKit-generated PSI classes.
  - `CONTRIBUTING.md` updated with comprehensive GrammarKit usage conventions: rule names vs interface names, visitor collision avoidance, `extends`/`mixin`/`fake` rules, `ElixirPsiImplUtil` method resolution and ambiguity pitfalls, source layout (`gen/` vs `src/`), CRLF conversion, and testing workflow after BNF changes.
- [#3830](https://github.com/intellij-elixir/intellij-elixir/pull/3830) - [@joshuataylor](https://github.com/joshuataylor)
  - Bumped IntelliJ 2026.1.x target to 2026.1.2.
- [#3832](https://github.com/intellij-elixir/intellij-elixir/pull/3832) - [@joshuataylor](https://github.com/joshuataylor)
  - Bumped Gradle to 9.5.1.
  - Bumped intellij-platform plugin to 2.16.0.
- [#3831](https://github.com/intellij-elixir/intellij-elixir/pull/3831) - [@joshuataylor](https://github.com/joshuataylor)
  - Bumped JetBrains JDK to `jetbrains-21.0.10-b1163.110`.

## [23.5.0] - 2026-05-15

### Enhancements

- [#3817](https://github.com/intellij-elixir/intellij-elixir/pull/3817) - [@sh41](https://github.com/sh41)
  - All Elixir settings panels (Credo, Dialyzer, SDKs, Experimental Settings) grouped under a single "Elixir" parent configurable in both full IDEs and small IDEs (RubyMine, PyCharm, etc.). Dropped redundant "Elixir" prefixes from child panel display names.
  - Settings search indexing via `ElixirSearchableOptionContributor` -- typing "credo", "dialyzer", "elixir", or "liveview" in the Settings search box now surfaces the relevant Elixir settings pages.
  - Credo inspection -- umbrella project support: `resolveCredoWorkingDirectory` walks up from `apps/<app>` content roots to the umbrella root so Credo runs once per umbrella root instead of once per app. Working directories deduplicated by ancestor path.
  - Credo inspection -- execution failure surfacing: failures (missing SDK, missing Credo dependency, compilation errors) now appear as inspection problems on `mix.exs` and as aggregated IDE notifications, instead of being silently dropped.
  - Credo inspection -- partial result preservation: when a Credo run emits some findings before hitting a fatal error, the findings are kept and a warning notification is shown alongside them.
  - Credo inspection -- a finding with an unusual location no longer aborts the run: line-and-column, line-only and file-level findings are all handled, and an invalid path is skipped rather than failing the inspection.
  - Credo inspection -- findings no longer report as an execution failure; a lint-level exit code is expected, not an error.

### Bug Fixes

- [#3817](https://github.com/intellij-elixir/intellij-elixir/pull/3817) - [@sh41](https://github.com/sh41)
  - Credo inspection -- the editor no longer stalls while a run reports its findings, under 2025.3+ writer-preference locking. Partially fixes [#3790](https://github.com/intellij-elixir/intellij-elixir/issues/3790).
  - Credo "Configure credo" notification link opens the Credo settings page again -- it still pointed at the old Editor > Inspections > Errors location after the settings moved under Elixir.
  - Credo and Dialyzer configurable IDs (`"Credo"`, `"Dialyzer"`) collided with display names -- separated into distinct `language.elixir.credo` / `language.elixir.dialyzer` IDs.

## [23.4.0] - 2026-05-15

### Enhancements

- [#3820](https://github.com/intellij-elixir/intellij-elixir/pull/3820) - [@sh41](https://github.com/sh41)
  - Status bar SDK widget -- module SDK inconsistency detection: detects dangling SDK references (module `.iml` references an SDK name that no longer exists in the JDK table, e.g. project cloned between WSL distributions) and SDK mismatches (module uses a different SDK than the project SDK). Notifications fire reactively with deduplication and include specific navigation instructions.
  - Status bar SDK widget -- folder mark validation: detects misconfigured source/test/excluded folder marks on Mix project modules. Uses debounced `rootsChanged()` via `MutableSharedFlow` + 2-second debounce to prevent thrashing during bulk operations. Tracks active notifications and expires them when the issue resolves.
  - "Reconfigure Elixir Module Setup" action (`ReconfigureModuleSetupAction`): additively applies canonical folder marks and fixes dangling/mismatched SDK entries. Preserves user-customised source roots, skips non-existent directories and non-Elixir modules. Available via Tools menu and status bar widget popup.
  - `CANONICAL_FOLDER_MARKS` unification: New Project Wizard-created projects now get the full mark set (`lib/`, `web/`, `spec/`, `test/`, plus 7 exclusions). Previously NPW projects missed `spec/` (ESpec), `web/` (pre-Phoenix 1.3), and all exclusions.
  - `ProjectModuleSetupValidator`: inspects every Mix module's content entries against canonical marks and returns a list of discrepancies.
  - Module type utilities: `isElixirModule()` and `getMixContentRoots()` helpers in `ModuleExtensions.kt`.
  - `isSmallIde` detection fix: `ApplicationInfo` product codes instead of class detection (class detection broke in 2026.1).

### Bug Fixes

- [#3820](https://github.com/intellij-elixir/intellij-elixir/pull/3820) - [@sh41](https://github.com/sh41)
  - "Run Mix ExUnit" context menu missing on test directories containing non-matching `.ex` files in subdirectories: `containsFileWithSuffix` recursive directory walker returned `false` (stop) on non-matching `ElixirFile` instead of `true` (continue), and the `PsiDirectory` branch propagated that premature stop. Renamed `Finder.kt` -> `ContainsFileWithSuffix.kt`. Fixes [#3804](https://github.com/intellij-elixir/intellij-elixir/issues/3804).
  - Replaced deprecated `SystemUtils.isWindows`/`isMac` (Apache Commons) with IntelliJ `SystemInfo`/`OS` utilities across the codebase.
  - Replaced `commons-lang NotImplementedException` with `UnsupportedOperationException` across all uses.
  - Replaced deprecated `SimpleConfigurable.create(Getter)` with `Supplier` overload.
  - `detectSdkVersion` EDT guard: guarded with `runWithModalProgressBlocking` to prevent blocking on the EDT. Possibly addresses [#2980](https://github.com/intellij-elixir/intellij-elixir/issues/2980).

## [23.3.0] - 2026-05-15

### Enhancements

- [#3819](https://github.com/intellij-elixir/intellij-elixir/pull/3819) - [@sh41](https://github.com/sh41)
  - Erlang external documentation support: parse external `.chunk` files (`<app>/doc/chunks/<module>.chunk`) and normalise doc payload across binary, charlist, and structured-term (`application/erlang+html`) variants. Covers OTP 23, 26, and 27 packaging layouts. BEAM decompilation now loads external chunk docs into generated mirror source.
  - Erlang module resolution via atom qualifier syntax: `:math.sqrt(2)`, `:ets.lookup(table, key)` and similar calls now resolve correctly. `maybeModularNameToModulars()` widened from `Set<Call>` to `Set<PsiNamedElement>` to include BEAM-decompiled `ModuleImpl` instances. Unblocks Go-to-Declaration, Find Usages, and autocomplete for all Erlang modules used with atom qualifier syntax.
  - Syntax highlighting in Quick Documentation code blocks: registered `CodeBlockHtmlProvider` and `CodeFenceHtmlProvider` in `MarkdownFlavourDescriptor`. Indented code blocks default to Elixir; fenced blocks dispatch by language hint with Elixir fallback. `RenderedDocCodeBlockRenderer` applies semantic overlays for alias, function call, macro call, and declaration styling.
  - Erlang atom hover resolution: atom targets routed explicitly in documentation lookup with richer function head presentation using metadata-derived spec signatures.
  - `isDocumentationHost` consolidated into single source of truth in `PsiLanguageInjectionHost`, used by both the injection host and the markdown `Injector`.

### Bug Fixes

- [#3819](https://github.com/intellij-elixir/intellij-elixir/pull/3819) - [@sh41](https://github.com/sh41)
  - Quick Documentation (hover/Ctrl+Q) for qualified function calls like `Enum.map(list, fun)` and `GenServer.call(pid, msg)` -- previously showed no docs or fell back to module doc. Four root causes fixed: `ElixirRelativeIdentifier` not forwarded in `getCustomDocumentationElement`; `singleOrNull` returning `null` for multi-clause resolutions (replaced with `firstOrNull`); `filterIsInstance<Call>()` dropping `CallDefinitionImpl` from BEAM-only modules; arity-relaxed fallback not filtering by exact function name. Fixes [#3636](https://github.com/intellij-elixir/intellij-elixir/issues/3636).
  - Hover docs for BEAM macros with default arguments (e.g. `Logger.info("hello")`): added `Docs.documentedByNameFallback()` that searches the name's `TreeMap` for the nearest arity. `BeamDocsHelper` now dispatches by `Definition` kind (function vs macro). Possibly addresses [#3650](https://github.com/intellij-elixir/intellij-elixir/issues/3650), [#3553](https://github.com/intellij-elixir/intellij-elixir/issues/3553), [#3552](https://github.com/intellij-elixir/intellij-elixir/issues/3552), [#3324](https://github.com/intellij-elixir/intellij-elixir/issues/3324), [#3468](https://github.com/intellij-elixir/intellij-elixir/issues/3468), [#2691](https://github.com/intellij-elixir/intellij-elixir/issues/2691).
  - Red parser-error squiggles in Elixir code blocks injected into `@doc`/`@moduledoc`/`@typedoc` heredocs suppressed via `DocCodeBlockHighlightErrorFilter` -- documentation snippets are inherently partial and should not show errors.
  - `@delegate_to` doc attributes no longer trigger "Do not know whether to inject Markdown" error log entries.
  - `do`/`end`/`fn` keywords in injected doc code blocks now styled correctly via a new `Keyword` annotator scoped to injected fragments.
  - Erroneous `startInjecting(MarkdownLanguage.INSTANCE)` removed from `injectElixirInCodeBlocksInQuote` -- the function injects Elixir into indented code blocks; starting a competing Markdown injection was incorrect.

## [23.2.0] - 2026-05-15

### Enhancements

- [#3818](https://github.com/intellij-elixir/intellij-elixir/pull/3818) - [@sh41](https://github.com/sh41)
  - Code completion deduplication: multi-clause functions (e.g. `Enum.map_every` with 5 clauses) now appear once in completion results instead of once per clause head. Shared `PreferFunctionHead` logic selects bare function heads over implementation clauses.
  - Parameter info deduplication: parameter hints grouped by `(name, arity)` -- separate arities still show distinct hints, but multiple clauses of the same arity no longer produce duplicate entries.
  - Completion prefers source-defined modulars over BEAM stubs when both are available, eliminating duplicate completion entries.
  - Transitive alias resolution: when stub-index lookup finds nothing for a module name that is itself a `QualifiableAlias`, resolution now follows alias chains transitively. Possibly addresses [#1806](https://github.com/intellij-elixir/intellij-elixir/issues/1806).
  - `DefinitionsScopedSearch` cancellation: added `ProgressManager.checkCanceled()` at loop boundaries and honour `Processor.process()` return value for early-exit, preventing hangs during large-project searches.

### Bug Fixes

- [#3818](https://github.com/intellij-elixir/intellij-elixir/pull/3818) - [@sh41](https://github.com/sh41)
  - **Breaking change**: removed `nameArityInAnyModule` global fallback from resolver. Previously, when `resolveInScope` found no results, the resolver fell back to a global stub-index search returning every function with a matching name from every module (all marked `validResult=false`). This polluted parameter hints with unrelated modules (e.g. hovering `Enum.map()` showed hints from `Stream.Reducers`, `Ecto`, `Phoenix`), caused Go-to-Definition to navigate to wrong-module definitions, and filled the resolution cache with irrelevant results. Calls that were previously "resolved" to functions in unrelated modules will now correctly appear as unresolved references.
  - Infinite loop in `UnaliasedName.up` when resolving `QualifiedMultipleAliases` -- function overload ordering caused mutual recursion.
  - Infinite recursion and NPE prevention in PSI resolve/tree-walk paths via `RecursionManager.doPreventingRecursion()` and null-safe `VISITED_ELEMENT_SET` access in `ResolveState`.
  - `@spec` line marker grouping checked arity equality before name equality -- specs for different functions with the same arity were incorrectly grouped together.
  - Gutter icons anchored to leaf `PsiElement`s per the `LineMarkerProvider` contract. Non-leaf elements caused markers to blink or appear in wrong positions after edits.
  - Removed redundant `computeReadAction`/`runReadAction` wrappers from `CallImpl` getters (`functionName`, `moduleName`, `resolvedPrimaryArity`) and `PsiNamedElementImpl` name getters. These trivial PSI reads were called from paths already holding a read lock; under 2025.3+ writer-preference locking, re-acquiring blocks the EDT when a write action is pending. Partially fixes [#3790](https://github.com/intellij-elixir/intellij-elixir/issues/3790).
  - `Elixir.` prefix stripping for module name resolution -- stub index stores names without the prefix, so `Elixir.Enum` lookups now match correctly.

## [23.1.0] - 2026-05-15

### Enhancements

- [#3816](https://github.com/intellij-elixir/intellij-elixir/pull/3816) - [@sh41](https://github.com/sh41)
  - Project-scoped coroutine service (`ElixirCoroutineService`) with `supervisedChildScope` for structured, lifecycle-bound concurrency across plugin subsystems.
  - Debugger runtime (Process, Node, MailBox) migrated from unmanaged `executeOnPooledThread` to structured coroutine scopes with cooperative cancellation via `ensureActive()`. Clean `CancellationException` handling prevents false error reports on shutdown.
  - Serialised node-facing debugger operations on a dedicated single-lane `nodeDispatcher`, preventing concurrent BEAM RPC/network calls and preserving operation ordering.
  - WSL debugger source resolution: when a BEAM-reported source path is Linux-style and doesn't resolve locally, converts to Windows UNC via WSL compat and retries. Applied to breakpoint hit navigation, stack frame source mapping, and failed-breakpoint presentation. WSL distribution cached on the debugger process to avoid repeated lookups.
  - `mix format` integration replaced: removed legacy `MixFormatExternalFormatProcessor`, added `MixFormatFormattingService` using `AsyncDocumentFormattingService`. Improved stderr parsing, notification rendering, and error offset navigation.
  - CLI ANSI toggle: added `ansi` parameter to control ANSI escape codes in subprocess output. Disabled for formatter subprocess to keep parsing deterministic.
  - Breakpoint availability hot-path optimisation: added `isInsideModule()` for cheap boolean module-boundary checks, avoiding full module-name assembly. `getModuleName()` traversal now stops at file boundaries to avoid directory traversal.
  - SDK setup modernisation: replaced hand-rolled `invokeAndWait` + boolean-flag pattern in `SdkRegistrar` with `edtWriteAction {}`. `registerOrUpdateErlangSdk` / `registerOrUpdateElixirSdk` are now suspend funs. Replaced `ProgressManager.runProcessWithProgressSynchronously` in `erlang/Type.setupSdkPaths` with `runWithModalProgressBlocking + withContext(Dispatchers.IO)`.
  - Removed unnecessary environment picker complexity from dependent SDK creation flow.
  - Removed blocking pooled-thread wrappers from SDK lookup; tightened read-action/background boundaries.

### Bug Fixes

- [#3816](https://github.com/intellij-elixir/intellij-elixir/pull/3816) - [@sh41](https://github.com/sh41)
  - Debugger node network calls dispatched off EDT via serial `nodeDispatcher`, fixing EDT blocking during debug sessions.
  - VFS blocking read in debugger breakpoint availability checks -- `getModuleName()` directory traversal triggered blocking disk reads on the VFS, now stops at file boundaries. Partially fixes [#3790](https://github.com/intellij-elixir/intellij-elixir/issues/3790).
  - WSL Linux paths not resolving for debugger source navigation -- breakpoint hits, stack frames, and failed-breakpoint messages now resolve correctly when the BEAM reports Linux-style paths on Windows.
  - Erlang SDK `detectSdkVersion` EDT path guarded with `runWithModalProgressBlocking` to prevent unguarded EDT blocking.
  - Debugger deprecation warning and unused variable warning resolved.

## [23.0.7] - 2026-05-15

### Enhancements

- [#3815](https://github.com/intellij-elixir/intellij-elixir/pull/3815) - [@sh41](https://github.com/sh41)
  - Unicode identifier and atom support in the JFlex lexer -- Elixir supports Unicode identifiers (e.g. `def ΦΤ§ do`) and bare Unicode atoms (e.g. `:ΦΤ§`), but the lexer only recognised ASCII characters. Added Unicode letter/digit support using JFlex POSIX character classes.
  - Erlang private functions now decompiled as `defp` instead of `def`, using the BEAM export table to classify functions.
  - Strip `-type`/`-opaque` prefix from Erlang type signatures, mapping to `@typep`/`@opaque`.
  - `Elixir.` prefix stripping for uniform module name resolution -- `Enum.map()` and `Elixir.Enum.map()` now resolve consistently.
  - Type rendering: replaced a `TODO()` crash in `appendTypes()` with actual BEAM doc-chunk signature rendering.
  - Non-blocking bulk decompilation: moved bulk decompile scan off the EDT with run-scoped log deduplication to prevent log floods during library indexing.
  - Improved parse error diagnostics: error reports now include the parser error description and failing source line, with top-10 unique error pattern summary in the bulk-decompile run log.

### Bug Fixes

- [#3815](https://github.com/intellij-elixir/intellij-elixir/pull/3815) - [@sh41](https://github.com/sh41)
  - FD leak fix (WSL/IJent): `InputStream` opened per BEAM file was never closed; after ~20K sequential reads during bulk decompilation, IJent exhausted its vsock FD limit, crashed the gRPC connection, and froze the IDE. Wrapped in `use {}` to release FDs immediately. Fixes [#3613](https://github.com/intellij-elixir/intellij-elixir/issues/3613).
  - Parenthesise block expressions (`case`/`if`/`try`) and nested binary literals inside binary element type specs -- previously emitted unparseable Elixir. Fixes [#3554](https://github.com/intellij-elixir/intellij-elixir/issues/3554), [#3555](https://github.com/intellij-elixir/intellij-elixir/issues/3555).
  - Space after word-based unary operators (`not` etc.) to prevent keyword-argument misparse. Fixes [#3556](https://github.com/intellij-elixir/intellij-elixir/issues/3556).
  - Sanitise Erlang compiler-generated variable names (e.g. `f@_1`) containing `@` -- invalid in Elixir identifiers -- replacing with `_`. Fixes [#3557](https://github.com/intellij-elixir/intellij-elixir/issues/3557).
  - Escape interpolation markers (`#{`) in decompiled atom and string values. Fixes [#3519](https://github.com/intellij-elixir/intellij-elixir/issues/3519).
  - Strip `\r` from BEAM documentation chunk strings before inserting into IntelliJ `Document`. Fixes [#3433](https://github.com/intellij-elixir/intellij-elixir/issues/3433).
  - Handle `:elixir_erl` Dbgi metadata value `:none` (modules compiled with `debug_info: false`) gracefully instead of logging SEVERE errors. Fixes [#3454](https://github.com/intellij-elixir/intellij-elixir/issues/3454).
  - Escape `\u{...}` sequences in decompiled doc strings to prevent Elixir misinterpreting them as Unicode code-point escapes -- was silently breaking the entire `String` module mirror mapping. Fixes [#3412](https://github.com/intellij-elixir/intellij-elixir/issues/3412).
  - Emit `def` prefix on overridden `__struct__/1` signatures so PSI mirror mapping resolves arity-1 struct functions. Fixes [#3596](https://github.com/intellij-elixir/intellij-elixir/issues/3596).
  - Handle Erlang wildcard variable `_` in `record_field` -- eliminates ~100 SEVERE log entries per bulk decompile run. Fixes [#3403](https://github.com/intellij-elixir/intellij-elixir/issues/3403).
  - Fix backslash escaping order: escape `\` before `'` in Erlang charlist rendering. Fixes [#3234](https://github.com/intellij-elixir/intellij-elixir/issues/3234).
  - Add `not` to reserved variable keywords. Fixes [#2825](https://github.com/intellij-elixir/intellij-elixir/issues/2825).
  - Emit `()` for zero-argument anonymous function clauses. Fixes [#2916](https://github.com/intellij-elixir/intellij-elixir/issues/2916).
  - Emit source expression for empty map update associations. Fixes [#2745](https://github.com/intellij-elixir/intellij-elixir/issues/2745).
  - Propagate `doBlock` flag through match expressions in comprehension generators. Fixes [#2907](https://github.com/intellij-elixir/intellij-elixir/issues/2907), [#2908](https://github.com/intellij-elixir/intellij-elixir/issues/2908).
  - Parenthesise nested bitstring generator expressions and wrap non-literal binary element sizes in `size()`. Fixes [#3469](https://github.com/intellij-elixir/intellij-elixir/issues/3469), [#3423](https://github.com/intellij-elixir/intellij-elixir/issues/3423).
  - Render non-integer binary element type specifier values. Fixes [#3240](https://github.com/intellij-elixir/intellij-elixir/issues/3240).
  - Validate alias shape for `Elixir.`-prefixed atoms, falling back to quoted atom form for invalid names. Fixes [#3197](https://github.com/intellij-elixir/intellij-elixir/issues/3197), [#3206](https://github.com/intellij-elixir/intellij-elixir/issues/3206).
  - Indexing deadlock prevention: `Cache.from(FileContent)` now uses `Beam.from(fileContent)` instead of reopening `VirtualFile.inputStream` on cache misses. Fixes [#2544](https://github.com/intellij-elixir/intellij-elixir/issues/2544), [#2333](https://github.com/intellij-elixir/intellij-elixir/issues/2333).
  - Escape backslashes in quoted strings. Fixes [#2728](https://github.com/intellij-elixir/intellij-elixir/issues/2728), [#2635](https://github.com/intellij-elixir/intellij-elixir/issues/2635).
  - Fix decompilation of modules with `Elixir.`-prefixed atoms that contain `do`-`end` operands causing parse cascades. Fixes [#778](https://github.com/intellij-elixir/intellij-elixir/issues/778), [#2769](https://github.com/intellij-elixir/intellij-elixir/issues/2769), [#3420](https://github.com/intellij-elixir/intellij-elixir/issues/3420).

## [23.0.6] - 2026-05-14

### Enhancements

- [#3814](https://github.com/intellij-elixir/intellij-elixir/pull/3814) - [@sh41](https://github.com/sh41)
  - 2026.2 compatibility: use deprecated `selectSdkHome` 2-arg form in SDK setup to compile against both 261 and 262 API. **Note**: On WSL, the SDK file chooser now opens at the user home directory instead of the WSL distribution root. This will be restored when 261 support is dropped.

### Bug Fixes

- [#3814](https://github.com/intellij-elixir/intellij-elixir/pull/3814) - [@sh41](https://github.com/sh41)
  - Fix unreachable `?: "IU"` fallback for `platformType` Gradle property -- `get()` throws on missing properties, so the elvis operator was dead code. Now uses `getOrElse("IU")`.

### Build / CI

- [#3814](https://github.com/intellij-elixir/intellij-elixir/pull/3814) - [@sh41](https://github.com/sh41)
  - CI matrix updated to `windows-2025` runners. IDEA EAP version pinned to `261.24374.34` (Java 25 incompatibility with `LATEST-EAP-SNAPSHOT`).
  - Plugin verifier pinned to 1.384 with markdown/HTML/plain report formats and clickable report output.

## [23.0.5] - 2026-04-28

### Bug Fixes

- Fixed Umbrella project import crash when root folder and child app share the same name (e.g., `emqx/` root with `apps/emqx/` child). The quick import path (`File` -> `Open` on `mix.exs`) bypassed the wizard's duplicate detection, causing `ModuleWithNameAlreadyExists`. Module names are now disambiguated using the relative path (e.g., `emqx` for the root, `emqx-apps-emqx` for the child). - [@joshuataylor](https://github.com/joshuataylor), (Thanks to [@JiaRG](https://github.com/JiaRG) for the thorough and reproducible bug report and excellent example umbrella project!)

## [23.0.4] - 2026-04-26

### Enhancements

- 2026.1 compatibility: remove deprecated `OpenProjectTask.copy()` call in `DirectoryConfigurator`. - [@joshuataylor](https://github.com/joshuataylor)
- 2026.1 compatibility: use `ActionUtil.performAction()` instead of directly invoking `@OverrideOnly` method in `InstallMixDependenciesAction`. - [@joshuataylor](https://github.com/joshuataylor)
- Remove internal API usage: replace `DiagnosticBundle`, `AbstractMessage`, and `PlatformUtils` with public alternatives for plugin verification compatibility. - [@joshuataylor](https://github.com/joshuataylor)

### Build / CI

- Bump Gradle from 9.3.1 to 9.4.1. - [@joshuataylor](https://github.com/joshuataylor)
- Bump kotlinx-coroutines from 1.9.0 to 1.10.2. - [@joshuataylor](https://github.com/joshuataylor)
- Bump gradle-download from 5.6.0 to 5.7.0. - [@joshuataylor](https://github.com/joshuataylor)
- Bump intellij-platform from 2.12.0 to 2.15.0. - [@joshuataylor](https://github.com/joshuataylor)
- Bump Kotlin to 2.3.21. - [@joshuataylor](https://github.com/joshuataylor)

## [23.0.3] - 2026-04-26

### Bug Fixes

- [#3806](https://github.com/intellij-elixir/intellij-elixir/pull/3806) - [@sh41](https://github.com/sh41)
  - Fix indexing deadlock by reading BEAM files from `FileContent` instead of reopening `VirtualFile` input stream. Avoids deadlock on WSL/IJ environments during annotation and highlighting passes.
- [#3807](https://github.com/intellij-elixir/intellij-elixir/pull/3807) - [@sh41](https://github.com/sh41)
  - Remove stale library roots on dependency re-sync, fixing libraries XML files getting updated with redundant data endlessly. Fixes [#3804](https://github.com/intellij-elixir/intellij-elixir/issues/3804).

## [23.0.2] - 2026-04-17

### Enhancements

- [#3763](https://github.com/intellij-elixir/intellij-elixir/pull/3763) - [@sh41](https://github.com/sh41)
  - JPS plugin refactor: fix JPS builder classpath (broken since September 2024), move `HomePath` into IDE module, persist Elixir SDK derived data (`mix-home`, `wsl-unc-path`) with a `data-version` marker and migration, and separate JPS builder from shared modules.
- [#3792](https://github.com/intellij-elixir/intellij-elixir/pull/3792) - [@sh41](https://github.com/sh41)
  - Mix deps tooling: dedicated `mix deps` status parsing, deps checker service with debounced notifications and install action, SDK creation/registration flow with explicit Erlang dependency wiring, and Mise plugin integration for auto-install/configure of Elixir/Erlang SDKs.
  - Non-blocking write actions in UI and formatter to avoid background/EDT exceptions.
  - Hardened CLI argument construction across Mix/IEx/ESpec/ExUnit configurations.
  - WSL path canonicalization and env var handling improvements.
  - 2026.1 compatibility: ignore `ExperimentalPsiDummyBlock` type in `ElixirDocumentationProvider`.
- [#3791](https://github.com/intellij-elixir/intellij-elixir/pull/3791) - [@soomtong](https://github.com/soomtong)
  - Update New Project Wizard to use the new `LanguageGeneratorNewProjectWizard` API with Elixir icon display.
- OTP 28 support: handle `AtU8` atom tables and stubless BEAM fallback. - [@sh41](https://github.com/sh41)
- Handle `@moduledoc`-style `ElixirMatchedAtOperation` in markdown injection. - [@sh41](https://github.com/sh41)
- Handle uncompressed literals and improve error reporting in BEAM `Literals` parsing. - [@sh41](https://github.com/sh41)

### Bug Fixes

- Fix broken IDE navigation by specifying base platform rather than module. - [@sh41](https://github.com/sh41)
- Fix process kill on Windows. - [@sh41](https://github.com/sh41)
- Fix compile server classpath for JPS module jars. - [@sh41](https://github.com/sh41)
- Fix WSL path handling: canonicalize WSL paths, convert only known env vars to WSL paths. - [@sh41](https://github.com/sh41)
- Ensure debugger path is added to code paths if unavailable. - [@sh41](https://github.com/sh41)
- Assert read access in all resolvers to avert deadlocks. - [@sh41](https://github.com/sh41)
- Less aggressive SDK version differentiation in `SdkHomeScan`. - [@sh41](https://github.com/sh41)

### Build / CI

- Bump intellij-platform from 2.11.0 to 2.12.0. - [@joshuataylor](https://github.com/joshuataylor)

## [23.0.1] - 2026-03-06

### Enhancements

- [#3787](https://github.com/intellij-elixir/intellij-elixir/pull/3787) - [@joshuataylor](https://github.com/joshuataylor)
  - Remove internal API usage to improve plugin verification compatibility.
- [#3788](https://github.com/intellij-elixir/intellij-elixir/pull/3788) - [@joshuataylor](https://github.com/joshuataylor)
  - Add separate GitHub Actions job for tagging/releasing, fixing release being overridden on every merge to main.

## [23.0.0] - 2026-02-09

### Enhancements

- WSL Support - [@sh41](https://github.com/sh41) - see [release announcement](https://github.com/intellij-elixir/intellij-elixir/releases/tag/v23.0.0) for full details!
  - Windows Subsystem for Linux support enabling Elixir development in WSL from Windows JetBrains IDEs. Originally submitted as [#3749](https://github.com/intellij-elixir/intellij-elixir/pull/3749), broken into 7 stacked PRs for review. Resolves [#1384](https://github.com/intellij-elixir/intellij-elixir/issues/1384), [#1911](https://github.com/intellij-elixir/intellij-elixir/issues/1911), [#2499](https://github.com/intellij-elixir/intellij-elixir/issues/2499), [#3470](https://github.com/intellij-elixir/intellij-elixir/issues/3470), [#3674](https://github.com/intellij-elixir/intellij-elixir/issues/3674), [#3746](https://github.com/intellij-elixir/intellij-elixir/issues/3746). May also help with [#3659](https://github.com/intellij-elixir/intellij-elixir/issues/3659), [#3716](https://github.com/intellij-elixir/intellij-elixir/issues/3716), [#3715](https://github.com/intellij-elixir/intellij-elixir/issues/3715). See the [README WSL section](https://github.com/intellij-elixir/intellij-elixir?tab=readme-ov-file#windows-subsystem-for-linux-wsl-support) for setup instructions.
  - SDK discovery for Elixir and Erlang inside WSL distributions (asdf, mise, kerl, kiex, Homebrew, Nix).
  - WSL path conversion supporting both `\\wsl$\` and `\\wsl.localhost\` UNC path formats.
  - All run configurations work with WSL: Mix tasks, IEx, ExUnit, ESpec, Distillery, external tools.
  - WSL-safe process handling with graceful BEAM termination across the WSL boundary.
  - WSL-aware SDK naming in UI and status bar widget.
  - SDK settings improvements for small-IDE compatibility (RubyMine, WebStorm, etc.).
- [#3753](https://github.com/intellij-elixir/intellij-elixir/pull/3753) - [@sh41](https://github.com/sh41)
  - Windows development infrastructure: platform-aware build services, Gradle tasks, CI caching (~4.5 GB fork-safe cache), and updated CONTRIBUTING.md.
- [#3757](https://github.com/intellij-elixir/intellij-elixir/pull/3757) - [@sh41](https://github.com/sh41)
  - Mix dependency health checks on project open with install action and notifications via shared MixTaskRunner.
  - Faster and more robust Mix project import with pre-scan of OTP apps off EDT.
- [#3759](https://github.com/intellij-elixir/intellij-elixir/pull/3759) - [@sh41](https://github.com/sh41)
  - IntelliJ IDE Starter-based UI test infrastructure for automated IDE testing.

### Bug Fixes

- [#3761](https://github.com/intellij-elixir/intellij-elixir/pull/3761) - [@sh41](https://github.com/sh41)
  - Fix deadlock when `/deps` directory is deleted while the deps watcher is active.
  - Fix JPS Builder module name handling (broken since September 2024).
- [#3765](https://github.com/intellij-elixir/intellij-elixir/pull/3765) - [@sh41](https://github.com/sh41)
  - Fix false inspection warnings in `~r` regex sigils containing interpolated variables.

## [22.0.0] - 2025-12-16

### Breaking changes

- This release supports 2025.3+ IDEs only (253.xxx).

### Enhancements

- Language Injection for literal sigils (`~H`, `~r`, etc.) - [@polymorfiq](https://github.com/polymorfiq)
- [#3711](https://github.com/intellij-elixir/intellij-elixir/pull/3711) - [@joshuataylor](https://github.com/joshuataylor)
  - Status Bar Widget showing the current project's Elixir SDK version.
  - "Refresh Elixir SDK Classpaths" action to fix SDK classpath issues.
- Compatibility fixes for 2025.1+ (understatement of changes, countless amount of changes to help improve things, thank you so micj) - [@sh41](https://github.com/sh41)
- Extensive EDT threading fixes for 2025.2+ compatibility.

## [21.0.0] - 2025-05-17

### Enhancements

- [#3651](https://github.com/intellij-elixir/intellij-elixir/pull/3681) - [@joshuataylor](https://github.com/joshuataylor)
  - Support Jetbrains 2025.1 and relax until-build.

## [20.0.1] - 2024-11-29

### Bug Fixes

- [#3651](https://github.com/intellij-elixir/intellij-elixir/pull/3667) - [@joshuataylor](https://github.com/joshuataylor)
  - Support Webstorm 2024.3 (243.21565.180). Thanks to [@Kae-Tempest](https://github.com/Kae-Tempest) for the report!

## [20.0.0] - 2024-11-14

### Enhancements

- [#3651](https://github.com/intellij-elixir/intellij-elixir/pull/3651) - [@joshuataylor](https://github.com/joshuataylor)
  - Support Jetbrains 2024.3 (243.21565.193)

## [19.0.1] - 2024-08-20

### Enhancements

- [#3639](https://github.com/intellij-elixir/intellij-elixir/pull/3639) - [@joshuataylor](https://github.com/joshuataylor)
  - Support JetBrains Exception Analyzer
- [#3640](https://github.com/intellij-elixir/intellij-elixir/pull/3640) - [@joshuataylor](https://github.com/joshuataylor)
  - Bump intellij platform gradle to 2.0.1 and IdeaVIM to 2.16.0
- [#3643](https://github.com/intellij-elixir/intellij-elixir/pull/3643) - [@joshuataylor](https://github.com/joshuataylor)
  - Fix RubyMine freezing for umbrella projects but showing the new project wizard as a temporary workaround.

## [19.0.0] - 2024-08-14

### Breaking changes

- [#3619](https://github.com/intellij-elixir/intellij-elixir/pull/3619) - [@joshuataylor](https://github.com/joshuataylor)
  - Drop support for < 2024.2 IDEs

### Enhancements

- [#3619](https://github.com/intellij-elixir/intellij-elixir/pull/3619) - [@joshuataylor](https://github.com/joshuataylor)
  - Support IntelliJ Gradle Plugin 2.0

## [18.0.1] - 2024-08-05

### Enhancements

- [#3582](https://github.com/intellij-elixir/intellij-elixir/pull/3582) - [@rNoz](https://github.com/rNoz)
  - Enabling proper code generation for comments (Comment with Line/Block Comment)

## [18.0.0] - 2024-08-03

### Breaking changes

- [#3569](https://github.com/intellij-elixir/intellij-elixir/pull/3569) - [@ashleysommer](https://github.com/ashleysommer), [@joshuataylor](https://github.com/joshuataylor)
  - Drop support for < 2024.1 IDEs.

## [17.0.1] - 2023-12-24

### Bug Fixes

- [#3491](https://github.com/intellij-elixir/intellij-elixir/pull/3491) - [@neominik](https://github.com/neominik)
  - Render code snippets that are not links.
- [#3562](https://github.com/intellij-elixir/intellij-elixir/pull/3562) - [@ashleysommer](https://github.com/ashleysommer)
  - Add the correct ERL and elixir arguments for starting IEx depending on the version of Elixir SDK.
- [#3563](https://github.com/intellij-elixir/intellij-elixir/pull/3563) - [@ashleysommer](https://github.com/ashleysommer)
  - Bundle latest OtpErlang.jar from JInterface v1.14 for OTP v26.

## [17.0.0] - 2024-01-11

### Breaking changes

- [#3500](https://github.com/intellij-elixir/intellij-elixir/pull/3500) - [@KronicDeth](https://github.com/KronicDeth)
  - Drop support for <= 2023.2 IDEs.

### Enhancements

- [#2402](https://github.com/intellij-elixir/intellij-elixir/pull/3402) - [@joshuataylor](https://github.com/joshuataylor)
  - Support 2023.3 IDEs.

### Bug Fixes

- [#3431](https://github.com/intellij-elixir/intellij-elixir/pull/3431) - [@KronicDeth](https://github.com/KronicDeth)
  - Add `displayName` in `plugin.xml` for configurables for faster menu loading.
    - `org.elixir_lang.facet.configurable.Project` - "Elixir"
    - `org.elixir_lang.facets.sdks.erlang.Configurable` - "Internal Erlang SDKs"
    - `org.elixir_lang.facets.sdks.elixir.Configurable` - "SDKs"

## [16.0.0] - 2023-09-12

- The [CHANGELOG for v15](https://github.com/intellij-elixir/intellij-elixir/blob/v15.1.0/CHANGELOG.md) can be found in [the v16.0.0 tag](https://github.com/intellij-elixir/intellij-elixir/tree/v16.0.0).

[Unreleased]: https://github.com/intellij-elixir/intellij-elixir//compare/v24.0.1...HEAD
[24.0.1]: https://github.com/intellij-elixir/intellij-elixir//compare/v24.0.0...v24.0.1
[24.0.0]: https://github.com/intellij-elixir/intellij-elixir//compare/v23.8.2...v24.0.0
[23.8.2]: https://github.com/intellij-elixir/intellij-elixir//compare/v23.5.0...v23.8.2
[23.5.0]: https://github.com/intellij-elixir/intellij-elixir//compare/v23.4.0...v23.5.0
[23.4.0]: https://github.com/intellij-elixir/intellij-elixir//compare/v23.3.0...v23.4.0
[23.3.0]: https://github.com/intellij-elixir/intellij-elixir//compare/v23.2.0...v23.3.0
[23.2.0]: https://github.com/intellij-elixir/intellij-elixir//compare/v23.1.0...v23.2.0
[23.1.0]: https://github.com/intellij-elixir/intellij-elixir//compare/v23.0.7...v23.1.0
[23.0.7]: https://github.com/intellij-elixir/intellij-elixir//compare/v23.0.6...v23.0.7
[23.0.6]: https://github.com/intellij-elixir/intellij-elixir//compare/v23.0.5...v23.0.6
[23.0.5]: https://github.com/intellij-elixir/intellij-elixir//compare/v23.0.4...v23.0.5
[23.0.4]: https://github.com/intellij-elixir/intellij-elixir//compare/v23.0.3...v23.0.4
[23.0.3]: https://github.com/intellij-elixir/intellij-elixir//compare/v23.0.2...v23.0.3
[23.0.2]: https://github.com/intellij-elixir/intellij-elixir//compare/v23.0.1...v23.0.2
[23.0.1]: https://github.com/intellij-elixir/intellij-elixir//compare/v23.0.0...v23.0.1
[23.0.0]: https://github.com/intellij-elixir/intellij-elixir//compare/v22.0.0...v23.0.0
[22.0.0]: https://github.com/intellij-elixir/intellij-elixir//compare/v21.0.0...v22.0.0
[21.0.0]: https://github.com/intellij-elixir/intellij-elixir//compare/v20.0.1...v21.0.0
[20.0.1]: https://github.com/intellij-elixir/intellij-elixir//compare/v20.0.0...v20.0.1
[20.0.0]: https://github.com/intellij-elixir/intellij-elixir//compare/v19.0.1...v20.0.0
[19.0.1]: https://github.com/intellij-elixir/intellij-elixir//compare/v19.0.0...v19.0.1
[19.0.0]: https://github.com/intellij-elixir/intellij-elixir//compare/v18.0.1...v19.0.0
[18.0.1]: https://github.com/intellij-elixir/intellij-elixir//compare/v18.0.0...v18.0.1
[18.0.0]: https://github.com/intellij-elixir/intellij-elixir//compare/v17.0.1...v18.0.0
[17.0.1]: https://github.com/intellij-elixir/intellij-elixir//compare/v17.0.0...v17.0.1
[17.0.0]: https://github.com/intellij-elixir/intellij-elixir//compare/v16.0.0...v17.0.0
[16.0.0]: https://github.com/intellij-elixir/intellij-elixir//commits/v16.0.0
