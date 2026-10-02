# Source code recreated from a .beam file by IntelliJ Elixir
defmodule Module do
  @moduledoc ~S'''
  Provides functions to deal with modules during compilation time.

  It allows a developer to dynamically add, delete and register
  attributes, attach documentation and so forth.

  After a module is compiled, using many of the functions in
  this module will raise errors, since it is out of their scope
  to inspect runtime data. Most of the runtime data can be inspected
  via the [`__info__/1`](`c:Module.__info__/1`) function attached to
  each compiled module.

  ## Module attributes

  Each module can be decorated with one or more attributes. The following ones
  are currently defined by Elixir:

  ### `@after_compile`

  A hook that will be invoked right after the current module is compiled.
  Accepts a module or a `{module, function_name}`. See the "Compile callbacks"
  section below.

  ### `@before_compile`

  A hook that will be invoked before the module is compiled.
  Accepts a module or a `{module, function_or_macro_name}` tuple.
  See the "Compile callbacks" section below.

  ### `@behaviour`

  Note the British spelling!

  Behaviours can be referenced by modules to ensure they implement
  required specific function signatures defined by `@callback`.

  For example, you could specify a `URI.Parser` behaviour as follows:

      defmodule URI.Parser do
        @doc "Defines a default port"
        @callback default_port() :: integer

        @doc "Parses the given URL"
        @callback parse(uri_info :: URI.t()) :: URI.t()
      end

  And then a module may use it as:

      defmodule URI.HTTP do
        @behaviour URI.Parser
        def default_port(), do: 80
        def parse(info), do: info
      end

  If the behaviour changes or `URI.HTTP` does not implement
  one of the callbacks, a warning will be raised.

  For detailed documentation, see the
  [behaviour typespec documentation](typespecs.md#behaviours).

  ### `@impl`

  To aid in the correct implementation of behaviours, you may optionally declare
  `@impl` for implemented callbacks of a behaviour. This makes callbacks
  explicit and can help you to catch errors in your code. The compiler will warn
  in these cases:

    * if you mark a function with `@impl` when that function is not a callback.

    * if you don't mark a function with `@impl` when other functions are marked
      with `@impl`. If you mark one function with `@impl`, you must mark all
      other callbacks for that behaviour as `@impl`.

  `@impl` works on a per-context basis. If you generate a function through a macro
  and mark it with `@impl`, that won't affect the module where that function is
  generated in.

  `@impl` also helps with maintainability by making it clear to other developers
  that the function is implementing a callback.

  Using `@impl`, the example above can be rewritten as:

      defmodule URI.HTTP do
        @behaviour URI.Parser

        @impl true
        def default_port(), do: 80

        @impl true
        def parse(info), do: info
      end

  You may pass either `false`, `true`, or a specific behaviour to `@impl`.

      defmodule Foo do
        @behaviour Bar
        @behaviour Baz

        # Will warn if neither Bar nor Baz specify a callback named bar/0.
        @impl true
        def bar(), do: :ok

        # Will warn if Baz does not specify a callback named baz/0.
        @impl Baz
        def baz(), do: :ok
      end

  The code is now more readable, as it is now clear which functions are
  part of your API and which ones are callback implementations. To reinforce this
  idea, `@impl true` automatically marks the function as `@doc false`, disabling
  documentation unless `@doc` is explicitly set.

  ### `@compile`

  Defines options for module compilation. This is used to configure
  both Elixir and Erlang compilers, as any other compilation pass
  added by external tools. For example:

      defmodule MyModule do
        @compile {:inline, my_fun: 1}

        def my_fun(arg) do
          to_string(arg)
        end
      end

  Multiple uses of `@compile` will accumulate instead of overriding
  previous ones. See the "Compile options" section below.

  ### `@deprecated`

  Provides the deprecation reason for a function. For example:

      defmodule Keyword do
        @deprecated "Use Kernel.length/1 instead"
        def size(keyword) do
          length(keyword)
        end
      end

  The Mix compiler automatically looks for calls to deprecated modules
  and emit warnings during compilation.

  Using the `@deprecated` attribute will also be reflected in the
  documentation of the given function and macro. You can choose between
  the `@deprecated` attribute and the documentation metadata to provide
  hard-deprecations (with warnings) and soft-deprecations (without warnings):

  This is a soft-deprecation as it simply annotates the documentation
  as deprecated:

      @doc deprecated: "Use Kernel.length/1 instead"
      def size(keyword)

  This is a hard-deprecation as it emits warnings and annotates the
  documentation as deprecated:

      @deprecated "Use Kernel.length/1 instead"
      def size(keyword)

  Currently `@deprecated` only supports functions and macros. However
  you can use the `:deprecated` key in the annotation metadata to
  annotate the docs of modules, types and callbacks too.

  We recommend using this feature with care, especially library authors.
  Deprecating code always pushes the burden towards library users. We
  also recommend for deprecated functionality to be maintained for long
  periods of time, even after deprecation, giving developers plenty of
  time to update (except for cases where keeping the deprecated API is
  undesired, such as in the presence of security issues).

  ### `@doc` and `@typedoc`

  Provides documentation for the entity that follows the attribute.
  `@doc` is to be used with a function, macro, callback, or
  macrocallback, while `@typedoc` with a type (public or opaque).

  Accepts a string (often a heredoc) or `false` where `@doc false` will
  make the entity invisible to documentation extraction tools like
  [`ExDoc`](https://hexdocs.pm/ex_doc/). For example:

      defmodule MyModule do
        @typedoc "This type"
        @typedoc since: "1.1.0"
        @type t :: term

        @doc "Hello world"
        @doc since: "1.1.0"
        def hello do
          "world"
        end

        @doc """
        Sums `a` to `b`.
        """
        def sum(a, b) do
          a + b
        end
      end

  As can be seen in the example above, `@doc` and `@typedoc` also accept
  a keyword list that serves as a way to provide arbitrary metadata
  about the entity. Tools like [`ExDoc`](https://hexdocs.pm/ex_doc/) and
  `IEx` may use this information to display annotations. A common use
  case is `since` that may be used to annotate in which version the
  function was introduced.

  As illustrated in the example, it is possible to use these attributes
  more than once before an entity. However, the compiler will warn if
  used twice with binaries as that replaces the documentation text from
  the preceding use. Multiple uses with keyword lists will merge the
  lists into one.

  Note that since the compiler also defines some additional metadata,
  there are a few reserved keys that will be ignored and warned if used.
  Currently these are: `:opaque` and `:defaults`.

  Once this module is compiled, this information becomes available via
  the `Code.fetch_docs/1` function.

  ### `@dialyzer`

  Defines warnings to request or suppress when using a version of
  `:dialyzer` that supports module attributes.

  Accepts an atom, a tuple, or a list of atoms and tuples. For example:

      defmodule MyModule do
        @dialyzer {:nowarn_function, my_fun: 1}

        def my_fun(arg) do
          M.not_a_function(arg)
        end
      end

  For the list of supported warnings, see
  [`:dialyzer` module](http://www.erlang.org/doc/man/dialyzer.html).

  Multiple uses of `@dialyzer` will accumulate instead of overriding
  previous ones.

  ### `@external_resource`

  Specifies an external resource for the current module.

  Sometimes a module embeds information from an external file. This
  attribute allows the module to annotate which external resources
  have been used.

  Tools may use this information to ensure the module is recompiled
  in case any of the external resources change, see for example:
  [`mix compile.elixir`](https://hexdocs.pm/mix/Mix.Tasks.Compile.Elixir.html).

  If the external resource does not exist, the module still has
  a dependency on it, causing the module be recompiled as soon
  as the file is added.

  ### `@file`

  Changes the filename used in stacktraces for the function or macro that
  follows the attribute, such as:

      defmodule MyModule do
        @doc "Hello world"
        @file "hello.ex"
        def hello do
          "world"
        end
      end

  ### `@moduledoc`

  Provides documentation for the current module.

      defmodule MyModule do
        @moduledoc """
        A very useful module.
        """
        @moduledoc authors: ["Alice", "Bob"]
      end

  Accepts a string (often a heredoc) or `false` where `@moduledoc false`
  will make the module invisible to documentation extraction tools like
  [`ExDoc`](https://hexdocs.pm/ex_doc/).

  Similarly to `@doc` also accepts a keyword list to provide metadata
  about the module. For more details, see the documentation of `@doc`
  above.

  Once this module is compiled, this information becomes available via
  the `Code.fetch_docs/1` function.

  ### `@on_definition`

  A hook that will be invoked when each function or macro in the current
  module is defined. Useful when annotating functions.

  Accepts a module or a `{module, function_name}` tuple. See the
  "Compile callbacks" section below.

  ### `@on_load`

  A hook that will be invoked whenever the module is loaded.

  Accepts the function name (as an atom) of a function in the current module or
  `{function_name, 0}` tuple where `function_name` is the name of a function in
  the current module. The function must be public and have an arity of 0 (no
  arguments). If the function does not return `:ok`, the loading of the module
  will be aborted. For example:

      defmodule MyModule do
        @on_load :load_check

        def load_check do
          if some_condition() do
            :ok
          else
            :abort
          end
        end

        def some_condition do
          false
        end
      end

  Modules compiled with HiPE would not call this hook.

  ### `@vsn`

  Specify the module version. Accepts any valid Elixir value, for example:

      defmodule MyModule do
        @vsn "1.0"
      end

  ### Typespec attributes

  The following attributes are part of typespecs and are also built-in in
  Elixir:

    * `@type` - defines a type to be used in `@spec`
    * `@typep` - defines a private type to be used in `@spec`
    * `@opaque` - defines an opaque type to be used in `@spec`
    * `@spec` - provides a specification for a function
    * `@callback` - provides a specification for a behaviour callback
    * `@macrocallback` - provides a specification for a macro behaviour callback
    * `@optional_callbacks` - specifies which behaviour callbacks and macro
      behaviour callbacks are optional
    * `@impl` - declares an implementation of a callback function or macro

  For detailed documentation, see the [typespec documentation](typespecs.md).

  ### Custom attributes

  In addition to the built-in attributes outlined above, custom attributes may
  also be added. Custom attributes are expressed using the `@/1` operator followed
  by a valid variable name. The value given to the custom attribute must be a valid
  Elixir value:

      defmodule MyModule do
        @custom_attr [some: "stuff"]
      end

  For more advanced options available when defining custom attributes, see
  `register_attribute/3`.

  ## Compile callbacks

  There are three callbacks that are invoked when functions are defined,
  as well as before and immediately after the module bytecode is generated.

  ### `@after_compile`

  A hook that will be invoked right after the current module is compiled.

  Accepts a module or a `{module, function_name}` tuple. The function
  must take two arguments: the module environment and its bytecode.
  When just a module is provided, the function is assumed to be
  `__after_compile__/2`.

  Callbacks will run in the order they are registered.

  #### Example

      defmodule MyModule do
        @after_compile __MODULE__

        def __after_compile__(env, _bytecode) do
          IO.inspect(env)
        end
      end

  ### `@before_compile`

  A hook that will be invoked before the module is compiled.

  Accepts a module or a `{module, function_or_macro_name}` tuple. The
  function/macro must take one argument: the module environment. If
  it's a macro, its returned value will be injected at the end of the
  module definition before the compilation starts.

  When just a module is provided, the function/macro is assumed to be
  `__before_compile__/1`.

  Callbacks will run in the order they are registered. Any overridable
  definition will be made concrete before the first callback runs.
  A definition may be made overridable again in another before compile
  callback and it will be made concrete one last time after all callbacks
  run.

  *Note*: unlike `@after_compile`, the callback function/macro must
  be placed in a separate module (because when the callback is invoked,
  the current module does not yet exist).

  #### Example

      defmodule A do
        defmacro __before_compile__(_env) do
          quote do
            def hello, do: "world"
          end
        end
      end

      defmodule B do
        @before_compile A
      end

      B.hello()
      #=> "world"

  ### `@on_definition`

  A hook that will be invoked when each function or macro in the current
  module is defined. Useful when annotating functions.

  Accepts a module or a `{module, function_name}` tuple. The function
  must take 6 arguments:

    * the module environment
    * the kind of the function/macro: `:def`, `:defp`, `:defmacro`, or `:defmacrop`
    * the function/macro name
    * the list of quoted arguments
    * the list of quoted guards
    * the quoted function body

  If the function/macro being defined has multiple clauses, the hook will
  be called for each clause.

  Unlike other hooks, `@on_definition` will only invoke functions and
  never macros. This is to avoid `@on_definition` callbacks from
  redefining functions that have just been defined in favor of more
  explicit approaches.

  When just a module is provided, the function is assumed to be
  `__on_definition__/6`.

  #### Example

      defmodule Hooks do
        def on_def(_env, kind, name, args, guards, body) do
          IO.puts("Defining #{kind} named #{name} with args:")
          IO.inspect(args)
          IO.puts("and guards")
          IO.inspect(guards)
          IO.puts("and body")
          IO.puts(Macro.to_string(body))
        end
      end

      defmodule MyModule do
        @on_definition {Hooks, :on_def}

        def hello(arg) when is_binary(arg) or is_list(arg) do
          "Hello" <> to_string(arg)
        end

        def hello(_) do
          :ok
        end
      end

  ## Compile options

  The `@compile` attribute accepts different options that are used by both
  Elixir and Erlang compilers. Some of the common use cases are documented
  below:

    * `@compile :debug_info` - includes `:debug_info` regardless of the
      corresponding setting in `Code.get_compiler_option/1`

    * `@compile {:debug_info, false}` - disables `:debug_info` regardless
      of the corresponding setting in `Code.get_compiler_option/1`

    * `@compile {:inline, some_fun: 2, other_fun: 3}` - inlines the given
      name/arity pairs. Inlining is applied locally, calls from another
      module are not affected by this option

    * `@compile {:autoload, false}` - disables automatic loading of
      modules after compilation. Instead, the module will be loaded after
      it is dispatched to

    * `@compile {:no_warn_undefined, Mod}` or
      `@compile {:no_warn_undefined, {Mod, fun, arity}}` - does not warn if
      the given module or the given `Mod.fun/arity` are not defined


  '''

  # Functions

  @doc false
  def __get_attribute__(module, key, line) when is_atom(key) do
    (
      assert_not_compiled!({:get_attribute, 2}, module, "Use the Module.__info__/1 callback or Code.fetch_docs/1 instead")
      {set, bag} = data_tables_for(module)
      case(:ets.lookup(set, key)) do
        [{_, _, :accumulate}] ->
          :lists.reverse(bag_lookup_element(bag, {:accumulate, key}, 2))
        [{_, val, line}] when is_integer(line) ->
          :ets.update_element(set, key, {3, :used})
          val
        [{_, val, _}] ->
          val
        [] when is_integer(line) ->
          error_message = <<"undefined module attribute @"::binary(), String.Chars.to_string(key)::binary(), ", "::binary(), "please remove access to @"::binary(), String.Chars.to_string(key)::binary(), " or explicitly set it before access"::binary()>>
          IO.warn(error_message, attribute_stack(module, line))
          nil
        [] ->
          nil
      end
    )
  end

  def __info__(arg1) do
    # body not decompiled
  end

  @doc false
  def __put_attribute__(module, key, value, line) when is_atom(key) do
    (
      assert_not_readonly!({:__put_attribute__, 4}, module)
      {set, bag} = data_tables_for(module)
      value = preprocess_attribute(key, value)
      put_attribute(module, key, value, line, set, bag)
      :ok
    )
  end

  def add_doc(x0, x1, x2, x3, x4) do
    super(x0, x1, x2, x3, [], x4)
  end


  @deprecated """
  Use @doc instead
  """

  @doc false
  def add_doc(module, line, kind, {name, arity}, signature, doc) do
    (
      assert_not_compiled!({:add_doc, 6}, module)
      case(kind === :defp or kind === :defmacrop or kind === :typep) do
        false ->
          {set, _bag} = data_tables_for(module)
          compile_doc(set, nil, line, kind, name, arity, signature, nil, doc, %{}, %Macro.Env{aliases: [], context: nil, context_modules: [Module], contextual_vars: [], current_vars: {%{{:_bag, nil} => 8, {:arity, nil} => 4, {:doc, nil} => 6, {:kind, nil} => 2, {:line, nil} => 1, {:module, nil} => 0, {:name, nil} => 3, {:set, nil} => 7, {:signature, nil} => 5}, %{{:_bag, nil} => 8, {:arity, nil} => 4, {:doc, nil} => 6, {:kind, nil} => 2, {:line, nil} => 1, {:module, nil} => 0, {:name, nil} => 3, {:set, nil} => 7, {:signature, nil} => 5}}, file: "/private/tmp/elixir-20201103-80297-pfqt4c/elixir-1.11.2/lib/elixir/lib/module.ex", function: {:add_doc, 6}, functions: [{Kernel, [!=: 2, !==: 2, *: 2, +: 1, +: 2, ++: 2, -: 1, -: 2, --: 2, /: 2, <: 2, <=: 2, ==: 2, ===: 2, =~: 2, >: 2, >=: 2, abs: 1, apply: 2, apply: 3, binary_part: 3, bit_size: 1, byte_size: 1, ceil: 1, div: 2, elem: 2, exit: 1, floor: 1, function_exported?: 3, get_and_update_in: 3, get_in: 2, hd: 1, inspect: 1, inspect: 2, is_atom: 1, is_binary: 1, is_bitstring: 1, is_boolean: 1, is_float: 1, is_function: 1, is_function: 2, is_integer: 1, is_list: 1, is_map: 1, is_map_key: 2, is_number: 1, is_pid: 1, is_port: 1, is_reference: 1, is_tuple: 1, length: 1, macro_exported?: 3, make_ref: 0, map_size: 1, max: 2, min: 2, node: 0, node: 1, not: 1, pop_in: 2, put_elem: 3, put_in: 3, rem: 2, round: 1, self: 0, send: 2, spawn: 1, spawn: 3, spawn_link: 1, spawn_link: 3, spawn_monitor: 1, spawn_monitor: 3, struct: 1, struct: 2, struct!: 1, struct!: 2, throw: 1, tl: 1, trunc: 1, tuple_size: 1, update_in: 3]}], lexical_tracker: nil, line: 1405, macro_aliases: [], macros: [{Kernel, [!: 1, &&: 2, ..: 2, <>: 2, @: 1, alias!: 1, and: 2, binding: 0, binding: 1, def: 1, def: 2, defdelegate: 2, defexception: 1, defguard: 1, defguardp: 1, defimpl: 2, defimpl: 3, defmacro: 1, defmacro: 2, defmacrop: 1, defmacrop: 2, defmodule: 2, defoverridable: 1, defp: 1, defp: 2, defprotocol: 2, defstruct: 1, destructure: 2, get_and_update_in: 2, if: 2, in: 2, is_exception: 1, is_exception: 2, is_nil: 1, is_struct: 1, is_struct: 2, match?: 2, or: 2, pop_in: 1, put_in: 2, raise: 1, raise: 2, reraise: 2, reraise: 3, sigil_C: 2, sigil_D: 2, sigil_N: 2, sigil_R: 2, sigil_S: 2, sigil_T: 2, sigil_U: 2, sigil_W: 2, sigil_c: 2, sigil_r: 2, sigil_s: 2, sigil_w: 2, to_char_list: 1, to_charlist: 1, to_string: 1, unless: 2, update_in: 2, use: 1, use: 2, var!: 1, var!: 2, |>: 2, ||: 2]}], module: Module, prematch_vars: :warn, requires: [Application, Kernel, Kernel.Typespec], tracers: [], unused_vars: {%{{:_bag, 8} => 1404, {:arity, 4} => false, {:doc, 6} => false, {:kind, 2} => false, {:line, 1} => false, {:module, 0} => false, {:name, 3} => false, {:set, 7} => false, {:signature, 5} => false}, 9}, vars: [arity: nil, doc: nil, kind: nil, line: nil, module: nil, name: nil, signature: nil]}, false)
          :ok
        true ->
          if(doc) do
            :ok
          else
            {:error, :private_doc}
          end
      end
    )
  end

  def behaviour_info(arg1) do
    # body not decompiled
  end

  @doc false
  def check_behaviours_and_impls(env, _set, bag, all_definitions) do
    (
      behaviours = bag_lookup_element(bag, {:accumulate, :behaviour}, 2)
      impls = bag_lookup_element(bag, :impls, 2)
      callbacks = check_behaviours(env, behaviours)
      pending_callbacks = case(impls != []) do
        false ->
          callbacks
        true ->
          {non_implemented_callbacks, contexts} = check_impls(env, behaviours, callbacks, impls)
          warn_missing_impls(env, non_implemented_callbacks, contexts, all_definitions)
          non_implemented_callbacks
      end
      check_callbacks(env, pending_callbacks, all_definitions)
      :ok
    )
  end

  @doc false
  def compile_definition_attributes(env, kind, name, args, _guards, body) do
    (
      %{module: module} = env
      {set, bag} = data_tables_for(module)
      {arity, defaults} = args_count(args, 0, 0)
      context = Keyword.get(:ets.lookup_element(set, {:def, {name, arity}}, 3), :context)
      impl = compile_impl(set, bag, context, name, env, kind, arity, defaults)
      doc_meta = compile_doc_meta(set, bag, name, arity, defaults)
      {line, doc} = get_doc_info(set, env)
      compile_doc(set, context, line, kind, name, arity, args, body, doc, doc_meta, env, impl)
      :ok
    )
  end

  @doc ~S"""
  Concatenates a list of aliases and returns a new alias.

  ## Examples

      iex> Module.concat([Foo, Bar])
      Foo.Bar

      iex> Module.concat([Foo, "Bar"])
      Foo.Bar


  """
  def concat(list) when is_list(list) do
    :elixir_aliases.concat(list)
  end

  @doc ~S"""
  Concatenates two aliases and returns a new alias.

  ## Examples

      iex> Module.concat(Foo, Bar)
      Foo.Bar

      iex> Module.concat(Foo, "Bar")
      Foo.Bar


  """
  def concat(left, right) when (is_binary(left) or is_atom(left)) and (is_binary(right) or is_atom(right)) do
    :elixir_aliases.concat([left, right])
  end

  @doc ~S"""
  Creates a module with the given name and defined by
  the given quoted expressions.

  The line where the module is defined and its file **must**
  be passed as options.

  It returns a tuple of shape `{:module, module, binary, term}`
  where `module` is the module name, `binary` is the module
  bytecode and `term` is the result of the last expression in
  `quoted`.

  Similar to `Kernel.defmodule/2`, the binary will only be
  written to disk as a `.beam` file if `Module.create/3` is
  invoked in a file that is currently being compiled.

  ## Examples

      contents =
        quote do
          def world, do: true
        end

      Module.create(Hello, contents, Macro.Env.location(__ENV__))

      Hello.world()
      #=> true

  ## Differences from `defmodule`

  `Module.create/3` works similarly to `Kernel.defmodule/2`
  and return the same results. While one could also use
  `defmodule` to define modules dynamically, this function
  is preferred when the module body is given by a quoted
  expression.

  Another important distinction is that `Module.create/3`
  allows you to control the environment variables used
  when defining the module, while `Kernel.defmodule/2`
  automatically uses the environment it is invoked at.

  """
  def create(module, quoted, %Macro.Env{} = env) when is_atom(module) do
    create(module, quoted, :maps.to_list(env))
  end

  def create(module, quoted, opts) when is_atom(module) and is_list(opts) do
    (
      if(Keyword.has_key?(opts, :file)) do
        raise(ArgumentError, "expected :file to be given as option")
      else
        nil
      end
      next = :elixir_module.next_counter(nil)
      line = Keyword.get(opts, :line, 0)
      quoted = :elixir_quote.linify_with_context_counter(line, {module, next}, quoted)
      :elixir_module.compile(module, quoted, [], :elixir.env_for_eval(opts))
    )
  end

  @doc ~S"""
  Checks if the module defines the given function or macro.

  Use `defines?/3` to assert for a specific type.

  This function can only be used on modules that have not yet been compiled.
  Use `Kernel.function_exported?/3` and `Kernel.macro_exported?/3` to check for
  public functions and macros respectively in compiled modules.

  Note that `defines?` returns false for functions and macros that have
  been defined but then marked as overridable and no other implementation
  has been provided. You can check the overridable status by calling
  `overridable?/2`.

  ## Examples

      defmodule Example do
        Module.defines?(__MODULE__, {:version, 0}) #=> false
        def version, do: 1
        Module.defines?(__MODULE__, {:version, 0}) #=> true
      end


  """
  def defines?(module, {name, arity} = tuple) when is_atom(module) and is_atom(name) and is_integer(arity) and arity >= 0 and arity <= 255 do
    (
      assert_not_compiled!({:defines?, 2}, module, "Use Kernel.function_exported?/3 and Kernel.macro_exported?/3 to check for public functions and macros instead")
      {set, _bag} = data_tables_for(module)
      :ets.member(set, {:def, tuple})
    )
  end

  @doc ~S"""
  Checks if the module defines a function or macro of the
  given `kind`.

  `kind` can be any of `:def`, `:defp`, `:defmacro`, or `:defmacrop`.

  This function can only be used on modules that have not yet been compiled.
  Use `Kernel.function_exported?/3` and `Kernel.macro_exported?/3` to check for
  public functions and macros respectively in compiled modules.

  ## Examples

      defmodule Example do
        Module.defines?(__MODULE__, {:version, 0}, :def) #=> false
        def version, do: 1
        Module.defines?(__MODULE__, {:version, 0}, :def) #=> true
      end


  """
  def defines?(module, {name, arity} = tuple, def_kind) when is_atom(module) and is_atom(name) and is_integer(arity) and arity >= 0 and arity <= 255 and (def_kind === :def or def_kind === :defp or def_kind === :defmacro or def_kind === :defmacrop) do
    (
      assert_not_compiled!({:defines?, 3}, module, "Use Kernel.function_exported?/3 and Kernel.macro_exported?/3 to check for public functions and macros instead")
      {set, _bag} = data_tables_for(module)
      match?([{_, ^def_kind, _, _, _, _}], :ets.lookup(set, {:def, tuple}))
    )
  end

  @doc ~S"""
  Checks if the current module defines the given type (private, opaque or not).

  This function is only available for modules being compiled.

  """
  def defines_type?(module, definition) do
    Kernel.Typespec.defines_type?(module, definition)
  end

  @doc ~S"""
  Returns all functions and macros defined in `module`.

  It returns a list with all defined functions and macros, public and private,
  in the shape of `[{name, arity}, ...]`.

  This function can only be used on modules that have not yet been compiled.
  Use the `c:Module.__info__/1` callback to get the public functions and macros in
  compiled modules.

  ## Examples

      defmodule Example do
        def version, do: 1
        defmacrop test(arg), do: arg
        Module.definitions_in(__MODULE__) #=> [{:version, 0}, {:test, 1}]
      end


  """
  def definitions_in(module) when is_atom(module) do
    (
      assert_not_compiled!({:definitions_in, 1}, module, "Use the Module.__info__/1 callback to get public functions and macros instead")
      {_, bag} = data_tables_for(module)
      bag_lookup_element(bag, :defs, 2)
    )
  end

  @doc ~S"""
  Returns all functions defined in `module`, according
  to its kind.

  This function can only be used on modules that have not yet been compiled.
  Use the `c:Module.__info__/1` callback to get the public functions and macros in
  compiled modules.

  ## Examples

      defmodule Example do
        def version, do: 1
        Module.definitions_in(__MODULE__, :def)  #=> [{:version, 0}]
        Module.definitions_in(__MODULE__, :defp) #=> []
      end


  """
  def definitions_in(module, kind) when is_atom(module) and (kind === :def or kind === :defp or kind === :defmacro or kind === :defmacrop) do
    (
      assert_not_compiled!({:definitions_in, 2}, module, "Use the Module.__info__/1 callback to get public functions and macros instead")
      {set, _} = data_tables_for(module)
      :ets.select(set, [{{{:def, :"$1"}, kind, :_, :_, :_, :_}, [], [:"$1"]}])
    )
  end

  @doc ~S"""
  Deletes the module attribute that matches the given key.

  It returns the deleted attribute value (or `nil` if nothing was set).

  ## Examples

      defmodule MyModule do
        Module.put_attribute(__MODULE__, :custom_threshold_for_lib, 10)
        Module.delete_attribute(__MODULE__, :custom_threshold_for_lib)
      end


  """
  def delete_attribute(module, key) when is_atom(module) and is_atom(key) do
    (
      assert_not_readonly!({:delete_attribute, 2}, module)
      {set, bag} = data_tables_for(module)
      case(:ets.lookup(set, key)) do
        [{_, _, :accumulate}] ->
          reverse_values(:ets.take(bag, {:accumulate, key}), [])
        [{_, value, _}] ->
          :ets.delete(set, key)
          value
        [] ->
          nil
      end
    )
  end

  def eval_quoted(x0, x1) do
    super(x0, x1, [], [])
  end

  def eval_quoted(x0, x1, x2) do
    super(x0, x1, x2, [])
  end

  @doc ~S"""
  Evaluates the quoted contents in the given module's context.

  A list of environment options can also be given as argument.
  See `Code.eval_string/3` for more information.

  Raises an error if the module was already compiled.

  ## Examples

      defmodule Foo do
        contents =
          quote do
            def sum(a, b), do: a + b
          end

        Module.eval_quoted(__MODULE__, contents)
      end

      Foo.sum(1, 2)
      #=> 3

  For convenience, you can pass any `Macro.Env` struct, such
  as  `__ENV__/0`, as the first argument or as options. Both
  the module and all options will be automatically extracted
  from the environment:

      defmodule Foo do
        contents =
          quote do
            def sum(a, b), do: a + b
          end

        Module.eval_quoted(__ENV__, contents)
      end

      Foo.sum(1, 2)
      #=> 3

  Note that if you pass a `Macro.Env` struct as first argument
  while also passing `opts`, they will be merged with `opts`
  having precedence.

  """
  def eval_quoted(%Macro.Env{} = env, quoted, binding, opts) when is_list(binding) and is_list(opts) do
    eval_quoted(env.module(), quoted, binding, Keyword.merge(:maps.to_list(env), opts))
  end

  def eval_quoted(module, quoted, binding, %Macro.Env{} = env) when is_atom(module) and is_list(binding) do
    eval_quoted(module, quoted, binding, :maps.to_list(env))
  end

  def eval_quoted(module, quoted, binding, opts) when is_atom(module) and is_list(binding) and is_list(opts) do
    (
      assert_not_compiled!({:eval_quoted, 4}, module)
      :elixir_def.reset_last(module)
      {value, binding, _env} = :elixir.eval_quoted(quoted, binding, Keyword.put(opts, :module, module))
      {value, binding}
    )
  end

  def get_attribute(module, key) do
    super(module, key, nil)
  end

  @doc ~S"""
  Gets the given attribute from a module.

  If the attribute was marked with `accumulate` with
  `Module.register_attribute/3`, a list is always returned.
  `nil` is returned if the attribute has not been marked with
  `accumulate` and has not been set to any value.

  The `@` macro compiles to a call to this function. For example,
  the following code:

      @foo

  Expands to something akin to:

      Module.get_attribute(__MODULE__, :foo)

  This function can only be used on modules that have not yet been compiled.
  Use the `c:Module.__info__/1` callback to get all persisted attributes, or
  `Code.fetch_docs/1` to retrieve all documentation related attributes in
  compiled modules.

  ## Examples

      defmodule Foo do
        Module.put_attribute(__MODULE__, :value, 1)
        Module.get_attribute(__MODULE__, :value) #=> 1

        Module.get_attribute(__MODULE__, :value, :default) #=> 1
        Module.get_attribute(__MODULE__, :not_found, :default) #=> :default

        Module.register_attribute(__MODULE__, :value, accumulate: true)
        Module.put_attribute(__MODULE__, :value, 1)
        Module.get_attribute(__MODULE__, :value) #=> [1]
      end


  """
  def get_attribute(module, key, default) when is_atom(module) and is_atom(key) do
    case(__get_attribute__(module, key, nil)) do
      nil ->
        default
      value ->
        value
    end
  end

  @doc ~S"""
  Checks if the given attribute has been defined.

  An attribute is defined if it has been registered with `register_attribute/3`
  or assigned a value. If an attribute has been deleted with `delete_attribute/2`
  it is no longer considered defined.

  This function can only be used on modules that have not yet been compiled.

  ## Examples

      defmodule MyModule do
        @value 1
        Module.register_attribute(__MODULE__, :other_value)
        Module.put_attribute(__MODULE__, :another_value, 1)

        Module.has_attribute?(__MODULE__, :value) #=> true
        Module.has_attribute?(__MODULE__, :other_value) #=> true
        Module.has_attribute?(__MODULE__, :another_value) #=> true

        Module.has_attribute?(__MODULE__, :undefined) #=> false

        Module.delete_attribute(__MODULE__, :value)
        Module.has_attribute?(__MODULE__, :value) #=> false
      end


  """
  def has_attribute?(module, key) when is_atom(module) and is_atom(key) do
    (
      assert_not_compiled!({:has_attribute?, 2}, module)
      {set, _bag} = data_tables_for(module)
      :ets.member(set, key)
    )
  end

  @doc ~S"""
  Makes the given functions in `module` overridable.

  An overridable function is lazily defined, allowing a
  developer to customize it. See `Kernel.defoverridable/1` for
  more information and documentation.

  Once a function or a macro is marked as overridable, it will
  no longer be listed under `definitions_in/1` or return true
  when given to `defines?/2` until another implementation is
  given.

  """
  def make_overridable(module, tuples) when is_atom(module) and is_list(tuples) do
    (
      assert_not_readonly!({:make_overridable, 2}, module)
      func = fn
       {function_name, arity} = tuple when is_atom(function_name) and is_integer(arity) and arity >= 0 and arity <= 255 ->
          case(:elixir_def.take_definition(module, tuple)) do
            false ->
              raise(ArgumentError, <<"cannot make function "::binary(), String.Chars.to_string(function_name)::binary(), "/"::binary(), String.Chars.to_string(arity)::binary(), " "::binary(), "overridable because it was not defined"::binary()>>)
            clause ->
              neighbours = :elixir_locals.yank(tuple, module)
              :elixir_overridable.record_overridable(module, tuple, clause, neighbours)
          end
        other ->
          raise(ArgumentError, <<"each element in tuple list has to be a "::binary(), "{function_name :: atom, arity :: 0..255} tuple, got: "::binary(), Kernel.inspect(other)::binary()>>)
      end
      :lists.foreach(func, tuples)
    )
  end

  def make_overridable(module, behaviour) when is_atom(module) and is_atom(behaviour) do
    (
      case(check_module_for_overridable(module, behaviour)) do
        :ok ->
          :ok
        {:error, error_explanation} ->
          raise(ArgumentError, <<"cannot pass module "::binary(), Kernel.inspect(behaviour)::binary(), " as argument "::binary(), "to defoverridable/1 because "::binary(), String.Chars.to_string(error_explanation)::binary()>>)
      end
      behaviour_callbacks = for(callback <- behaviour_info(behaviour, :callbacks)) do
        {pair, _kind} = normalize_macro_or_function_callback(callback)
        pair
      end
      tuples = for(definition <- definitions_in(module), Enum.member?(behaviour_callbacks, definition)) do
        definition
      end
      make_overridable(module, tuples)
    )
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  @doc ~S"""
  Checks if a module is open.

  A module is "open" if it is currently being defined and its attributes and
  functions can be modified.

  """
  def open?(module) when is_atom(module) do
    :elixir_module.is_open(module)
  end

  @doc ~S"""
  Returns `true` if `tuple` in `module` is marked as overridable.

  """
  def overridable?(module, {function_name, arity} = tuple) when is_atom(function_name) and is_integer(arity) and arity >= 0 and arity <= 255 do
    :elixir_overridable.overridable_for(module, tuple) != :not_overridable
  end

  @doc ~S"""
  Puts a module attribute with `key` and `value` in the given `module`.

  ## Examples

      defmodule MyModule do
        Module.put_attribute(__MODULE__, :custom_threshold_for_lib, 10)
      end


  """
  def put_attribute(module, key, value) when is_atom(module) and is_atom(key) do
    __put_attribute__(module, key, value, nil)
  end

  @doc ~S"""
  Registers an attribute.

  By registering an attribute, a developer is able to customize
  how Elixir will store and accumulate the attribute values.

  ## Options

  When registering an attribute, two options can be given:

    * `:accumulate` - several calls to the same attribute will
      accumulate instead of overriding the previous one. New attributes
      are always added to the top of the accumulated list.

    * `:persist` - the attribute will be persisted in the Erlang
      Abstract Format. Useful when interfacing with Erlang libraries.

  By default, both options are `false`.

  ## Examples

      defmodule MyModule do
        Module.register_attribute(__MODULE__, :custom_threshold_for_lib, accumulate: true)

        @custom_threshold_for_lib 10
        @custom_threshold_for_lib 20
        @custom_threshold_for_lib #=> [20, 10]
      end


  """
  def register_attribute(module, attribute, options) when is_atom(module) and is_atom(attribute) and is_list(options) do
    (
      assert_not_readonly!({:register_attribute, 3}, module)
      {set, bag} = data_tables_for(module)
      if(Keyword.get(options, :persist)) do
        nil
      else
        :ets.insert(bag, {:persisted_attributes, attribute})
      end
      if(Keyword.get(options, :accumulate)) do
        :ets.insert_new(bag, {:warn_attributes, attribute})
        :ets.insert_new(set, {attribute, nil, :unset})
      else
        :ets.insert_new(set, {attribute, [], :accumulate}) || x
      end
      :ok
    )
  end

  @doc ~S"""
  Concatenates a list of aliases and returns a new alias only if the alias
  was already referenced.

  If the alias was not referenced yet, fails with `ArgumentError`.
  It handles charlists, binaries and atoms.

  ## Examples

      iex> Module.safe_concat([Module, Unknown])
      ** (ArgumentError) argument error

      iex> Module.safe_concat([List, Chars])
      List.Chars


  """
  def safe_concat(list) when is_list(list) do
    :elixir_aliases.safe_concat(list)
  end

  @doc ~S"""
  Concatenates two aliases and returns a new alias only if the alias was
  already referenced.

  If the alias was not referenced yet, fails with `ArgumentError`.
  It handles charlists, binaries and atoms.

  ## Examples

      iex> Module.safe_concat(Module, Unknown)
      ** (ArgumentError) argument error

      iex> Module.safe_concat(List, Chars)
      List.Chars


  """
  def safe_concat(left, right) when (is_binary(left) or is_atom(left)) and (is_binary(right) or is_atom(right)) do
    :elixir_aliases.safe_concat([left, right])
  end

  @doc ~S"""
  Copies the given spec as a callback.

  Returns `true` if there is such a spec and it was copied as a callback.
  If the function associated to the spec has documentation defined prior to
  invoking this function, the docs are copied too.

  """
  def spec_to_callback(module, definition) do
    Kernel.Typespec.spec_to_callback(module, definition)
  end

  @doc ~S"""
  Splits the given module name into binary parts.

  `module` has to be an Elixir module, as `split/1` won't work with Erlang-style
  modules (for example, `split(:lists)` raises an error).

  `split/1` also supports splitting the string representation of Elixir modules
  (that is, the result of calling `Atom.to_string/1` with the module name).

  ## Examples

      iex> Module.split(Very.Long.Module.Name.And.Even.Longer)
      ["Very", "Long", "Module", "Name", "And", "Even", "Longer"]
      iex> Module.split("Elixir.String.Chars")
      ["String", "Chars"]


  """
  def split(module) when is_atom(module) do
    split(Atom.to_string(module), _original = module)
  end

  def split(module) when is_binary(module) do
    split(module, _original = module)
  end

  # Private Functions

  defp unquote(:"-check_behaviours/2-fun-0-")(arg1, arg2, arg3, arg4, arg5) do
    # body not decompiled
  end

  defp unquote(:"-check_behaviours/2-fun-1-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-check_callbacks/3-fun-0-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp unquote(:"-check_impls/4-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-check_impls/4-fun-1-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-check_impls/4-fun-2-")(arg1, arg2, arg3, arg4, arg5) do
    # body not decompiled
  end

  defp unquote(:"-impl_behaviours/6-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-known_callbacks/1-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-make_overridable/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-make_overridable/2-fun-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-make_overridable/2-fun-2-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-simplify_arg/3-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-warn_missing_impls/4-fun-0-")(arg1, arg2, arg3, arg4, arg5) do
    # body not decompiled
  end

  defp add_callback(original, behaviour, env, optional_callbacks, acc) do
    (
      {callback, kind} = normalize_macro_or_function_callback(original)
      case(acc) do
        %{^callback => {_kind, conflict, _optional?}} ->
          message = case(conflict == behaviour) do
            false ->
              <<"conflicting behaviours found. "::binary(), String.Chars.to_string(format_definition(kind, callback))::binary(), " is required by "::binary(), Kernel.inspect(conflict)::binary(), " and "::binary(), Kernel.inspect(behaviour)::binary(), " (in module "::binary(), Kernel.inspect(env.module())::binary(), ")"::binary()>>
            true ->
              <<"the behavior "::binary(), Kernel.inspect(conflict)::binary(), " has been declared twice "::binary(), "(conflict in "::binary(), String.Chars.to_string(format_definition(kind, callback))::binary(), " in module "::binary(), Kernel.inspect(env.module())::binary(), ")"::binary()>>
          end
          IO.warn(message, Macro.Env.stacktrace(env))
        %{} ->
          :ok
      end
      :maps.put(callback, {kind, behaviour, Enum.member?(optional_callbacks, original)}, acc)
    )
  end

  defp add_defaults_count(doc_meta, 0) do
    doc_meta
  end

  defp add_defaults_count(doc_meta, n) do
    :maps.put(:defaults, n, doc_meta)
  end

  defp args_count([{:\\, _, _} | tail], total, defaults) do
    args_count(tail, total + 1, defaults + 1)
  end

  defp args_count([_head | tail], total, defaults) do
    args_count(tail, total + 1, defaults)
  end

  defp args_count([], total, defaults) do
    {total, defaults}
  end

  defp assert_not_compiled!(function_name_arity, module) do
    super(function_name_arity, module, "")
  end

  defp assert_not_compiled!(function_name_arity, module, extra_msg) do
    open?(module) || x
  end

  defp assert_not_compiled_message({function_name, arity}, module, extra_msg) do
    (
      mfa = <<"Module."::binary(), String.Chars.to_string(function_name)::binary(), "/"::binary(), String.Chars.to_string(arity)::binary()>>
      <<"could not call "::binary(), String.Chars.to_string(mfa)::binary(), " because the module "::binary(), Kernel.inspect(module)::binary(), " is already compiled"::binary(), case(extra_msg) do
        "" ->
          ""
        _ ->
          <<". "::binary(), extra_msg::binary()>>
      end::binary()>>
    )
  end

  defp assert_not_readonly!({function_name, arity}, module) do
    case(:elixir_module.mode(module)) do
      :all ->
        :ok
      :readonly ->
        raise(ArgumentError, <<"could not call Module."::binary(), String.Chars.to_string(function_name)::binary(), "/"::binary(), String.Chars.to_string(arity)::binary(), " because the module "::binary(), Kernel.inspect(module)::binary(), " is in read-only mode (@after_compile)"::binary()>>)
      :closed ->
        raise(ArgumentError, assert_not_compiled_message({function_name, arity}, module, ""))
    end
  end

  defp attribute_stack(module, line) do
    (
      file = String.to_charlist(Path.relative_to_cwd(:elixir_module.file(module)))
      [{module, :__MODULE__, 0, [file: file, line: line]}]
    )
  end

  defp autogenerated_key(counters, key) do
    case(counters) do
      %{^key => :once} ->
        {key, :maps.put(key, 2, counters)}
      %{^key => value} ->
        {key, :maps.put(key, value + 1, counters)}
      %{} ->
        {key, :maps.put(key, :once, counters)}
    end
  end

  defp bag_lookup_element(table, key, pos) do
    try() do
      :ets.lookup_element(table, key, pos)
    catch
      :error, :badarg ->
        []
    end
  end

  defp behaviour_callbacks_for_impls([], _behaviour, _callbacks) do
    []
  end

  defp behaviour_callbacks_for_impls([fa | tail], behaviour, callbacks) do
    case(callbacks[fa]) do
      {_, ^behaviour, _} ->
        [{fa, behaviour} | behaviour_callbacks_for_impls(tail, behaviour, callbacks)]
      _ ->
        behaviour_callbacks_for_impls(tail, behaviour, callbacks)
    end
  end

  defp behaviour_info(module, key) do
    case(module.behaviour_info(key)) do
      list when is_list(list) ->
        list
      :undefined ->
        []
    end
  end

  defp build_signature(args, env) do
    (
      {reverse_args, counters} = simplify_args(args, %{}, [], env)
      expand_keys(reverse_args, counters, [])
    )
  end

  defp callbacks_for_impls([], _) do
    []
  end

  defp callbacks_for_impls([fa | tail], callbacks) do
    case(callbacks[fa]) do
      {_, behaviour, _} ->
        [{fa, behaviour} | callbacks_for_impls(tail, callbacks)]
      nil ->
        callbacks_for_impls(tail, callbacks)
    end
  end

  defp check_behaviours(env, behaviours) do
    Enum.reduce(behaviours, %{}, fn behaviour, acc -> cond() do
      not(is_atom(behaviour)) ->
        message = <<"@behaviour "::binary(), Kernel.inspect(behaviour)::binary(), " must be an atom (in module "::binary(), Kernel.inspect(env.module())::binary(), ")"::binary()>>
        IO.warn(message, Macro.Env.stacktrace(env))
        acc
      Code.ensure_compiled(behaviour) != {:module, behaviour} ->
        message = <<"@behaviour "::binary(), Kernel.inspect(behaviour)::binary(), " does not exist (in module "::binary(), Kernel.inspect(env.module())::binary(), ")"::binary()>>
        IO.warn(message, Macro.Env.stacktrace(env))
        acc
      not(:erlang.function_exported(behaviour, :behaviour_info, 1)) ->
        message = <<"module "::binary(), Kernel.inspect(behaviour)::binary(), " is not a behaviour (in module "::binary(), Kernel.inspect(env.module())::binary(), ")"::binary()>>
        IO.warn(message, Macro.Env.stacktrace(env))
        acc
      true ->
        :elixir_env.trace({:require, [], behaviour, []}, env)
        optional_callbacks = behaviour_info(behaviour, :optional_callbacks)
        callbacks = behaviour_info(behaviour, :callbacks)
        Enum.reduce(callbacks, acc, fn x1, x2 -> add_callback(x1, behaviour, env, optional_callbacks, x2) end)
    end end)
  end

  defp check_callbacks(env, callbacks, all_definitions) do
    (
      for({callback, {kind, behaviour, optional?}} <- callbacks) do
        case(:lists.keyfind(callback, 1, all_definitions)) do
          false when not(optional?) ->
            message = <<format_callback(callback, kind, behaviour)::binary(), " is not implemented (in module "::binary(), Kernel.inspect(env.module())::binary(), ")"::binary()>>
            IO.warn(message, Macro.Env.stacktrace(env))
          {_, wrong_kind, _, _} when kind != wrong_kind ->
            message = <<format_callback(callback, kind, behaviour)::binary(), " was implemented as \""::binary(), String.Chars.to_string(wrong_kind)::binary(), "\" but should have been \""::binary(), String.Chars.to_string(kind)::binary(), "\" "::binary(), "(in module "::binary(), Kernel.inspect(env.module())::binary(), ")"::binary()>>
            IO.warn(message, Macro.Env.stacktrace(env))
          _ ->
            :ok
        end
      end
      :ok
    )
  end

  defp check_impls(env, behaviours, callbacks, impls) do
    (
      acc = {callbacks, %{}}
      Enum.reduce(impls, acc, fn {fa, context, defaults, kind, line, file, value}, acc -> case(impl_behaviours(fa, defaults, kind, value, behaviours, callbacks)) do
        {:ok, impl_behaviours} ->
          Enum.reduce(impl_behaviours, acc, fn {fa, behaviour}, {callbacks, contexts} ->
            callbacks = :maps.remove(fa, callbacks)
            contexts = Map.update(contexts, behaviour, [context], fn x1 -> [context | x1] end)
            {callbacks, contexts}
          end)
        {:error, message} ->
          formatted = format_impl_warning(fa, kind, message)
          IO.warn(formatted, Macro.Env.stacktrace(%{env | line: line, file: file}))
          acc
      end end)
    )
  end

  defp check_module_for_overridable(module, behaviour) do
    (
      {_, bag} = data_tables_for(module)
      behaviour_definitions = bag_lookup_element(bag, {:accumulate, :behaviour}, 2)
      cond() do
        Code.ensure_compiled(behaviour) != {:module, behaviour} ->
          {:error, "it was not defined"}
        not(:erlang.function_exported(behaviour, :behaviour_info, 1)) ->
          {:error, "it does not define any callbacks"}
        not(Enum.member?(behaviour_definitions, behaviour)) ->
          error_message = <<"its corresponding behaviour is missing. Did you forget to "::binary(), "add @behaviour "::binary(), Kernel.inspect(behaviour)::binary(), "?"::binary()>>
          {:error, error_message}
        true ->
          :ok
      end
    )
  end

  defp compile_deprecated(doc_meta, set, bag, name, arity, defaults) do
    case(:ets.take(set, :deprecated)) do
      [{:deprecated, reason, _}] when is_binary(reason) ->
        :ets.insert(bag, deprecated_reasons(defaults, name, arity, reason))
        :maps.put(:deprecated, reason, doc_meta)
      _ ->
        doc_meta
    end
  end

  defp compile_doc(_table, _ctx, line, kind, name, arity, _args, _body, doc, _meta, env, _impl) when kind === :defp or kind === :defmacrop do
    if(doc) do
      nil
    else
      message = <<String.Chars.to_string(kind)::binary(), " "::binary(), String.Chars.to_string(name)::binary(), "/"::binary(), String.Chars.to_string(arity)::binary(), " is private, "::binary(), "@doc attribute is always discarded for private functions/macros/types"::binary()>>
      IO.warn(message, Macro.Env.stacktrace(%{env | line: line}))
    end
  end

  defp compile_doc(table, ctx, line, kind, name, arity, args, body, doc, doc_meta, env, impl) do
    (
      key = {doc_key(kind), name, arity}
      signature = build_signature(args, env)
      case(:ets.lookup(table, key)) do
        [] ->
          doc = if(is_nil(doc) && impl) do
            doc
          else
            false
          end
          :ets.insert(table, {key, ctx, line, signature, doc, doc_meta})
        [{_, current_ctx, current_line, current_sign, current_doc, current_doc_meta}] ->
          case(is_binary(current_doc) and is_binary(doc) and body != nil and is_nil(current_ctx)) do
            false ->
              nil
            true ->
              message = <<"redefining @doc attribute previously set at line "::binary(), String.Chars.to_string(current_line)::binary(), ".\n\nPlease remove the duplicate docs. If instead you want to override a previously defined @doc, attach the @doc attribute to a function head:\n\n    @doc \"\"\"\n    new docs\n    \"\"\"\n    def "::binary(), String.Chars.to_string(name)::binary(), "(...)\n"::binary()>>
              IO.warn(message, Macro.Env.stacktrace(%{env | line: line}))
          end
          signature = merge_signatures(current_sign, signature, 1)
          doc = case(is_nil(doc)) do
            false ->
              doc
            true ->
              current_doc
          end
          doc = if(is_nil(doc) && impl) do
            doc
          else
            false
          end
          doc_meta = Map.merge(current_doc_meta, doc_meta)
          :ets.insert(table, {key, ctx, current_line, signature, doc, doc_meta})
      end
    )
  end

  defp compile_doc_meta(set, bag, name, arity, defaults) do
    (
      doc_meta = compile_deprecated(%{}, set, bag, name, arity, defaults)
      doc_meta = get_doc_meta(doc_meta, set)
      add_defaults_count(doc_meta, defaults)
    )
  end

  defp compile_impl(set, bag, context, name, env, kind, arity, defaults) do
    (
      %{line: line, file: file} = env
      case(:ets.take(set, :impl)) do
        [{:impl, value, _}] ->
          impl = {{name, arity}, context, defaults, kind, line, file, value}
          :ets.insert(bag, {:impls, impl})
          value
        [] ->
          false
      end
    )
  end

  defp data_tables_for(module) do
    :elixir_module.data_tables(module)
  end

  defp deprecated_reason(name, arity, reason) do
    {:deprecated, {{name, arity}, reason}}
  end

  defp deprecated_reasons(0, name, arity, reason) do
    [deprecated_reason(name, arity, reason)]
  end

  defp deprecated_reasons(defaults, name, arity, reason) do
    [deprecated_reason(name, arity - defaults, reason) | deprecated_reasons(defaults - 1, name, arity, reason)]
  end

  defp doc_key(:def) do
    :function
  end

  defp doc_key(:defmacro) do
    :macro
  end

  defp expand_key(key, counters) do
    case(counters) do
      %{^key => count} when is_integer(count) and count >= 1 ->
        {{String.to_atom(<<String.Chars.to_string(key)::binary(), String.Chars.to_string(count)::binary()>>), [], Elixir}, :maps.put(key, count - 1, counters)}
      _ ->
        {{key, [], Elixir}, counters}
    end
  end

  defp expand_keys([{:\\, meta, [key, default]} | keys], counters, acc) when is_atom(key) do
    (
      {var, counters} = expand_key(key, counters)
      expand_keys(keys, counters, [{:\\, meta, [var, default]} | acc])
    )
  end

  defp expand_keys([key | keys], counters, acc) when is_atom(key) do
    (
      {var, counters} = expand_key(key, counters)
      expand_keys(keys, counters, [var | acc])
    )
  end

  defp expand_keys([arg | args], counters, acc) do
    expand_keys(args, counters, [arg | acc])
  end

  defp expand_keys([], _counters, acc) do
    acc
  end

  defp format_callback(callback, kind, module) do
    (
      protocol_or_behaviour = if(protocol?(module)) do
        "behaviour "
      else
        "protocol "
      end
      <<format_definition(kind, callback)::binary(), " required by "::binary(), protocol_or_behaviour::binary(), Kernel.inspect(module)::binary()>>
    )
  end

  defp format_definition(:defmacro) do
    "macro"
  end

  defp format_definition(:defmacrop) do
    "macro"
  end

  defp format_definition(:def) do
    "function"
  end

  defp format_definition(:defp) do
    "function"
  end

  defp format_definition(kind, {name, arity}) do
    <<format_definition(kind)::binary(), " "::binary(), String.Chars.to_string(name)::binary(), "/"::binary(), String.Chars.to_string(arity)::binary()>>
  end

  defp format_impl_warning(fa, kind, :private_function) do
    <<String.Chars.to_string(format_definition(kind, fa))::binary(), " is private, @impl attribute is always discarded for private functions/macros"::binary()>>
  end

  defp format_impl_warning(fa, kind, {:no_behaviours, value}) do
    <<"got \"@impl "::binary(), Kernel.inspect(value)::binary(), "\" for "::binary(), String.Chars.to_string(format_definition(kind, fa))::binary(), " but no behaviour was declared"::binary()>>
  end

  defp format_impl_warning(_, kind, {:impl_not_defined, {fa, behaviour}}) do
    <<"got \"@impl false\" for "::binary(), String.Chars.to_string(format_definition(kind, fa))::binary(), " "::binary(), "but it is a callback specified in "::binary(), Kernel.inspect(behaviour)::binary()>>
  end

  defp format_impl_warning(fa, kind, {:impl_defined, callbacks}) do
    <<"got \"@impl true\" for "::binary(), String.Chars.to_string(format_definition(kind, fa))::binary(), " "::binary(), "but no behaviour specifies such callback"::binary(), String.Chars.to_string(known_callbacks(callbacks))::binary()>>
  end

  defp format_impl_warning(fa, kind, {:behaviour_not_declared, behaviour}) do
    <<"got \"@impl "::binary(), Kernel.inspect(behaviour)::binary(), "\" for "::binary(), String.Chars.to_string(format_definition(kind, fa))::binary(), " "::binary(), "but this behaviour was not declared with @behaviour"::binary()>>
  end

  defp format_impl_warning(fa, kind, {:behaviour_not_defined, behaviour, callbacks}) do
    <<"got \"@impl "::binary(), Kernel.inspect(behaviour)::binary(), "\" for "::binary(), String.Chars.to_string(format_definition(kind, fa))::binary(), " "::binary(), "but this behaviour does not specify such callback"::binary(), String.Chars.to_string(known_callbacks(callbacks))::binary()>>
  end

  defp get_doc_info(table, env) do
    case(:ets.take(table, :doc)) do
      [{:doc, {_, _} = pair, _}] ->
        pair
      [] ->
        {env.line(), nil}
    end
  end

  defp get_doc_meta(existing_meta, set) do
    case(:ets.take(set, {:doc, :meta})) do
      [{{:doc, :meta}, metadata, _}] ->
        Map.merge(existing_meta, metadata)
      [] ->
        existing_meta
    end
  end

  defp impl_behaviours(_, kind, _, _, _) when kind === :defp or kind === :defmacrop do
    {:error, :private_function}
  end

  defp impl_behaviours(_, _, value, [], _) do
    {:error, {:no_behaviours, value}}
  end

  defp impl_behaviours(impls, _, false, _, callbacks) do
    case(callbacks_for_impls(impls, callbacks)) do
      [] ->
        {:ok, []}
      [impl | _] ->
        {:error, {:impl_not_defined, impl}}
    end
  end

  defp impl_behaviours(impls, _, true, _, callbacks) do
    case(callbacks_for_impls(impls, callbacks)) do
      [] ->
        {:error, {:impl_defined, callbacks}}
      impls ->
        {:ok, impls}
    end
  end

  defp impl_behaviours(impls, _, behaviour, behaviours, callbacks) do
    (
      filtered = behaviour_callbacks_for_impls(impls, behaviour, callbacks)
      cond() do
        filtered != [] ->
          {:ok, filtered}
        not(Enum.member?(behaviours, behaviour)) ->
          {:error, {:behaviour_not_declared, behaviour}}
        true ->
          {:error, {:behaviour_not_defined, behaviour, callbacks}}
      end
    )
  end

  defp impl_behaviours({function, arity}, defaults, kind, value, behaviours, callbacks) do
    (
      impls = for(n <- Range.new(arity, arity - defaults)) do
        {function, n}
      end
      impl_behaviours(impls, kind, value, behaviours, callbacks)
    )
  end

  defp known_callbacks(callbacks) when map_size(callbacks) == 0 do
    <<". There are no known callbacks, please specify the proper @behaviour "::binary(), "and make sure it defines callbacks"::binary()>>
  end

  defp known_callbacks(callbacks) do
    (
      formatted_callbacks = for({{name, arity}, {kind, module, _}} <- callbacks) do
        <<"\n  * "::binary(), Exception.format_mfa(module, name, arity)::binary(), " ("::binary(), String.Chars.to_string(format_definition(kind))::binary(), ")"::binary()>>
      end
      <<". The known callbacks are:\n"::binary(), String.Chars.to_string(formatted_callbacks)::binary(), "\n"::binary()>>
    )
  end

  defp merge_signature({:\\, meta, [left, right]}, newer, i) do
    {:\\, meta, [merge_signature(left, newer, i), right]}
  end

  defp merge_signature(older, {:\\, _, [left, _]}, i) do
    merge_signature(older, left, i)
  end

  defp merge_signature({_, _, nil} = older, _newer, _) do
    older
  end

  defp merge_signature(_older, {_, _, nil} = newer, _) do
    newer
  end

  defp merge_signature({var, _, _} = older, {var, _, _}, _) do
    older
  end

  defp merge_signature({_, meta, _}, _newer, i) do
    {String.to_atom(<<"arg"::binary(), String.Chars.to_string(i)::binary()>>), meta, Elixir}
  end

  defp merge_signatures([h1 | t1], [h2 | t2], i) do
    [merge_signature(h1, h2, i) | merge_signatures(t1, t2, i + 1)]
  end

  defp merge_signatures([], [], _) do
    []
  end

  defp missing_impl_in_context?(meta, behaviour, contexts) do
    case(contexts) do
      %{^behaviour => known} ->
        Enum.member?(known, Keyword.get(meta, :context))
      %{} ->
        not(Keyword.has_key?(meta, :context))
    end
  end

  defp normalize_macro_or_function_callback({function_name, arity}) do
    case(Atom.to_charlist(function_name)) do
      [77, 65, 67, 82, 79, 45 | tail] ->
        {{:erlang.list_to_atom(tail), arity - 1}, :defmacro}
      _ ->
        {{function_name, arity}, :def}
    end
  end

  defp preprocess_attribute(key, value) when key === :moduledoc or key === :typedoc or key === :doc do
    case(value) do
      {line, doc} when is_integer(line) and (is_binary(doc) or doc == false or is_nil(doc)) ->
        value
      {line, [{key, _} | _]} when is_integer(line) and is_atom(key) ->
        value
      {line, doc} when is_integer(line) ->
        raise(ArgumentError, <<"@"::binary(), String.Chars.to_string(key)::binary(), " is a built-in module attribute for documentation. It should be either "::binary(), "false, nil, a string, or a keyword list, got: "::binary(), Kernel.inspect(doc)::binary()>>)
      _other ->
        raise(ArgumentError, <<"@"::binary(), String.Chars.to_string(key)::binary(), " is a built-in module attribute for documentation. When set dynamically, "::binary(), "it should be {line, doc} (where \"doc\" is either false, nil, a string, or a keyword list), "::binary(), "got: "::binary(), Kernel.inspect(value)::binary()>>)
    end
  end

  defp preprocess_attribute(:on_load, value) do
    case(value) do
      _ when is_atom(value) ->
        {value, 0}
      {atom, 0} = tuple when is_atom(atom) ->
        tuple
      _ ->
        raise(ArgumentError, <<"@on_load is a built-in module attribute that annotates a function to be invoked "::binary(), "when the module is loaded. It should be an atom or a {atom, 0} tuple, "::binary(), "got: "::binary(), Kernel.inspect(value)::binary()>>)
    end
  end

  defp preprocess_attribute(:impl, value) do
    case(value) do
      _ when :erlang.is_boolean(value) ->
        value
      module when is_atom(module) and module != nil ->
        _ = Code.ensure_compiled(module)
        value
      _ ->
        raise(ArgumentError, <<"@impl is a built-in module attribute that marks the next definition "::binary(), "as a callback implementation. It should be a module or a boolean, "::binary(), "got: "::binary(), Kernel.inspect(value)::binary()>>)
    end
  end

  defp preprocess_attribute(:before_compile, atom) when is_atom(atom) do
    {atom, :__before_compile__}
  end

  defp preprocess_attribute(:after_compile, atom) when is_atom(atom) do
    {atom, :__after_compile__}
  end

  defp preprocess_attribute(:on_definition, atom) when is_atom(atom) do
    {atom, :__on_definition__}
  end

  defp preprocess_attribute(key, _value) when key === :type or key === :typep or key === :opaque or key === :spec or key === :callback or key === :macrocallback do
    raise(ArgumentError, <<"attributes type, typep, opaque, spec, callback, and macrocallback "::binary(), "must be set directly via the @ notation"::binary()>>)
  end

  defp preprocess_attribute(:external_resource, value) when not(is_binary(value)) do
    raise(ArgumentError, <<"@external_resource is a built-in module attribute used for specifying file "::binary(), "dependencies. It should be a string the path to a file, got: "::binary(), Kernel.inspect(value)::binary()>>)
  end

  defp preprocess_attribute(:deprecated, value) when not(is_binary(value)) do
    raise(ArgumentError, <<"@deprecated is a built-in module attribute that annotates a definition as deprecated. "::binary(), "It should be a string with the reason for the deprecation, got: "::binary(), Kernel.inspect(value)::binary()>>)
  end

  defp preprocess_attribute(:file, value) do
    case(value) do
      _ when is_binary(value) ->
        value
      {file, line} when is_binary(file) and is_integer(line) ->
        value
      _ ->
        raise(ArgumentError, <<"@file is a built-in module attribute that annotates the file and line the next "::binary(), "definition comes from. It should be a string or {string, line} tuple as value, "::binary(), "got: "::binary(), Kernel.inspect(value)::binary()>>)
    end
  end

  defp preprocess_attribute(_key, value) do
    value
  end

  defp preprocess_doc_meta([], _module, _line, map) do
    map
  end

  defp preprocess_doc_meta([{key, _} | tail], module, line, map) when key === :opaque or key === :defaults do
    (
      message = <<"ignoring reserved documentation metadata key: "::binary(), Kernel.inspect(key)::binary()>>
      IO.warn(message, attribute_stack(module, line))
      preprocess_doc_meta(tail, module, line, map)
    )
  end

  defp preprocess_doc_meta([{key, value} | tail], module, line, map) when is_atom(key) do
    (
      validate_doc_meta(key, value)
      preprocess_doc_meta(tail, module, line, :maps.put(key, value, map))
    )
  end

  defp protocol?(module) do
    Code.ensure_loaded?(module) and :erlang.function_exported(module, :__protocol__, 1) and module.__protocol__(:module) == module
  end

  defp put_attribute(module, key, {_, metadata}, line, set, _bag) when (key === :doc or key === :typedoc or key === :moduledoc) and is_list(metadata) do
    (
      metadata_map = preprocess_doc_meta(metadata, module, line, %{})
      case(:ets.insert_new(set, {{key, :meta}, metadata_map, line})) do
        true ->
          :ok
        false ->
          current_metadata = :ets.lookup_element(set, {key, :meta}, 2)
          :ets.update_element(set, {key, :meta}, {2, Map.merge(current_metadata, metadata_map)})
      end
    )
  end

  defp put_attribute(module, key, value, line, set, _bag) when key === :doc or key === :typedoc or key === :moduledoc or key === :impl or key === :deprecated do
    (
      try() do
        :ets.lookup_element(set, key, 3)
      catch
        :error, :badarg ->
          :ok
      else
        unread_line when is_integer(line) and is_integer(unread_line) ->
          message = <<"redefining @"::binary(), String.Chars.to_string(key)::binary(), " attribute previously set at line "::binary(), String.Chars.to_string(unread_line)::binary()>>
          IO.warn(message, attribute_stack(module, line))
        _ ->
          :ok
      end
      :ets.insert(set, {key, value, line})
    )
  end

  defp put_attribute(_module, :on_load, value, line, set, bag) do
    try() do
      :ets.lookup_element(set, :on_load, 3)
    catch
      :error, :badarg ->
        :ets.insert(set, {:on_load, value, line})
        :ets.insert(bag, {:warn_attributes, :on_load})
    else
      _ ->
        raise(ArgumentError, "the @on_load attribute can only be set once per module")
    end
  end

  defp put_attribute(_module, key, value, line, set, bag) do
    try() do
      :ets.lookup_element(set, key, 3)
    catch
      :error, :badarg ->
        :ets.insert(set, {key, value, line})
        :ets.insert(bag, {:warn_attributes, key})
    else
      :accumulate ->
        :ets.insert(bag, {{:accumulate, key}, value})
      _ ->
        :ets.insert(set, {key, value, line})
    end
  end

  defp reverse_values([{_, value} | tail], acc) do
    reverse_values(tail, [value | acc])
  end

  defp reverse_values([], acc) do
    acc
  end

  defp simplify_arg({:\\, _, [left, right]}, counters, env) do
    (
      {left, counters} = simplify_arg(left, counters, env)
      right = Macro.prewalk(right, fn
       {:@, _, _} = attr ->
          Macro.expand_once(attr, env)
        other ->
          other
      end)
      {{:\\, [], [left, right]}, counters}
    )
  end

  defp simplify_arg({:=, _, [{var, _, atom}, _]}, counters, _env) when is_atom(atom) do
    {simplify_var(var, nil), counters}
  end

  defp simplify_arg({:=, _, [_, {var, _, atom}]}, counters, _env) when is_atom(atom) do
    {simplify_var(var, nil), counters}
  end

  defp simplify_arg({var, _, atom}, counters, _env) when is_atom(atom) do
    {simplify_var(var, Elixir), counters}
  end

  defp simplify_arg({:%, _, [left, _]}, counters, env) do
    case(Macro.expand_once(left, env)) do
      module when is_atom(module) ->
        autogenerated_key(counters, simplify_module_name(module))
      _ ->
        autogenerated_key(counters, :struct)
    end
  end

  defp simplify_arg({:%{}, _, _}, counters, _env) do
    autogenerated_key(counters, :map)
  end

  defp simplify_arg({:@, _, _} = attr, counters, env) do
    simplify_arg(Macro.expand_once(attr, env), counters, env)
  end

  defp simplify_arg(other, counters, _env) when is_integer(other) do
    autogenerated_key(counters, :int)
  end

  defp simplify_arg(other, counters, _env) when :erlang.is_boolean(other) do
    autogenerated_key(counters, :bool)
  end

  defp simplify_arg(other, counters, _env) when is_atom(other) do
    autogenerated_key(counters, :atom)
  end

  defp simplify_arg(other, counters, _env) when is_list(other) do
    autogenerated_key(counters, :list)
  end

  defp simplify_arg(other, counters, _env) when :erlang.is_float(other) do
    autogenerated_key(counters, :float)
  end

  defp simplify_arg(other, counters, _env) when is_binary(other) do
    autogenerated_key(counters, :binary)
  end

  defp simplify_arg(_, counters, _env) do
    autogenerated_key(counters, :arg)
  end

  defp simplify_args([arg | args], counters, acc, env) do
    (
      {arg, counters} = simplify_arg(arg, counters, env)
      simplify_args(args, counters, [arg | acc], env)
    )
  end

  defp simplify_args([], counters, reverse_args, _env) do
    {reverse_args, counters}
  end

  defp simplify_module_name(module) when is_atom(module) do
    try() do
      split(module)
    rescue
      _ in [ArgumentError] ->
        module
    else
      module_name ->
        String.to_atom(Macro.underscore(List.last(module_name)))
    end
  end

  defp simplify_var(var, guess_priority) do
    case(Atom.to_string(var)) do
      "_" ->
        {:_, [], guess_priority}
      <<"_"::binary(), rest::binary()>> ->
        {String.to_atom(rest), [], guess_priority}
      _ ->
        {var, [], nil}
    end
  end

  defp split(<<"Elixir."::binary(), name::binary()>>, _original) do
    String.split(name, ".")
  end

  defp split(_module, original) do
    raise(ArgumentError, <<"expected an Elixir module, got: "::binary(), Kernel.inspect(original)::binary()>>)
  end

  defp validate_doc_meta(:since, value) when not(is_binary(value)) do
    raise(ArgumentError, <<":since is a built-in documentation metadata key. It should be a string representing "::binary(), "the version in which the documented entity was added, got: "::binary(), Kernel.inspect(value)::binary()>>)
  end

  defp validate_doc_meta(:deprecated, value) when not(is_binary(value)) do
    raise(ArgumentError, <<":deprecated is a built-in documentation metadata key. It should be a string "::binary(), "representing the replacement for the deprecated entity, got: "::binary(), Kernel.inspect(value)::binary()>>)
  end

  defp validate_doc_meta(:delegate_to, value) do
    case(value) do
      {m, f, a} when is_atom(m) and is_atom(f) and is_integer(a) and a >= 0 ->
        :ok
      _ ->
        raise(ArgumentError, <<":delegate_to is a built-in documentation metadata key. It should be a three-element "::binary(), "tuple in the form of {module, function, arity}, got: "::binary(), Kernel.inspect(value)::binary()>>)
    end
  end

  defp validate_doc_meta(_, _) do
    :ok
  end

  defp warn_missing_impls(_env, callbacks, _contexts, _defs) when map_size(callbacks) == 0 do
    :ok
  end

  defp warn_missing_impls(env, non_implemented_callbacks, contexts, defs) do
    (
      for({pair, kind, meta, _clauses} <- defs, kind === :def or kind === :defmacro) do
        with({:ok, {_, behaviour, _}} <- :maps.find(pair, non_implemented_callbacks), true <- missing_impl_in_context?(meta, behaviour, contexts)) do
          message = <<"module attribute @impl was not set for "::binary(), String.Chars.to_string(format_definition(kind, pair))::binary(), " "::binary(), "callback (specified in "::binary(), Kernel.inspect(behaviour)::binary(), "). "::binary(), "This either means you forgot to add the \"@impl true\" annotation before the "::binary(), "definition or that you are accidentally overriding this callback"::binary()>>
          IO.warn(message, Macro.Env.stacktrace(%{env | line: :elixir_utils.get_line(meta)}))
        end
      end
      :ok
    )
  end
end
