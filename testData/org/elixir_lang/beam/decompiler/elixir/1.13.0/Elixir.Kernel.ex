# Source code recreated from a .beam file by IntelliJ Elixir
defmodule Kernel do
  @moduledoc ~S"""
  `Kernel` is Elixir's default environment.

  It mainly consists of:

    * basic language primitives, such as arithmetic operators, spawning of processes,
      data type handling, and others
    * macros for control-flow and defining new functionality (modules, functions, and the like)
    * guard checks for augmenting pattern matching

  You can invoke `Kernel` functions and macros anywhere in Elixir code
  without the use of the `Kernel.` prefix since they have all been
  automatically imported. For example, in IEx, you can call:

      iex> is_number(13)
      true

  If you don't want to import a function or macro from `Kernel`, use the `:except`
  option and then list the function/macro by arity:

      import Kernel, except: [if: 2, unless: 2]

  See `Kernel.SpecialForms.import/2` for more information on importing.

  Elixir also has special forms that are always imported and
  cannot be skipped. These are described in `Kernel.SpecialForms`.

  ## The standard library

  `Kernel` provides the basic capabilities the Elixir standard library
  is built on top of. It is recommended to explore the standard library
  for advanced functionality. Here are the main groups of modules in the
  standard library (this list is not a complete reference, see the
  documentation sidebar for all entries).

  ### Built-in types

  The following modules handle Elixir built-in data types:

    * `Atom` - literal constants with a name (`true`, `false`, and `nil` are atoms)
    * `Float` - numbers with floating point precision
    * `Function` - a reference to code chunk, created with the `fn/1` special form
    * `Integer` - whole numbers (not fractions)
    * `List` - collections of a variable number of elements (linked lists)
    * `Map` - collections of key-value pairs
    * `Process` - light-weight threads of execution
    * `Port` - mechanisms to interact with the external world
    * `Tuple` - collections of a fixed number of elements

  There are two data types without an accompanying module:

    * Bitstring - a sequence of bits, created with `Kernel.SpecialForms.<<>>/1`.
      When the number of bits is divisible by 8, they are called binaries and can
      be manipulated with Erlang's `:binary` module
    * Reference - a unique value in the runtime system, created with `make_ref/0`

  ### Data types

  Elixir also provides other data types that are built on top of the types
  listed above. Some of them are:

    * `Date` - `year-month-day` structs in a given calendar
    * `DateTime` - date and time with time zone in a given calendar
    * `Exception` - data raised from errors and unexpected scenarios
    * `MapSet` - unordered collections of unique elements
    * `NaiveDateTime` - date and time without time zone in a given calendar
    * `Keyword` - lists of two-element tuples, often representing optional values
    * `Range` - inclusive ranges between two integers
    * `Regex` - regular expressions
    * `String` - UTF-8 encoded binaries representing characters
    * `Time` - `hour:minute:second` structs in a given calendar
    * `URI` - representation of URIs that identify resources
    * `Version` - representation of versions and requirements

  ### System modules

  Modules that interface with the underlying system, such as:

    * `IO` - handles input and output
    * `File` - interacts with the underlying file system
    * `Path` - manipulates file system paths
    * `System` - reads and writes system information

  ### Protocols

  Protocols add polymorphic dispatch to Elixir. They are contracts
  implementable by data types. See `Protocol` for more information on
  protocols. Elixir provides the following protocols in the standard library:

    * `Collectable` - collects data into a data type
    * `Enumerable` - handles collections in Elixir. The `Enum` module
      provides eager functions for working with collections, the `Stream`
      module provides lazy functions
    * `Inspect` - converts data types into their programming language
      representation
    * `List.Chars` - converts data types to their outside world
      representation as charlists (non-programming based)
    * `String.Chars` - converts data types to their outside world
      representation as strings (non-programming based)

  ### Process-based and application-centric functionality

  The following modules build on top of processes to provide concurrency,
  fault-tolerance, and more.

    * `Agent` - a process that encapsulates mutable state
    * `Application` - functions for starting, stopping and configuring
      applications
    * `GenServer` - a generic client-server API
    * `Registry` - a key-value process-based storage
    * `Supervisor` - a process that is responsible for starting,
      supervising and shutting down other processes
    * `Task` - a process that performs computations
    * `Task.Supervisor` - a supervisor for managing tasks exclusively

  ### Supporting documents

  Elixir documentation also includes supporting documents under the
  "Pages" section. Those are:

    * [Compatibility and deprecations](compatibility-and-deprecations.md) - lists
      compatibility between every Elixir version and Erlang/OTP, release schema;
      lists all deprecated functions, when they were deprecated and alternatives
    * [Library guidelines](library-guidelines.md) - general guidelines, anti-patterns,
      and rules for those writing libraries
    * [Naming conventions](naming-conventions.md) - naming conventions for Elixir code
    * [Operators](operators.md) - lists all Elixir operators and their precedences
    * [Patterns and guards](patterns-and-guards.md) - an introduction to patterns,
      guards, and extensions
    * [Syntax reference](syntax-reference.md) - the language syntax reference
    * [Typespecs](typespecs.md)- types and function specifications, including list of types
    * [Unicode syntax](unicode-syntax.md) - outlines Elixir support for Unicode
    * [Writing documentation](writing-documentation.md) - guidelines for writing
      documentation in Elixir

  ## Guards

  This module includes the built-in guards used by Elixir developers.
  They are a predefined set of functions and macros that augment pattern
  matching, typically invoked after the `when` operator. For example:

      def drive(%User{age: age}) when age >= 16 do
        ...
      end

  The clause above will only be invoked if the user's age is more than
  or equal to 16. Guards also support joining multiple conditions with
  `and` and `or`. The whole guard is true if all guard expressions will
  evaluate to `true`. A more complete introduction to guards is available
  in the [Patterns and guards](patterns-and-guards.md) page.

  ## Structural comparison

  The comparison functions in this module perform structural comparison.
  This means structures are compared based on their representation and
  not on their semantic value. This is specially important for functions
  that are meant to provide ordering, such as `>/2`, `</2`, `>=/2`,
  `<=/2`, `min/2`, and `max/2`. For example:

      ~D[2017-03-31] > ~D[2017-04-01]

  will return `true` because structural comparison compares the `:day`
  field before `:month` or `:year`. Therefore, when comparing structs,
  you often use the `compare/2` function made available by the structs
  modules themselves:

      iex> Date.compare(~D[2017-03-31], ~D[2017-04-01])
      :lt

  Alternatively, you can use the functions in the `Enum` module to
  sort or compute a maximum/minimum:

      iex> Enum.sort([~D[2017-03-31], ~D[2017-04-01]], Date)
      [~D[2017-03-31], ~D[2017-04-01]]
      iex> Enum.max([~D[2017-03-31], ~D[2017-04-01]], Date)
      ~D[2017-04-01]

  ## Truthy and falsy values

  Besides the booleans `true` and `false`, Elixir has the
  concept of a "truthy" or "falsy" value.

    *  a value is truthy when it is neither `false` nor `nil`
    *  a value is falsy when it is either `false` or `nil`

  Elixir has functions, like `and/2`, that *only* work with
  booleans, but also functions that work with these
  truthy/falsy values, like `&&/2` and `!/1`.

  ### Examples

  We can check the truthiness of a value by using the `!/1`
  function twice.

  Truthy values:

      iex> !!true
      true
      iex> !!5
      true
      iex> !![1,2]
      true
      iex> !!"foo"
      true

  Falsy values (of which there are exactly two):

      iex> !!false
      false
      iex> !!nil
      false

  ## Inlining

  Some of the functions described in this module are inlined by
  the Elixir compiler into their Erlang counterparts in the
  [`:erlang`](`:erlang`) module.
  Those functions are called BIFs (built-in internal functions)
  in Erlang-land and they exhibit interesting properties, as some
  of them are allowed in guards and others are used for compiler
  optimizations.

  Most of the inlined functions can be seen in effect when
  capturing the function:

      iex> &Kernel.is_atom/1
      &:erlang.is_atom/1

  Those functions will be explicitly marked in their docs as
  "inlined by the compiler".

  """

  # Macros

  @doc ~S"""
  Boolean "not" operator.

  Receives any value (not just booleans) and returns `true` if `value`
  is `false` or `nil`; returns `false` otherwise.

  Not allowed in guard clauses.

  ## Examples

      iex> !Enum.empty?([])
      false

      iex> !List.first([])
      true


  """
  defmacro unquote(:!)({:!, _, [value]}) do
    (
      assert_no_match_or_guard_scope(__CALLER__.context(), "!")
      optimize_boolean({:case, [], [value, [do: [{:->, [], [[{:when, [], [{:x, [], Kernel}, {{:., [], [Kernel, :in]}, [], [{:x, [], Kernel}, [false, nil]]}]}], false]}, {:->, [], [[{:_, [], Kernel}], true]}]]]})
    )
  end

  defmacro unquote(:!)(value) do
    (
      assert_no_match_or_guard_scope(__CALLER__.context(), "!")
      optimize_boolean({:case, [], [value, [do: [{:->, [], [[{:when, [], [{:x, [], Kernel}, {{:., [], [Kernel, :in]}, [], [{:x, [], Kernel}, [false, nil]]}]}], true]}, {:->, [], [[{:_, [], Kernel}], false]}]]]})
    )
  end

  @doc ~S"""
  Boolean "and" operator.

  Provides a short-circuit operator that evaluates and returns
  the second expression only if the first one evaluates to a truthy value
  (neither `false` nor `nil`). Returns the first expression
  otherwise.

  Not allowed in guard clauses.

  ## Examples

      iex> Enum.empty?([]) && Enum.empty?([])
      true

      iex> List.first([]) && true
      nil

      iex> Enum.empty?([]) && List.first([1])
      1

      iex> false && throw(:bad)
      false

  Note that, unlike `and/2`, this operator accepts any expression
  as the first argument, not only booleans.

  """
  defmacro left && right do
    (
      assert_no_match_or_guard_scope(__CALLER__.context(), "&&")
      {:case, [], [left, [do: [{:->, [], [[{:when, [], [{:x, [], Kernel}, {{:., [], [Kernel, :in]}, [], [{:x, [], Kernel}, [false, nil]]}]}], {:x, [], Kernel}]}, {:->, [], [[{:_, [], Kernel}], right]}]]]}
    )
  end

  @doc ~S"""
  Creates a range from `first` to `last`.

  If first is less than last, the range will be increasing from
  first to last. If first is equal to last, the range will contain
  one element, which is the number itself.

  If first is more than last, the range will be decreasing from first
  to last, albeit this behaviour is deprecated. Instead prefer to
  explicitly list the step with `first..last//-1`.

  See the `Range` module for more information.

  ## Examples

      iex> 0 in 1..3
      false
      iex> 2 in 1..3
      true

      iex> Enum.to_list(1..3)
      [1, 2, 3]


  """
  defmacro first .. last do
    case(bootstrapped?(Macro)) do
      true ->
        first = Macro.expand(first, __CALLER__)
        last = Macro.expand(last, __CALLER__)
        validate_range!(first, last)
        range(__CALLER__.context(), first, last)
      false ->
        range(__CALLER__.context(), first, last)
    end
  end

  @doc ~S"""
  Creates a range from `first` to `last` with `step`.

  See the `Range` module for more information.

  ## Examples

      iex> 0 in 1..3//1
      false
      iex> 2 in 1..3//1
      true
      iex> 2 in 1..3//2
      false

      iex> Enum.to_list(1..3//1)
      [1, 2, 3]
      iex> Enum.to_list(1..3//2)
      [1, 3]
      iex> Enum.to_list(3..1//-1)
      [3, 2, 1]
      iex> Enum.to_list(1..0//1)
      []


  """
  defmacro unquote(:..//)(first, last, step) do
    case(bootstrapped?(Macro)) do
      true ->
        first = Macro.expand(first, __CALLER__)
        last = Macro.expand(last, __CALLER__)
        step = Macro.expand(step, __CALLER__)
        validate_range!(first, last)
        validate_step!(step)
        range(__CALLER__.context(), first, last, step)
      false ->
        range(__CALLER__.context(), first, last, step)
    end
  end

  @doc ~S"""
  Binary concatenation operator. Concatenates two binaries.

  Raises an `ArgumentError` if one of the sides aren't binaries.

  ## Examples

      iex> "foo" <> "bar"
      "foobar"

  The `<>/2` operator can also be used in pattern matching (and guard clauses) as
  long as the left argument is a literal binary:

      iex> "foo" <> x = "foobar"
      iex> x
      "bar"

  `x <> "bar" = "foobar"` would result in an `ArgumentError` exception.


  """
  defmacro left <> right do
    (
      concats = extract_concatenations({:<>, [], [left, right]}, __CALLER__)
      {:<<>>, [], :elixir_quote.list(concats, [])}
    )
  end

  @doc ~S"""
  Module attribute unary operator.

  Reads and writes attributes in the current module.

  The canonical example for attributes is annotating that a module
  implements an OTP behaviour, such as `GenServer`:

      defmodule MyServer do
        @behaviour GenServer
        # ... callbacks ...
      end

  By default Elixir supports all the module attributes supported by Erlang, but
  custom attributes can be used as well:

      defmodule MyServer do
        @my_data 13
        IO.inspect(@my_data)
        #=> 13
      end

  Unlike Erlang, such attributes are not stored in the module by default since
  it is common in Elixir to use custom attributes to store temporary data that
  will be available at compile-time. Custom attributes may be configured to
  behave closer to Erlang by using `Module.register_attribute/3`.

  > **Important:** Libraries and frameworks should consider prefixing any
  > module attributes that are private by underscore, such as `@_my_data`
  > so code completion tools do not show them on suggestions and prompts.

  Finally, note that attributes can also be read inside functions:

      defmodule MyServer do
        @my_data 11
        def first_data, do: @my_data
        @my_data 13
        def second_data, do: @my_data
      end

      MyServer.first_data()
      #=> 11

      MyServer.second_data()
      #=> 13

  It is important to note that reading an attribute takes a snapshot of
  its current value. In other words, the value is read at compilation
  time and not at runtime. Check the `Module` module for other functions
  to manipulate module attributes.

  ## Attention! Multiple references of the same attribute

  As mentioned above, every time you read a module attribute, a snapshot
  of its current value is taken. Therefore, if you are storing large
  values inside module attributes (for example, embedding external files
  in module attributes), you should avoid referencing the same attribute
  multiple times. For example, don't do this:

      @files %{
        example1: File.read!("lib/example1.data"),
        example2: File.read!("lib/example2.data")
      }

      def example1, do: @files[:example1]
      def example2, do: @files[:example2]

  In the above, each reference to `@files` may end-up with a complete
  and individual copy of the whole `@files` module attribute. Instead,
  reference the module attribute once in a private function:

      @files %{
        example1: File.read!("lib/example1.data"),
        example2: File.read!("lib/example2.data")
      }

      defp files(), do: @files
      def example1, do: files()[:example1]
      def example2, do: files()[:example2]

  ## Attention! Compile-time dependencies

  Keep in mind references to other modules, even in module attributes,
  generate compile-time dependencies to said modules.

  For example, take this common pattern:

      @values [:foo, :bar, :baz]

      def handle_arg(arg) when arg in @values do
        ...
      end

  While the above is fine, imagine if instead you have actual
  module names in the module attribute, like this:

      @values [Foo, Bar, Baz]

      def handle_arg(arg) when arg in @values do
        ...
      end

  The code above will define a compile-time dependency on the modules
  `Foo`, `Bar`, and `Baz`, in a way that, if any of them change, the
  current module will have to recompile. In such cases, it may be
  preferred to avoid the module attribute altogether:

      def handle_arg(arg) when arg in [Foo, Bar, Baz] do
        ...
      end


  """
  defmacro unquote(:@)({:__aliases__, _meta, _args}) do
    raise(ArgumentError, "module attributes set via @ cannot start with an uppercase letter")
  end

  defmacro unquote(:@)({name, meta, args}) do
    (
      assert_module_scope(__CALLER__, :@, 1)
      function? = __CALLER__.function() != nil
      cond() do
        not(bootstrapped?(Macro)) ->
          nil
        (not(function?) and __CALLER__.context() == :match )->
          raise(ArgumentError, "invalid write attribute syntax. If you want to define an attribute, don't do this:\n\n    @foo = :value\n\nInstead, do this:\n\n    @foo :value\n")
        (is_list(args) and typespec?(name) )->
          case(bootstrapped?(Kernel.Typespec)) do
            false ->
              :ok
            true ->
              pos = :elixir_locals.cache_env(__CALLER__)
              %{line: line, file: file, module: module} = __CALLER__
              {{:., [], [{:__aliases__, [alias: false], [:Kernel, :Typespec]}, :deftypespec]}, [], [name, Macro.escape(:erlang.hd(args), unquote: true), line, file, module, pos]}
          end
        true ->
          do_at(args, meta, name, function?, __CALLER__)
      end
    )
  end

  @doc ~S"""
  When used inside quoting, marks that the given alias should not
  be hygienized. This means the alias will be expanded when
  the macro is expanded.

  Check `Kernel.SpecialForms.quote/2` for more information.

  """
  defmacro alias!(alias) when is_atom(alias) do
    alias
  end

  defmacro alias!({:__aliases__, meta, args}) do
    {:__aliases__, :lists.keydelete(:alias, 1, meta), args}
  end

  @doc ~S"""
  Strictly boolean "and" operator.

  If `left` is `false`, returns `false`; otherwise returns `right`.

  Requires only the `left` operand to be a boolean since it short-circuits. If
  the `left` operand is not a boolean, a `BadBooleanError` exception is raised.

  Allowed in guard tests.

  ## Examples

      iex> true and false
      false

      iex> true and "yay!"
      "yay!"

      iex> "yay!" and true
      ** (BadBooleanError) expected a boolean on left-side of "and", got: "yay!"


  """
  defmacro left and right do
    case(__CALLER__.context()) do
      nil ->
        build_boolean_check(:and, left, right, false)
      :match ->
        invalid_match!(:and)
      :guard ->
        {{:., [], [:erlang, :andalso]}, [], [left, right]}
    end
  end

  defmacro binding() do
    super
  end

  @doc ~S"""
  Returns the binding for the given context as a keyword list.

  In the returned result, keys are variable names and values are the
  corresponding variable values.

  If the given `context` is `nil` (by default it is), the binding for the
  current context is returned.

  ## Examples

      iex> x = 1
      iex> binding()
      [x: 1]
      iex> x = 2
      iex> binding()
      [x: 2]

      iex> binding(:foo)
      []
      iex> var!(x, :foo) = 1
      1
      iex> binding(:foo)
      [x: 1]


  """
  defmacro binding(context) do
    (
      in_match? = Macro.Env.in_match?(__CALLER__)
      bindings = for({v, c} <- Macro.Env.vars(__CALLER__), c == context) do
        {v, wrap_binding(in_match?, {v, [generated: true], c})}
      end
      :lists.sort(bindings)
    )
  end

  defmacro def(call) do
    super(call, nil)
  end

  @doc ~S"""
  Defines a public function with the given name and body.

  ## Examples

      defmodule Foo do
        def bar, do: :baz
      end

      Foo.bar()
      #=> :baz

  A function that expects arguments can be defined as follows:

      defmodule Foo do
        def sum(a, b) do
          a + b
        end
      end

  In the example above, a `sum/2` function is defined; this function receives
  two arguments and returns their sum.

  ## Default arguments

  `\\` is used to specify a default value for a parameter of a function. For
  example:

      defmodule MyMath do
        def multiply_by(number, factor \\ 2) do
          number * factor
        end
      end

      MyMath.multiply_by(4, 3)
      #=> 12

      MyMath.multiply_by(4)
      #=> 8

  The compiler translates this into multiple functions with different arities,
  here `MyMath.multiply_by/1` and `MyMath.multiply_by/2`, that represent cases when
  arguments for parameters with default values are passed or not passed.

  When defining a function with default arguments as well as multiple
  explicitly declared clauses, you must write a function head that declares the
  defaults. For example:

      defmodule MyString do
        def join(string1, string2 \\ nil, separator \\ " ")

        def join(string1, nil, _separator) do
          string1
        end

        def join(string1, string2, separator) do
          string1 <> separator <> string2
        end
      end

  Note that `\\` can't be used with anonymous functions because they
  can only have a sole arity.

  ### Keyword lists with default arguments

  Functions containing many arguments can benefit from using `Keyword`
  lists to group and pass attributes as a single value.

      defmodule MyConfiguration do
        @default_opts [storage: "local"]

        def configure(resource, opts \\ []) do
          opts = Keyword.merge(@default_opts, opts)
          storage = opts[:storage]
          # ...
        end
      end

  The difference between using `Map` and `Keyword` to store many
  arguments is `Keyword`'s keys:

    * must be atoms
    * can be given more than once
    * ordered, as specified by the developer

  ## Function and variable names

  Function and variable names have the following syntax:
  A _lowercase ASCII letter_ or an _underscore_, followed by any number of
  _lowercase or uppercase ASCII letters_, _numbers_, or _underscores_.
  Optionally they can end in either an _exclamation mark_ or a _question mark_.

  For variables, any identifier starting with an underscore should indicate an
  unused variable. For example:

      def foo(bar) do
        []
      end
      #=> warning: variable bar is unused

      def foo(_bar) do
        []
      end
      #=> no warning

      def foo(_bar) do
        _bar
      end
      #=> warning: the underscored variable "_bar" is used after being set

  ## `rescue`/`catch`/`after`/`else`

  Function bodies support `rescue`, `catch`, `after`, and `else` as `Kernel.SpecialForms.try/1`
  does (known as "implicit try"). For example, the following two functions are equivalent:

      def convert(number) do
        try do
          String.to_integer(number)
        rescue
          e in ArgumentError -> {:error, e.message}
        end
      end

      def convert(number) do
        String.to_integer(number)
      rescue
        e in ArgumentError -> {:error, e.message}
      end


  """
  defmacro def(call, expr) do
    define(:def, call, expr, __CALLER__)
  end

  @doc ~S"""
  Defines a function that delegates to another module.

  Functions defined with `defdelegate/2` are public and can be invoked from
  outside the module they're defined in, as if they were defined using `def/2`.
  Therefore, `defdelegate/2` is about extending the current module's public API.
  If what you want is to invoke a function defined in another module without
  using its full module name, then use `alias/2` to shorten the module name or use
  `import/2` to be able to invoke the function without the module name altogether.

  Delegation only works with functions; delegating macros is not supported.

  Check `def/2` for rules on naming and default arguments.

  ## Options

    * `:to` - the module to dispatch to.

    * `:as` - the function to call on the target given in `:to`.
      This parameter is optional and defaults to the name being
      delegated (`funs`).

  ## Examples

      defmodule MyList do
        defdelegate reverse(list), to: Enum
        defdelegate other_reverse(list), to: Enum, as: :reverse
      end

      MyList.reverse([1, 2, 3])
      #=> [3, 2, 1]

      MyList.other_reverse([1, 2, 3])
      #=> [3, 2, 1]


  """
  defmacro defdelegate(funs, opts) do
    (
      funs = Macro.escape(funs, unquote: true)
      opts = with(true <- is_list(opts), {:ok, target} <- Keyword.fetch(opts, :to), {:__aliases__, _, _} <- target) do
        target = Macro.expand(target, %{__CALLER__ | function: {:__info__, 1}})
        Keyword.replace!(opts, :to, target)
      else
        _ ->
          opts
      end
      {:__block__, [], [{:=, [], [{:funs, [line: 5562], Kernel}, funs]}, {:=, [], [{:opts, [line: 5562], Kernel}, opts]}, {:__block__, [], [{:=, [], [{:target, [], Kernel}, {:||, [context: Kernel, import: Kernel], [{{:., [], [{:__aliases__, [alias: false], [:Keyword]}, :get]}, [], [{:opts, [], Kernel}, :to]}, {:raise, [context: Kernel, import: Kernel], [{:__aliases__, [alias: false], [:ArgumentError]}, "expected to: to be given as argument"]}]}]}, {:if, [context: Kernel, import: Kernel], [{:is_list, [context: Kernel, import: Kernel], [{:funs, [], Kernel}]}, [do: {{:., [], [{:__aliases__, [alias: false], [:IO]}, :warn]}, [], ["passing a list to Kernel.defdelegate/2 is deprecated, please define each delegate separately", {{:., [], [{:__aliases__, [alias: false], [:Macro, :Env]}, :stacktrace]}, [], [{:__ENV__, [], Kernel}]}]}]]}, {:if, [context: Kernel, import: Kernel], [{{:., [], [{:__aliases__, [alias: false], [:Keyword]}, :has_key?]}, [], [{:opts, [], Kernel}, :append_first]}, [do: {{:., [], [{:__aliases__, [alias: false], [:IO]}, :warn]}, [], ["Kernel.defdelegate/2 :append_first option is deprecated", {{:., [], [{:__aliases__, [alias: false], [:Macro, :Env]}, :stacktrace]}, [], [{:__ENV__, [], Kernel}]}]}]]}, {:for, [], [{:<-, [], [{:fun, [], Kernel}, {{:., [], [{:__aliases__, [alias: false], [:List]}, :wrap]}, [], [{:funs, [], Kernel}]}]}, [do: {:__block__, [], [{:=, [], [{:{}, [], [{:name, [], Kernel}, {:args, [], Kernel}, {:as, [], Kernel}, {:as_args, [], Kernel}]}, {{:., [], [{:__aliases__, [alias: false], [:Kernel, :Utils]}, :defdelegate]}, [], [{:fun, [], Kernel}, {:opts, [], Kernel}]}]}, {:@, [context: Kernel, import: :elixir_bootstrap], [{:doc, [context: Kernel], [[delegate_to: {:{}, [], [{:target, [], Kernel}, {:as, [], Kernel}, {{:., [], [:erlang, :length]}, [], [{:as_args, [], Kernel}]}]}]]}]}, {:def, [context: Kernel, import: :elixir_bootstrap], [{:unquote, [context: Kernel], [{:{}, [], [{:name, [], Kernel}, [line: {{:., [], [{:__ENV__, [], Kernel}, :line]}, [no_parens: true], []}], {:args, [], Kernel}]}]}, [do: {{{:., [], [{:unquote, [], [{:target, [], Kernel}]}, :unquote]}, [], [{:as, [], Kernel}]}, [], [{:unquote_splicing, [], [{:as_args, [], Kernel}]}]}]]}]}]]}]}]}
    )
  end

  @doc ~S"""
  Defines an exception.

  Exceptions are structs backed by a module that implements
  the `Exception` behaviour. The `Exception` behaviour requires
  two functions to be implemented:

    * [`exception/1`](`c:Exception.exception/1`) - receives the arguments given to `raise/2`
      and returns the exception struct. The default implementation
      accepts either a set of keyword arguments that is merged into
      the struct or a string to be used as the exception's message.

    * [`message/1`](`c:Exception.message/1`) - receives the exception struct and must return its
      message. Most commonly exceptions have a message field which
      by default is accessed by this function. However, if an exception
      does not have a message field, this function must be explicitly
      implemented.

  Since exceptions are structs, the API supported by `defstruct/1`
  is also available in `defexception/1`.

  ## Raising exceptions

  The most common way to raise an exception is via `raise/2`:

      defmodule MyAppError do
        defexception [:message]
      end

      value = [:hello]

      raise MyAppError,
        message: "did not get what was expected, got: #{inspect(value)}"

  In many cases it is more convenient to pass the expected value to
  `raise/2` and generate the message in the `c:Exception.exception/1` callback:

      defmodule MyAppError do
        defexception [:message]

        @impl true
        def exception(value) do
          msg = "did not get what was expected, got: #{inspect(value)}"
          %MyAppError{message: msg}
        end
      end

      raise MyAppError, value

  The example above shows the preferred strategy for customizing
  exception messages.

  """
  defmacro defexception(fields) do
    {:__block__, [], [{:=, [], [{:fields, [line: 5108], Kernel}, fields]}, {:__block__, [], [{:@, [context: Kernel, import: :elixir_bootstrap], [{:behaviour, [context: Kernel], [{:__aliases__, [alias: false], [:Exception]}]}]}, {:=, [], [{:struct, [], Kernel}, {:defstruct, [context: Kernel, import: Kernel], [{:++, [context: Kernel, import: Kernel], [[__exception__: true], {:fields, [], Kernel}]}]}]}, {:if, [context: Kernel, import: Kernel], [{{:., [], [{:__aliases__, [alias: false], [:Map]}, :has_key?]}, [], [{:struct, [], Kernel}, :message]}, [do: {:__block__, [], [{:@, [context: Kernel, import: :elixir_bootstrap], [{:impl, [context: Kernel], [true]}]}, {:def, [context: Kernel, import: :elixir_bootstrap], [{:message, [context: Kernel], [{:exception, [], Kernel}]}, [do: {{:., [], [{:exception, [], Kernel}, :message]}, [no_parens: true], []}]]}, {:defoverridable, [context: Kernel, import: Kernel], [[message: 1]]}, {:@, [context: Kernel, import: :elixir_bootstrap], [{:impl, [context: Kernel], [true]}]}, {:def, [context: Kernel, import: :elixir_bootstrap], [{:when, [context: Kernel], [{:exception, [], [{:msg, [], Kernel}]}, {{:., [], [{:__aliases__, [alias: false], [:Kernel]}, :is_binary]}, [], [{:msg, [], Kernel}]}]}, [do: {:exception, [], [[message: {:msg, [], Kernel}]]}]]}]}]]}, {:@, [context: Kernel, import: :elixir_bootstrap], [{:impl, [context: Kernel], [true]}]}, {:def, [context: Kernel, import: :elixir_bootstrap], [{:when, [context: Kernel], [{:exception, [], [{:args, [], Kernel}]}, {{:., [], [{:__aliases__, [alias: false], [:Kernel]}, :is_list]}, [], [{:args, [], Kernel}]}]}, [do: {:__block__, [], [{:=, [], [{:struct, [], Kernel}, {:__struct__, [], []}]}, {:=, [], [{{:valid, [], Kernel}, {:invalid, [], Kernel}}, {{:., [], [{:__aliases__, [alias: false], [:Enum]}, :split_with]}, [], [{:args, [], Kernel}, {:fn, [], [{:->, [], [[{{:k, [], Kernel}, {:_, [], Kernel}}], {{:., [], [{:__aliases__, [alias: false], [:Map]}, :has_key?]}, [], [{:struct, [], Kernel}, {:k, [], Kernel}]}]}]}]}]}, {:case, [], [{:invalid, [], Kernel}, [do: [{:->, [], [[[]], :ok]}, {:->, [], [[{:_, [], Kernel}], {{:., [], [{:__aliases__, [alias: false], [:IO]}, :warn]}, [], [{:<>, [context: Kernel, import: Kernel], ["the following fields are unknown when raising ", {:<>, [context: Kernel, import: Kernel], [{:<<>>, [], [{:"::", [], [{{:., [], [Kernel, :to_string]}, [], [{{:., [], [{:__aliases__, [alias: false], [:Kernel]}, :inspect]}, [], [{:__MODULE__, [], Kernel}]}]}, {:binary, [], Kernel}]}, ": ", {:"::", [], [{{:., [], [Kernel, :to_string]}, [], [{{:., [], [{:__aliases__, [alias: false], [:Kernel]}, :inspect]}, [], [{:invalid, [], Kernel}]}]}, {:binary, [], Kernel}]}, ". "]}, {:<>, [context: Kernel, import: Kernel], ["Please make sure to only give known fields when raising ", {:<>, [context: Kernel, import: Kernel], [{:<<>>, [], ["or redefine ", {:"::", [], [{{:., [], [Kernel, :to_string]}, [], [{{:., [], [{:__aliases__, [alias: false], [:Kernel]}, :inspect]}, [], [{:__MODULE__, [], Kernel}]}]}, {:binary, [], Kernel}]}, ".exception/1 to "]}, {:<>, [context: Kernel, import: Kernel], ["discard unknown fields. Future Elixir versions will raise on ", "unknown fields given to raise/2"]}]}]}]}]}]}]}]]]}, {{:., [], [{:__aliases__, [alias: false], [:Kernel]}, :struct!]}, [], [{:struct, [], Kernel}, {:valid, [], Kernel}]}]}]]}, {:defoverridable, [context: Kernel, import: Kernel], [[exception: 1]]}]}]}
  end

  @doc ~S"""
  Generates a macro suitable for use in guard expressions.

  It raises at compile time if the definition uses expressions that aren't
  allowed in guards, and otherwise creates a macro that can be used both inside
  or outside guards.

  Note the convention in Elixir is to name functions/macros allowed in
  guards with the `is_` prefix, such as `is_list/1`. If, however, the
  function/macro returns a boolean and is not allowed in guards, it should
  have no prefix and end with a question mark, such as `Keyword.keyword?/1`.

  ## Example

      defmodule Integer.Guards do
        defguard is_even(value) when is_integer(value) and rem(value, 2) == 0
      end

      defmodule Collatz do
        @moduledoc "Tools for working with the Collatz sequence."
        import Integer.Guards

        @doc "Determines the number of steps `n` takes to reach `1`."
        # If this function never converges, please let me know what `n` you used.
        def converge(n) when n > 0, do: step(n, 0)

        defp step(1, step_count) do
          step_count
        end

        defp step(n, step_count) when is_even(n) do
          step(div(n, 2), step_count + 1)
        end

        defp step(n, step_count) do
          step(3 * n + 1, step_count + 1)
        end
      end


  """
  defmacro defguard(guard) do
    define_guard(:defmacro, guard, __CALLER__)
  end

  @doc ~S"""
  Generates a private macro suitable for use in guard expressions.

  It raises at compile time if the definition uses expressions that aren't
  allowed in guards, and otherwise creates a private macro that can be used
  both inside or outside guards in the current module.

  Similar to `defmacrop/2`, `defguardp/1` must be defined before its use
  in the current module.

  """
  defmacro defguardp(guard) do
    define_guard(:defmacrop, guard, __CALLER__)
  end

  defmacro defimpl(name, opts) do
    super(name, opts, [])
  end

  @doc ~S"""
  Defines an implementation for the given protocol.

  See the `Protocol` module for more information.

  """
  defmacro defimpl(name, opts, do_block) do
    (
      merged = Keyword.merge(opts, do_block)
      merged = Keyword.put_new(merged, :for, __CALLER__.module())
      case(is_nil(Keyword.fetch!(merged, :for))) do
        false ->
          nil
        true ->
          raise(ArgumentError, "defimpl/3 expects a :for option when declared outside a module")
      end
      Protocol.__impl__(name, merged)
    )
  end

  defmacro defmacro(call) do
    super(call, nil)
  end

  @doc ~S"""
  Defines a public macro with the given name and body.

  Macros must be defined before its usage.

  Check `def/2` for rules on naming and default arguments.

  ## Examples

      defmodule MyLogic do
        defmacro unless(expr, opts) do
          quote do
            if !unquote(expr), unquote(opts)
          end
        end
      end

      require MyLogic

      MyLogic.unless false do
        IO.puts("It works")
      end


  """
  defmacro defmacro(call, expr) do
    define(:defmacro, call, expr, __CALLER__)
  end

  defmacro defmacrop(call) do
    super(call, nil)
  end

  @doc ~S"""
  Defines a private macro with the given name and body.

  Private macros are only accessible from the same module in which they are
  defined.

  Private macros must be defined before its usage.

  Check `defmacro/2` for more information, and check `def/2` for rules on
  naming and default arguments.


  """
  defmacro defmacrop(call, expr) do
    define(:defmacrop, call, expr, __CALLER__)
  end

  @doc ~S"""
  Defines a module given by name with the given contents.

  This macro defines a module with the given `alias` as its name and with the
  given contents. It returns a tuple with four elements:

    * `:module`
    * the module name
    * the binary contents of the module
    * the result of evaluating the contents block

  ## Examples

      defmodule Number do
        def one, do: 1
        def two, do: 2
      end
      #=> {:module, Number, <<70, 79, 82, ...>>, {:two, 0}}

      Number.one()
      #=> 1

      Number.two()
      #=> 2

  ## Nesting

  Nesting a module inside another module affects the name of the nested module:

      defmodule Foo do
        defmodule Bar do
        end
      end

  In the example above, two modules - `Foo` and `Foo.Bar` - are created.
  When nesting, Elixir automatically creates an alias to the inner module,
  allowing the second module `Foo.Bar` to be accessed as `Bar` in the same
  lexical scope where it's defined (the `Foo` module). This only happens
  if the nested module is defined via an alias.

  If the `Foo.Bar` module is moved somewhere else, the references to `Bar` in
  the `Foo` module need to be updated to the fully-qualified name (`Foo.Bar`) or
  an alias has to be explicitly set in the `Foo` module with the help of
  `Kernel.SpecialForms.alias/2`.

      defmodule Foo.Bar do
        # code
      end

      defmodule Foo do
        alias Foo.Bar
        # code here can refer to "Foo.Bar" as just "Bar"
      end

  ## Dynamic names

  Elixir module names can be dynamically generated. This is very
  useful when working with macros. For instance, one could write:

      defmodule String.to_atom("Foo#{1}") do
        # contents ...
      end

  Elixir will accept any module name as long as the expression passed as the
  first argument to `defmodule/2` evaluates to an atom.
  Note that, when a dynamic name is used, Elixir won't nest the name under
  the current module nor automatically set up an alias.

  ## Reserved module names

  If you attempt to define a module that already exists, you will get a
  warning saying that a module has been redefined.

  There are some modules that Elixir does not currently implement but it
  may be implement in the future. Those modules are reserved and defining
  them will result in a compilation error:

      defmodule Any do
        # code
      end
      ** (CompileError) iex:1: module Any is reserved and cannot be defined

  Elixir reserves the following module names: `Elixir`, `Any`, `BitString`,
  `PID`, and `Reference`.

  """
  defmacro defmodule(alias, [do: block]) do
    (
      env = __CALLER__
      expanded = expand_module_alias(alias, env)
      {expanded, with_alias} = case(is_atom(expanded)) do
        true ->
          {full, old, new} = alias_defmodule(alias, expanded, env)
          meta = [defined: full, context: env.module()] ++ alias_meta(alias)
          {full, {:alias, meta, [old, [as: new, warn: false]]}}
        false ->
          {expanded, nil}
      end
      block = {:__block__, [], [{:=, [], [{:result, [], Kernel}, block]}, {{:., [], [:elixir_utils, :noop]}, [], []}, {:result, [], Kernel}]}
      escaped = case(env) do
        %{function: nil, lexical_tracker: pid} when is_pid(pid) ->
          integer = Kernel.LexicalTracker.write_cache(pid, block)
          {{:., [], [{:__aliases__, [alias: false], [:Kernel, :LexicalTracker]}, :read_cache]}, [], [pid, integer]}
        %{} ->
          :elixir_quote.escape(block, :none, false)
      end
      module_vars = :lists.map(&module_var/1, :maps.keys(env.versioned_vars()))
      {:__block__, [], [with_alias, {{:., [], [:elixir_module, :compile]}, [], [expanded, escaped, module_vars, {:__ENV__, [], Kernel}]}]}
    )
  end

  @doc ~S"""
  Makes the given definitions in the current module overridable.

  If the user defines a new function or macro with the same name
  and arity, then the overridable ones are discarded. Otherwise, the
  original definitions are used.

  It is possible for the overridden definition to have a different visibility
  than the original: a public function can be overridden by a private
  function and vice-versa.

  Macros cannot be overridden as functions and vice-versa.

  ## Example

      defmodule DefaultMod do
        defmacro __using__(_opts) do
          quote do
            def test(x, y) do
              x + y
            end

            defoverridable test: 2
          end
        end
      end

      defmodule InheritMod do
        use DefaultMod

        def test(x, y) do
          x * y + super(x, y)
        end
      end

  As seen as in the example above, `super` can be used to call the default
  implementation.

  If `@behaviour` has been defined, `defoverridable` can also be called with a
  module as an argument. All implemented callbacks from the behaviour above the
  call to `defoverridable` will be marked as overridable.

  ## Example

      defmodule Behaviour do
        @callback foo :: any
      end

      defmodule DefaultMod do
        defmacro __using__(_opts) do
          quote do
            @behaviour Behaviour

            def foo do
              "Override me"
            end

            defoverridable Behaviour
          end
        end
      end

      defmodule InheritMod do
        use DefaultMod

        def foo do
          "Overridden"
        end
      end


  """
  defmacro defoverridable(keywords_or_behaviour) do
    {{:., [], [{:__aliases__, [alias: false], [:Module]}, :make_overridable]}, [], [{:__MODULE__, [], Kernel}, keywords_or_behaviour]}
  end

  defmacro defp(call) do
    super(call, nil)
  end

  @doc ~S"""
  Defines a private function with the given name and body.

  Private functions are only accessible from within the module in which they are
  defined. Trying to access a private function from outside the module it's
  defined in results in an `UndefinedFunctionError` exception.

  Check `def/2` for more information.

  ## Examples

      defmodule Foo do
        def bar do
          sum(1, 2)
        end

        defp sum(a, b), do: a + b
      end

      Foo.bar()
      #=> 3

      Foo.sum(1, 2)
      ** (UndefinedFunctionError) undefined function Foo.sum/2


  """
  defmacro defp(call, expr) do
    define(:defp, call, expr, __CALLER__)
  end

  @doc ~S"""
  Defines a protocol.

  See the `Protocol` module for more information.

  """
  defmacro defprotocol(name, [do: block]) do
    Protocol.__protocol__(name) do
      block
    end
  end

  @doc ~S"""
  Defines a struct.

  A struct is a tagged map that allows developers to provide
  default values for keys, tags to be used in polymorphic
  dispatches and compile time assertions. For more information
  about structs, please check `Kernel.SpecialForms.%/2`.

  It is only possible to define a struct per module, as the
  struct it tied to the module itself. Calling `defstruct/1`
  also defines a `__struct__/0` function that returns the
  struct itself.

  ## Examples

      defmodule User do
        defstruct name: nil, age: nil
      end

  Struct fields are evaluated at compile-time, which allows
  them to be dynamic. In the example below, `10 + 11` is
  evaluated at compile-time and the age field is stored
  with value `21`:

      defmodule User do
        defstruct name: nil, age: 10 + 11
      end

  The `fields` argument is usually a keyword list with field names
  as atom keys and default values as corresponding values. `defstruct/1`
  also supports a list of atoms as its argument: in that case, the atoms
  in the list will be used as the struct's field names and they will all
  default to `nil`.

      defmodule Post do
        defstruct [:title, :content, :author]
      end

  ## Deriving

  Although structs are maps, by default structs do not implement
  any of the protocols implemented for maps. For example, attempting
  to use a protocol with the `User` struct leads to an error:

      john = %User{name: "John"}
      MyProtocol.call(john)
      ** (Protocol.UndefinedError) protocol MyProtocol not implemented for %User{...}

  `defstruct/1`, however, allows protocol implementations to be
  *derived*. This can be done by defining a `@derive` attribute as a
  list before invoking `defstruct/1`:

      defmodule User do
        @derive [MyProtocol]
        defstruct name: nil, age: 10 + 11
      end

      MyProtocol.call(john) # it works!

  For each protocol in the `@derive` list, Elixir will assert the protocol has
  been implemented for `Any`. If the `Any` implementation defines a
  `__deriving__/3` callback, the callback will be invoked and it should define
  the implementation module. Otherwise an implementation that simply points to
  the `Any` implementation is automatically derived. For more information on
  the `__deriving__/3` callback, see `Protocol.derive/3`.

  ## Enforcing keys

  When building a struct, Elixir will automatically guarantee all keys
  belongs to the struct:

      %User{name: "john", unknown: :key}
      ** (KeyError) key :unknown not found in: %User{age: 21, name: nil}

  Elixir also allows developers to enforce certain keys must always be
  given when building the struct:

      defmodule User do
        @enforce_keys [:name]
        defstruct name: nil, age: 10 + 11
      end

  Now trying to build a struct without the name key will fail:

      %User{age: 21}
      ** (ArgumentError) the following keys must also be given when building struct User: [:name]

  Keep in mind `@enforce_keys` is a simple compile-time guarantee
  to aid developers when building structs. It is not enforced on
  updates and it does not provide any sort of value-validation.

  ## Types

  It is recommended to define types for structs. By convention such type
  is called `t`. To define a struct inside a type, the struct literal syntax
  is used:

      defmodule User do
        defstruct name: "John", age: 25
        @type t :: %__MODULE__{name: String.t(), age: non_neg_integer}
      end

  It is recommended to only use the struct syntax when defining the struct's
  type. When referring to another struct it's better to use `User.t` instead of
  `%User{}`.

  The types of the struct fields that are not included in `%User{}` default to
  `term()` (see `t:term/0`).

  Structs whose internal structure is private to the local module (pattern
  matching them or directly accessing their fields should not be allowed) should
  use the `@opaque` attribute. Structs whose internal structure is public should
  use `@type`.

  """
  defmacro defstruct(fields) do
    (
      builder = case(bootstrapped?(Enum)) do
        true ->
          {:case, [], [{:@, [context: Kernel, import: :elixir_bootstrap], [{:enforce_keys, [context: Kernel], Kernel}]}, [do: [{:->, [], [[[]], {:def, [context: Kernel, import: :elixir_bootstrap], [{:__struct__, [context: Kernel], [{:kv, [], Kernel}]}, [do: {{:., [], [{:__aliases__, [alias: false], [:Enum]}, :reduce]}, [], [{:kv, [], Kernel}, {:@, [context: Kernel, import: :elixir_bootstrap], [{:__struct__, [context: Kernel], Kernel}]}, {:fn, [], [{:->, [], [[{{:key, [], Kernel}, {:val, [], Kernel}}, {:map, [], Kernel}], {{:., [], [{:__aliases__, [alias: false], [:Map]}, :replace!]}, [], [{:map, [], Kernel}, {:key, [], Kernel}, {:val, [], Kernel}]}]}]}]}]]}]}, {:->, [], [[{:_, [], Kernel}], {:def, [context: Kernel, import: :elixir_bootstrap], [{:__struct__, [context: Kernel], [{:kv, [], Kernel}]}, [do: {:__block__, [], [{:=, [], [{{:map, [], Kernel}, {:keys, [], Kernel}}, {{:., [], [{:__aliases__, [alias: false], [:Enum]}, :reduce]}, [], [{:kv, [], Kernel}, {{:@, [context: Kernel, import: :elixir_bootstrap], [{:__struct__, [context: Kernel], Kernel}]}, {:@, [context: Kernel, import: :elixir_bootstrap], [{:enforce_keys, [context: Kernel], Kernel}]}}, {:fn, [], [{:->, [], [[{{:key, [], Kernel}, {:val, [], Kernel}}, {{:map, [], Kernel}, {:keys, [], Kernel}}], {{{:., [], [{:__aliases__, [alias: false], [:Map]}, :replace!]}, [], [{:map, [], Kernel}, {:key, [], Kernel}, {:val, [], Kernel}]}, {{:., [], [{:__aliases__, [alias: false], [:List]}, :delete]}, [], [{:keys, [], Kernel}, {:key, [], Kernel}]}}]}]}]}]}, {:case, [], [{:keys, [], Kernel}, [do: [{:->, [], [[[]], {:map, [], Kernel}]}, {:->, [], [[{:_, [], Kernel}], {:raise, [context: Kernel, import: Kernel], [{:__aliases__, [alias: false], [:ArgumentError]}, {:<>, [context: Kernel, import: Kernel], ["the following keys must also be given when building ", {:<<>>, [], ["struct ", {:"::", [], [{{:., [], [Kernel, :to_string]}, [], [{:inspect, [context: Kernel, import: Kernel], [{:__MODULE__, [], Kernel}]}]}, {:binary, [], Kernel}]}, ": ", {:"::", [], [{{:., [], [Kernel, :to_string]}, [], [{:inspect, [context: Kernel, import: Kernel], [{:keys, [], Kernel}]}]}, {:binary, [], Kernel}]}]}]}]}]}]]]}]}]]}]}]]]}
        false ->
          {:__block__, [], [{:=, [], [{:_, [], Kernel}, {:@, [context: Kernel, import: :elixir_bootstrap], [{:enforce_keys, [context: Kernel], Kernel}]}]}, {:def, [context: Kernel, import: :elixir_bootstrap], [{:__struct__, [context: Kernel], [{:kv, [], Kernel}]}, [do: {{:., [], [:lists, :foldl]}, [], [{:fn, [], [{:->, [], [[{{:key, [], Kernel}, {:val, [], Kernel}}, {:acc, [], Kernel}], {{:., [], [{:__aliases__, [alias: false], [:Map]}, :replace!]}, [], [{:acc, [], Kernel}, {:key, [], Kernel}, {:val, [], Kernel}]}]}]}, {:@, [context: Kernel, import: :elixir_bootstrap], [{:__struct__, [context: Kernel], Kernel}]}, {:kv, [], Kernel}]}]]}]}
      end
      {:__block__, [], [{:if, [context: Kernel, import: Kernel], [{{:., [], [{:__aliases__, [alias: false], [:Module]}, :has_attribute?]}, [], [{:__MODULE__, [], Kernel}, :__struct__]}, [do: {:raise, [context: Kernel, import: Kernel], [{:__aliases__, [alias: false], [:ArgumentError]}, {:<>, [context: Kernel, import: Kernel], ["defstruct has already been called for ", {:<<>>, [], [{:"::", [], [{{:., [], [Kernel, :to_string]}, [], [{{:., [], [{:__aliases__, [alias: false], [:Kernel]}, :inspect]}, [], [{:__MODULE__, [], Kernel}]}]}, {:binary, [], Kernel}]}, ", defstruct can only be called once per module"]}]}]}]]}, {:=, [], [{:{}, [], [{:struct, [], Kernel}, {:keys, [], Kernel}, {:derive, [], Kernel}]}, {{:., [], [{:__aliases__, [alias: false], [:Kernel, :Utils]}, :defstruct]}, [], [{:__MODULE__, [], Kernel}, fields]}]}, {:@, [context: Kernel, import: :elixir_bootstrap], [{:__struct__, [context: Kernel], [{:struct, [], Kernel}]}]}, {:@, [context: Kernel, import: :elixir_bootstrap], [{:enforce_keys, [context: Kernel], [{:keys, [], Kernel}]}]}, {:case, [], [{:derive, [], Kernel}, [do: [{:->, [], [[[]], :ok]}, {:->, [], [[{:_, [], Kernel}], {{:., [], [{:__aliases__, [alias: false], [:Protocol]}, :__derive__]}, [], [{:derive, [], Kernel}, {:__MODULE__, [], Kernel}, {:__ENV__, [], Kernel}]}]}]]]}, {:def, [context: Kernel, import: :elixir_bootstrap], [{:__struct__, [context: Kernel], []}, [do: {:@, [context: Kernel, import: :elixir_bootstrap], [{:__struct__, [context: Kernel], Kernel}]}]]}, builder, {{:., [], [{:__aliases__, [alias: false], [:Kernel, :Utils]}, :announce_struct]}, [], [{:__MODULE__, [], Kernel}]}, {:struct, [], Kernel}]}
    )
  end

  @doc ~S"""
  Destructures two lists, assigning each term in the
  right one to the matching term in the left one.

  Unlike pattern matching via `=`, if the sizes of the left
  and right lists don't match, destructuring simply stops
  instead of raising an error.

  ## Examples

      iex> destructure([x, y, z], [1, 2, 3, 4, 5])
      iex> {x, y, z}
      {1, 2, 3}

  In the example above, even though the right list has more entries than the
  left one, destructuring works fine. If the right list is smaller, the
  remaining elements are simply set to `nil`:

      iex> destructure([x, y, z], [1])
      iex> {x, y, z}
      {1, nil, nil}

  The left-hand side supports any expression you would use
  on the left-hand side of a match:

      x = 1
      destructure([^x, y, z], [1, 2, 3])

  The example above will only work if `x` matches the first value in the right
  list. Otherwise, it will raise a `MatchError` (like the `=` operator would
  do).

  """
  defmacro destructure(left, right) when is_list(left) do
    {:=, [], [left, {{:., [], [{:__aliases__, [alias: false], [:Kernel, :Utils]}, :destructure]}, [], [right, length(left)]}]}
  end

  @doc ~S"""
  Gets a value and updates a nested data structure via the given `path`.

  This is similar to `get_and_update_in/3`, except the path is extracted
  via a macro rather than passing a list. For example:

      get_and_update_in(opts[:foo][:bar], &{&1, &1 + 1})

  Is equivalent to:

      get_and_update_in(opts, [:foo, :bar], &{&1, &1 + 1})

  This also works with nested structs and the `struct.path.to.value` way to specify
  paths:

      get_and_update_in(struct.foo.bar, &{&1, &1 + 1})

  Note that in order for this macro to work, the complete path must always
  be visible by this macro. See the "Paths" section below.

  ## Examples

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> get_and_update_in(users["john"].age, &{&1, &1 + 1})
      {27, %{"john" => %{age: 28}, "meg" => %{age: 23}}}

  ## Paths

  A path may start with a variable, local or remote call, and must be
  followed by one or more:

    * `foo[bar]` - accesses the key `bar` in `foo`; in case `foo` is nil,
      `nil` is returned

    * `foo.bar` - accesses a map/struct field; in case the field is not
      present, an error is raised

  Here are some valid paths:

      users["john"][:age]
      users["john"].age
      User.all()["john"].age
      all_users()["john"].age

  Here are some invalid ones:

      # Does a remote call after the initial value
      users["john"].do_something(arg1, arg2)

      # Does not access any key or field
      users


  """
  defmacro get_and_update_in(path, fun) do
    (
      {[h | t], _} = unnest(path, [], true, "get_and_update_in/2")
      nest_get_and_update_in(h, t, fun)
    )
  end

  @doc ~S"""
  Provides an `if/2` macro.

  This macro expects the first argument to be a condition and the second
  argument to be a keyword list.

  ## One-liner examples

      if(foo, do: bar)

  In the example above, `bar` will be returned if `foo` evaluates to
  a truthy value (neither `false` nor `nil`). Otherwise, `nil` will be
  returned.

  An `else` option can be given to specify the opposite:

      if(foo, do: bar, else: baz)

  ## Blocks examples

  It's also possible to pass a block to the `if/2` macro. The first
  example above would be translated to:

      if foo do
        bar
      end

  Note that `do`-`end` become delimiters. The second example would
  translate to:

      if foo do
        bar
      else
        baz
      end

  In order to compare more than two clauses, the `cond/1` macro has to be used.

  """
  defmacro if(condition, clauses) do
    build_if(condition, clauses)
  end

  @doc ~S"""
  Membership operator. Checks if the element on the left-hand side is a member of the
  collection on the right-hand side.

  ## Examples

      iex> x = 1
      iex> x in [1, 2, 3]
      true

  This operator (which is a macro) simply translates to a call to
  `Enum.member?/2`. The example above would translate to:

      Enum.member?([1, 2, 3], x)

  Elixir also supports `left not in right`, which evaluates to
  `not(left in right)`:

      iex> x = 1
      iex> x not in [1, 2, 3]
      false

  ## Guards

  The `in/2` operator (as well as `not in`) can be used in guard clauses as
  long as the right-hand side is a range or a list. In such cases, Elixir will
  expand the operator to a valid guard expression. For example:

      when x in [1, 2, 3]

  translates to:

      when x === 1 or x === 2 or x === 3

  However, this construct will be inneficient for large lists. In such cases, it
  is best to stop using guards and use a more appropriate data structure, such
  as `MapSet`.

  ### AST considerations

  `left not in right` is parsed by the compiler into the AST:

      {:not, _, [{:in, _, [left, right]}]}

  This is the same AST as `not(left in right)`.

  Additionally, `Macro.to_string/2` and `Code.format_string!/2`
  will translate all occurrences of this AST to `left not in right`.

  """
  defmacro left in right do
    (
      in_body? = is_nil(__CALLER__.context())
      expand = case(bootstrapped?(Macro)) do
        true ->
          fn x1 -> Macro.expand(x1, __CALLER__) end
        false ->
          fn x1 -> x1 end
      end
      case(expand.(right)) do
        [] when not(in_body?) ->
          false
        [] ->
          {:__block__, [], [{:=, [], [{:_, [], Kernel}, left]}, false]}
        [head | tail] = list ->
          case(not(in_body?) or small_literal_list?(right)) do
            true ->
              in_var(in_body?, left, fn x1 -> in_list(x1, head, tail, expand, list, in_body?) end)
            false ->
              {{:., [], [:lists, :member]}, [], [left, right]}
          end
        {:%{}, _meta, [__struct__: Range, first: first, last: last, step: step]} ->
          in_var(in_body?, left, fn x1 -> in_range(x1, expand.(first), expand.(last), expand.(step)) end)
        right when in_body? ->
          {{:., [], [{:__aliases__, [], [Elixir, :Enum]}, :member?]}, [], [right, left]}
        %Range{first: _, last: _, step: _} ->
          raise(ArgumentError, "non-literal range in guard should be escaped with Macro.escape/2")
        right ->
          raise_on_invalid_args_in_2(right)
      end
    )
  end

  @doc ~S"""
  Returns true if `term` is an exception; otherwise returns `false`.

  Allowed in guard tests.

  ## Examples

      iex> is_exception(%RuntimeError{})
      true

      iex> is_exception(%{})
      false


  """
  defmacro is_exception(term) do
    case(__CALLER__.context()) do
      nil ->
        {:case, [], [term, [do: [{:->, [], [[{:%, [], [{:_, [], Kernel}, {:%{}, [], [__exception__: true]}]}], true]}, {:->, [], [[{:_, [], Kernel}], false]}]]]}
      :match ->
        invalid_match!(:is_exception)
      :guard ->
        {:and, [context: Kernel, import: Kernel], [{:and, [context: Kernel, import: Kernel], [{:and, [context: Kernel, import: Kernel], [{:and, [context: Kernel, import: Kernel], [{:is_map, [context: Kernel, import: Kernel], [term]}, {{:., [], [:erlang, :is_map_key]}, [], [:__struct__, term]}]}, {:is_atom, [context: Kernel, import: Kernel], [{{:., [], [:erlang, :map_get]}, [], [:__struct__, term]}]}]}, {{:., [], [:erlang, :is_map_key]}, [], [:__exception__, term]}]}, {:==, [context: Kernel, import: Kernel], [{{:., [], [:erlang, :map_get]}, [], [:__exception__, term]}, true]}]}
    end
  end

  @doc ~S"""
  Returns true if `term` is an exception of `name`; otherwise returns `false`.

  Allowed in guard tests.

  ## Examples

      iex> is_exception(%RuntimeError{}, RuntimeError)
      true

      iex> is_exception(%RuntimeError{}, Macro.Env)
      false


  """
  defmacro is_exception(term, name) do
    case(__CALLER__.context()) do
      nil ->
        {:case, [], [name, [do: [{:->, [], [[{:when, [], [{:name, [], Kernel}, {:is_atom, [context: Kernel, import: Kernel], [{:name, [], Kernel}]}]}], {:case, [], [term, [do: [{:->, [], [[{:%{}, [], [__struct__: {:^, [], [{:name, [], Kernel}]}, __exception__: true]}], true]}, {:->, [], [[{:_, [], Kernel}], false]}]]]}]}, {:->, [], [[{:_, [], Kernel}], {:raise, [context: Kernel, import: Kernel], [{:__aliases__, [alias: false], [:ArgumentError]}]}]}]]]}
      :match ->
        invalid_match!(:is_exception)
      :guard ->
        {:and, [context: Kernel, import: Kernel], [{:and, [context: Kernel, import: Kernel], [{:and, [context: Kernel, import: Kernel], [{:and, [context: Kernel, import: Kernel], [{:and, [context: Kernel, import: Kernel], [{:is_map, [context: Kernel, import: Kernel], [term]}, {:or, [context: Kernel, import: Kernel], [{:is_atom, [context: Kernel, import: Kernel], [name]}, :fail]}]}, {{:., [], [:erlang, :is_map_key]}, [], [:__struct__, term]}]}, {:==, [context: Kernel, import: Kernel], [{{:., [], [:erlang, :map_get]}, [], [:__struct__, term]}, name]}]}, {{:., [], [:erlang, :is_map_key]}, [], [:__exception__, term]}]}, {:==, [context: Kernel, import: Kernel], [{{:., [], [:erlang, :map_get]}, [], [:__exception__, term]}, true]}]}
    end
  end

  @doc ~S"""
  Returns `true` if `term` is `nil`, `false` otherwise.

  Allowed in guard clauses.

  ## Examples

      iex> is_nil(1)
      false

      iex> is_nil(nil)
      true


  """
  defmacro is_nil(term) do
    {:==, [context: Kernel, import: Kernel], [term, nil]}
  end

  @doc ~S"""
  Returns true if `term` is a struct; otherwise returns `false`.

  Allowed in guard tests.

  ## Examples

      iex> is_struct(URI.parse("/"))
      true

      iex> is_struct(%{})
      false


  """
  defmacro is_struct(term) do
    case(__CALLER__.context()) do
      nil ->
        {:case, [], [term, [do: [{:->, [], [[{:%, [], [{:_, [], Kernel}, {:%{}, [], []}]}], true]}, {:->, [], [[{:_, [], Kernel}], false]}]]]}
      :match ->
        invalid_match!(:is_struct)
      :guard ->
        {:and, [context: Kernel, import: Kernel], [{:and, [context: Kernel, import: Kernel], [{:is_map, [context: Kernel, import: Kernel], [term]}, {{:., [], [:erlang, :is_map_key]}, [], [:__struct__, term]}]}, {:is_atom, [context: Kernel, import: Kernel], [{{:., [], [:erlang, :map_get]}, [], [:__struct__, term]}]}]}
    end
  end

  @doc ~S"""
  Returns true if `term` is a struct of `name`; otherwise returns `false`.

  Allowed in guard tests.

  ## Examples

      iex> is_struct(URI.parse("/"), URI)
      true

      iex> is_struct(URI.parse("/"), Macro.Env)
      false


  """
  defmacro is_struct(term, name) do
    case(__CALLER__.context()) do
      nil ->
        {:case, [generated: true], [name, [do: [{:->, [generated: true], [[{:when, [generated: true], [{:name, [generated: true], Kernel}, {:is_atom, [generated: true, context: Kernel, import: Kernel], [{:name, [generated: true], Kernel}]}]}], {:case, [generated: true], [term, [do: [{:->, [generated: true], [[{:%{}, [generated: true], [__struct__: {:^, [generated: true], [{:name, [generated: true], Kernel}]}]}], true]}, {:->, [generated: true], [[{:_, [generated: true], Kernel}], false]}]]]}]}, {:->, [generated: true], [[{:_, [generated: true], Kernel}], {:raise, [generated: true, context: Kernel, import: Kernel], [{:__aliases__, [generated: true, alias: false], [:ArgumentError]}]}]}]]]}
      :match ->
        invalid_match!(:is_struct)
      :guard ->
        {:and, [context: Kernel, import: Kernel], [{:and, [context: Kernel, import: Kernel], [{:and, [context: Kernel, import: Kernel], [{:is_map, [context: Kernel, import: Kernel], [term]}, {:or, [context: Kernel, import: Kernel], [{:is_atom, [context: Kernel, import: Kernel], [name]}, :fail]}]}, {{:., [], [:erlang, :is_map_key]}, [], [:__struct__, term]}]}, {:==, [context: Kernel, import: Kernel], [{{:., [], [:erlang, :map_get]}, [], [:__struct__, term]}, name]}]}
    end
  end

  @doc ~S"""
  A convenience macro that checks if the right side (an expression) matches the
  left side (a pattern).

  ## Examples

      iex> match?(1, 1)
      true

      iex> match?({1, _}, {1, 2})
      true

      iex> map = %{a: 1, b: 2}
      iex> match?(%{a: _}, map)
      true

      iex> a = 1
      iex> match?(^a, 1)
      true

  `match?/2` is very useful when filtering or finding a value in an enumerable:

      iex> list = [a: 1, b: 2, a: 3]
      iex> Enum.filter(list, &match?({:a, _}, &1))
      [a: 1, a: 3]

  Guard clauses can also be given to the match:

      iex> list = [a: 1, b: 2, a: 3]
      iex> Enum.filter(list, &match?({:a, x} when x < 2, &1))
      [a: 1]

  However, variables assigned in the match will not be available
  outside of the function call (unlike regular pattern matching with the `=`
  operator):

      iex> match?(_x, 1)
      true
      iex> binding()
      []

  Furthermore, remember the pin operator matches _values_, not _patterns_:

      match?(%{x: 1}, %{x: 1, y: 2})
      #=> true

      attrs = %{x: 1}
      match?(^attrs, %{x: 1, y: 2})
      #=> false

  The pin operator will check if the values are equal, using `===/2`, while
  patterns have their own rules when matching maps, lists, and so forth.
  Such behaviour is not specific to `match?/2`. The following code also
  throws an exception:

      attrs = %{x: 1}
      ^attrs = %{x: 1, y: 2}
      #=> (MatchError) no match of right hand side value: %{x: 1, y: 2}


  """
  defmacro match?(pattern, expr) do
    (
      success = [{:->, [], [[pattern], true]}]
      failure = [{:->, [generated: true], [[{:_, [generated: true], Kernel}], false]}]
      {:case, [], [expr, [do: success ++ failure]]}
    )
  end

  @doc ~S"""
  Strictly boolean "or" operator.

  If `left` is `true`, returns `true`; otherwise returns `right`.

  Requires only the `left` operand to be a boolean since it short-circuits.
  If the `left` operand is not a boolean, a `BadBooleanError` exception is
  raised.

  Allowed in guard tests.

  ## Examples

      iex> true or false
      true

      iex> false or 42
      42

      iex> 42 or false
      ** (BadBooleanError) expected a boolean on left-side of "or", got: 42


  """
  defmacro left or right do
    case(__CALLER__.context()) do
      nil ->
        build_boolean_check(:or, left, true, right)
      :match ->
        invalid_match!(:or)
      :guard ->
        {{:., [], [:erlang, :orelse]}, [], [left, right]}
    end
  end

  @doc ~S"""
  Pops a key from the nested structure via the given `path`.

  This is similar to `pop_in/2`, except the path is extracted via
  a macro rather than passing a list. For example:

      pop_in(opts[:foo][:bar])

  Is equivalent to:

      pop_in(opts, [:foo, :bar])

  Note that in order for this macro to work, the complete path must always
  be visible by this macro. For more information about the supported path
  expressions, please check `get_and_update_in/2` docs.

  ## Examples

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> pop_in(users["john"][:age])
      {27, %{"john" => %{}, "meg" => %{age: 23}}}

      iex> users = %{john: %{age: 27}, meg: %{age: 23}}
      iex> pop_in(users.john[:age])
      {27, %{john: %{}, meg: %{age: 23}}}

  In case any entry returns `nil`, its key will be removed
  and the deletion will be considered a success.

  """
  defmacro pop_in(path) do
    (
      {[h | t], _} = unnest(path, [], true, "pop_in/1")
      nest_pop_in(:map, h, t)
    )
  end

  @doc ~S"""
  Puts a value in a nested structure via the given `path`.

  This is similar to `put_in/3`, except the path is extracted via
  a macro rather than passing a list. For example:

      put_in(opts[:foo][:bar], :baz)

  Is equivalent to:

      put_in(opts, [:foo, :bar], :baz)

  This also works with nested structs and the `struct.path.to.value` way to specify
  paths:

      put_in(struct.foo.bar, :baz)

  Note that in order for this macro to work, the complete path must always
  be visible by this macro. For more information about the supported path
  expressions, please check `get_and_update_in/2` docs.

  ## Examples

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> put_in(users["john"][:age], 28)
      %{"john" => %{age: 28}, "meg" => %{age: 23}}

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> put_in(users["john"].age, 28)
      %{"john" => %{age: 28}, "meg" => %{age: 23}}


  """
  defmacro put_in(path, value) do
    case(unnest(path, [], true, "put_in/2")) do
      {[h | t], true} ->
        nest_update_in(h, t, {:fn, [], [{:->, [], [[{:_, [], Kernel}], value]}]})
      {[h | t], false} ->
        expr = nest_get_and_update_in(h, t, {:fn, [], [{:->, [], [[{:_, [], Kernel}], {nil, value}]}]})
        {{:., [], [:erlang, :element]}, [], [2, expr]}
    end
  end

  @doc ~S"""
  Raises an exception.

  If `message` is a string, it raises a `RuntimeError` exception with it.

  If `message` is an atom, it just calls `raise/2` with the atom as the first
  argument and `[]` as the second one.

  If `message` is an exception struct, it is raised as is.

  If `message` is anything else, `raise` will fail with an `ArgumentError`
  exception.

  ## Examples

      iex> raise "oops"
      ** (RuntimeError) oops

      try do
        1 + :foo
      rescue
        x in [ArithmeticError] ->
          IO.puts("that was expected")
          raise x
      end


  """
  defmacro raise(message) do
    (
      message = case(not(is_binary(message)) and bootstrapped?(Macro)) do
        true ->
          Macro.expand(message, __CALLER__)
        false ->
          message
      end
      erlang_error = case(:erlang.system_info(:otp_release) >= '24') do
        true ->
          fn x -> {{:., [], [:erlang, :error]}, [], [x, :none, [error_info: {:%{}, [], [module: {:__aliases__, [alias: false], [:Exception]}]}]]} end
        false ->
          fn x -> {{:., [], [:erlang, :error]}, [], [x]} end
      end
      case(message) do
        message when is_binary(message) ->
          erlang_error.({{:., [], [{:__aliases__, [alias: false], [:RuntimeError]}, :exception]}, [], [message]})
        {:<<>>, _, _} = message ->
          erlang_error.({{:., [], [{:__aliases__, [alias: false], [:RuntimeError]}, :exception]}, [], [message]})
        alias when is_atom(alias) ->
          erlang_error.({{:., [], [alias, :exception]}, [], [[]]})
        _ ->
          erlang_error.({{:., [], [{:__aliases__, [alias: false], [:Kernel, :Utils]}, :raise]}, [], [message]})
      end
    )
  end

  @doc ~S"""
  Raises an exception.

  Calls the `exception/1` function on the given argument (which has to be a
  module name like `ArgumentError` or `RuntimeError`) passing `attributes`
  in order to retrieve the exception struct.

  Any module that contains a call to the `defexception/1` macro automatically
  implements the `c:Exception.exception/1` callback expected by `raise/2`.
  For more information, see `defexception/1`.

  ## Examples

      iex> raise(ArgumentError, "Sample")
      ** (ArgumentError) Sample


  """
  defmacro raise(exception, attributes) do
    {{:., [], [:erlang, :error]}, [], [{{:., [], [exception, :exception]}, [], [attributes]}]}
  end

  @doc ~S"""
  Raises an exception preserving a previous stacktrace.

  Works like `raise/1` but does not generate a new stacktrace.

  Note that `__STACKTRACE__` can be used inside catch/rescue
  to retrieve the current stacktrace.

  ## Examples

      try do
        raise "oops"
      rescue
        exception ->
          reraise exception, __STACKTRACE__
      end


  """
  defmacro reraise(message, stacktrace) do
    case(Macro.expand(message, __CALLER__)) do
      message when is_binary(message) ->
        {{:., [], [:erlang, :error]}, [], [{{:., [], [:erlang, :raise]}, [], [:error, {{:., [], [{:__aliases__, [alias: false], [:RuntimeError]}, :exception]}, [], [message]}, stacktrace]}]}
      {:<<>>, _, _} = message ->
        {{:., [], [:erlang, :error]}, [], [{{:., [], [:erlang, :raise]}, [], [:error, {{:., [], [{:__aliases__, [alias: false], [:RuntimeError]}, :exception]}, [], [message]}, stacktrace]}]}
      alias when is_atom(alias) ->
        {{:., [], [:erlang, :error]}, [], [{{:., [], [:erlang, :raise]}, [], [:error, {{:., [], [alias, :exception]}, [], [[]]}, stacktrace]}]}
      message ->
        {{:., [], [:erlang, :error]}, [], [{{:., [], [:erlang, :raise]}, [], [:error, {{:., [], [{:__aliases__, [alias: false], [:Kernel, :Utils]}, :raise]}, [], [message]}, stacktrace]}]}
    end
  end

  @doc ~S"""
  Raises an exception preserving a previous stacktrace.

  `reraise/3` works like `reraise/2`, except it passes arguments to the
  `exception/1` function as explained in `raise/2`.

  ## Examples

      try do
        raise "oops"
      rescue
        exception ->
          reraise WrapperError, [exception: exception], __STACKTRACE__
      end


  """
  defmacro reraise(exception, attributes, stacktrace) do
    {{:., [], [:erlang, :raise]}, [], [:error, {{:., [], [exception, :exception]}, [], [attributes]}, stacktrace]}
  end

  @doc ~S"""
  Handles the sigil `~C` for charlists.

  It returns a charlist without interpolations and without escape
  characters, except for the escaping of the closing sigil character
  itself.

  ## Examples

      iex> ~C(foo)
      'foo'

      iex> ~C(f#{o}o)
      'f\#{o}o'


  """
  defmacro sigil_C({:<<>>, _meta, [string]}, []) when is_binary(string) do
    String.to_charlist(string)
  end

  @doc ~S"""
  Handles the sigil `~D` for dates.

  By default, this sigil uses the built-in `Calendar.ISO`, which
  requires dates to be written in the ISO8601 format:

      ~D[yyyy-mm-dd]

  such as:

      ~D[2015-01-13]

  If you are using alternative calendars, any representation can
  be used as long as you follow the representation by a single space
  and the calendar name:

      ~D[SOME-REPRESENTATION My.Alternative.Calendar]

  The lower case `~d` variant does not exist as interpolation
  and escape characters are not useful for date sigils.

  More information on dates can be found in the `Date` module.

  ## Examples

      iex> ~D[2015-01-13]
      ~D[2015-01-13]


  """
  defmacro sigil_D({:<<>>, _, [string]}, []) do
    (
      {{:ok, {year, month, day}}, calendar} = parse_with_calendar!(string, :parse_date, "Date")
      to_calendar_struct(Date, calendar: calendar, year: year, month: month, day: day)
    )
  end

  @doc ~S"""
  Handles the sigil `~N` for naive date times.

  By default, this sigil uses the built-in `Calendar.ISO`, which
  requires naive date times to be written in the ISO8601 format:

      ~N[yyyy-mm-dd hh:mm:ss]
      ~N[yyyy-mm-dd hh:mm:ss.ssssss]
      ~N[yyyy-mm-ddThh:mm:ss.ssssss]

  such as:

      ~N[2015-01-13 13:00:07]
      ~N[2015-01-13T13:00:07.123]

  If you are using alternative calendars, any representation can
  be used as long as you follow the representation by a single space
  and the calendar name:

      ~N[SOME-REPRESENTATION My.Alternative.Calendar]

  The lower case `~n` variant does not exist as interpolation
  and escape characters are not useful for date time sigils.

  More information on naive date times can be found in the
  `NaiveDateTime` module.

  ## Examples

      iex> ~N[2015-01-13 13:00:07]
      ~N[2015-01-13 13:00:07]
      iex> ~N[2015-01-13T13:00:07.001]
      ~N[2015-01-13 13:00:07.001]


  """
  defmacro sigil_N({:<<>>, _, [string]}, []) do
    (
      {{:ok, {year, month, day, hour, minute, second, microsecond}}, calendar} = parse_with_calendar!(string, :parse_naive_datetime, "NaiveDateTime")
      to_calendar_struct(NaiveDateTime, calendar: calendar, year: year, month: month, day: day, hour: hour, minute: minute, second: second, microsecond: microsecond)
    )
  end

  @doc ~S"""
  Handles the sigil `~R` for regular expressions.

  It returns a regular expression pattern without interpolations and
  without escape characters. Note it still supports escape of Regex
  tokens (such as escaping `+` or `?`) and it also requires you to
  escape the closing sigil character itself if it appears on the Regex.

  More information on regexes can be found in the `Regex` module.

  ## Examples

      iex> Regex.match?(~R(f#{1,3}o), "f#o")
      true


  """
  defmacro sigil_R({:<<>>, _meta, [string]}, options) when is_binary(string) do
    (
      regex = Regex.compile!(string, :binary.list_to_bin(options))
      Macro.escape(regex)
    )
  end

  @doc ~S"""
  Handles the sigil `~S` for strings.

  It returns a string without interpolations and without escape
  characters, except for the escaping of the closing sigil character
  itself.

  ## Examples

      iex> ~S(foo)
      "foo"
      iex> ~S(f#{o}o)
      "f\#{o}o"
      iex> ~S(\o/)
      "\\o/"

  However, if you want to re-use the sigil character itself on
  the string, you need to escape it:

      iex> ~S((\))
      "()"


  """
  defmacro sigil_S({:<<>>, _, [binary]}, []) when is_binary(binary) do
    binary
  end

  @doc ~S"""
  Handles the sigil `~T` for times.

  By default, this sigil uses the built-in `Calendar.ISO`, which
  requires times to be written in the ISO8601 format:

      ~T[hh:mm:ss]
      ~T[hh:mm:ss.ssssss]

  such as:

      ~T[13:00:07]
      ~T[13:00:07.123]

  If you are using alternative calendars, any representation can
  be used as long as you follow the representation by a single space
  and the calendar name:

      ~T[SOME-REPRESENTATION My.Alternative.Calendar]

  The lower case `~t` variant does not exist as interpolation
  and escape characters are not useful for time sigils.

  More information on times can be found in the `Time` module.

  ## Examples

      iex> ~T[13:00:07]
      ~T[13:00:07]
      iex> ~T[13:00:07.001]
      ~T[13:00:07.001]


  """
  defmacro sigil_T({:<<>>, _, [string]}, []) do
    (
      {{:ok, {hour, minute, second, microsecond}}, calendar} = parse_with_calendar!(string, :parse_time, "Time")
      to_calendar_struct(Time, calendar: calendar, hour: hour, minute: minute, second: second, microsecond: microsecond)
    )
  end

  @doc ~S"""
  Handles the sigil `~U` to create a UTC `DateTime`.

  By default, this sigil uses the built-in `Calendar.ISO`, which
  requires UTC date times to be written in the ISO8601 format:

      ~U[yyyy-mm-dd hh:mm:ssZ]
      ~U[yyyy-mm-dd hh:mm:ss.ssssssZ]
      ~U[yyyy-mm-ddThh:mm:ss.ssssss+00:00]

  such as:

      ~U[2015-01-13 13:00:07Z]
      ~U[2015-01-13T13:00:07.123+00:00]

  If you are using alternative calendars, any representation can
  be used as long as you follow the representation by a single space
  and the calendar name:

      ~U[SOME-REPRESENTATION My.Alternative.Calendar]

  The given `datetime_string` must include "Z" or "00:00" offset
  which marks it as UTC, otherwise an error is raised.

  The lower case `~u` variant does not exist as interpolation
  and escape characters are not useful for date time sigils.

  More information on date times can be found in the `DateTime` module.

  ## Examples

      iex> ~U[2015-01-13 13:00:07Z]
      ~U[2015-01-13 13:00:07Z]
      iex> ~U[2015-01-13T13:00:07.001+00:00]
      ~U[2015-01-13 13:00:07.001Z]


  """
  defmacro sigil_U({:<<>>, _, [string]}, []) do
    (
      {{:ok, {year, month, day, hour, minute, second, microsecond}, offset}, calendar} = parse_with_calendar!(string, :parse_utc_datetime, "UTC DateTime")
      case(offset != 0) do
        false ->
          nil
        true ->
          raise(ArgumentError, <<"cannot parse "::binary(), Kernel.inspect(string)::binary(), " as UTC DateTime for "::binary(), Kernel.inspect(calendar)::binary(), ", reason: :non_utc_offset"::binary()>>)
      end
      to_calendar_struct(DateTime, calendar: calendar, year: year, month: month, day: day, hour: hour, minute: minute, second: second, microsecond: microsecond, time_zone: "Etc/UTC", zone_abbr: "UTC", utc_offset: 0, std_offset: 0)
    )
  end

  @doc ~S"""
  Handles the sigil `~W` for list of words.

  It returns a list of "words" split by whitespace without interpolations
  and without escape characters, except for the escaping of the closing
  sigil character itself.

  ## Modifiers

    * `s`: words in the list are strings (default)
    * `a`: words in the list are atoms
    * `c`: words in the list are charlists

  ## Examples

      iex> ~W(foo #{bar} baz)
      ["foo", "\#{bar}", "baz"]


  """
  defmacro sigil_W({:<<>>, _meta, [string]}, modifiers) when is_binary(string) do
    split_words(string, modifiers, __CALLER__)
  end

  @doc ~S"""
  Handles the sigil `~c` for charlists.

  It returns a charlist as if it was a single quoted string, unescaping
  characters and replacing interpolations.

  ## Examples

      iex> ~c(foo)
      'foo'

      iex> ~c(f#{:o}o)
      'foo'

      iex> ~c(f\#{:o}o)
      'f\#{:o}o'


  """
  defmacro sigil_c({:<<>>, _meta, [string]}, []) when is_binary(string) do
    String.to_charlist(:elixir_interpolation.unescape_string(string))
  end

  defmacro sigil_c({:<<>>, _meta, pieces}, []) do
    {{:., [], [{:__aliases__, [alias: false], [:List]}, :to_charlist]}, [], [unescape_list_tokens(pieces)]}
  end

  @doc ~S"""
  Handles the sigil `~r` for regular expressions.

  It returns a regular expression pattern, unescaping characters and replacing
  interpolations.

  More information on regular expressions can be found in the `Regex` module.

  ## Examples

      iex> Regex.match?(~r/foo/, "foo")
      true

      iex> Regex.match?(~r/a#{:b}c/, "abc")
      true

  While the `~r` sigil allows parens and brackets to be used as delimiters,
  it is preferred to use `"` or `/` to avoid escaping conflicts with reserved
  regex characters.

  """
  defmacro sigil_r({:<<>>, _meta, [string]}, options) when is_binary(string) do
    (
      binary = :elixir_interpolation.unescape_string(string, &Regex.unescape_map/1)
      regex = Regex.compile!(binary, :binary.list_to_bin(options))
      Macro.escape(regex)
    )
  end

  defmacro sigil_r({:<<>>, meta, pieces}, options) do
    (
      binary = {:<<>>, meta, unescape_tokens(pieces, &Regex.unescape_map/1)}
      {{:., [], [{:__aliases__, [alias: false], [:Regex]}, :compile!]}, [], [binary, :binary.list_to_bin(options)]}
    )
  end

  @doc ~S"""
  Handles the sigil `~s` for strings.

  It returns a string as if it was a double quoted string, unescaping characters
  and replacing interpolations.

  ## Examples

      iex> ~s(foo)
      "foo"

      iex> ~s(f#{:o}o)
      "foo"

      iex> ~s(f\#{:o}o)
      "f\#{:o}o"


  """
  defmacro sigil_s({:<<>>, _, [piece]}, []) when is_binary(piece) do
    :elixir_interpolation.unescape_string(piece)
  end

  defmacro sigil_s({:<<>>, line, pieces}, []) do
    {:<<>>, line, unescape_tokens(pieces)}
  end

  @doc ~S"""
  Handles the sigil `~w` for list of words.

  It returns a list of "words" split by whitespace. Character unescaping and
  interpolation happens for each word.

  ## Modifiers

    * `s`: words in the list are strings (default)
    * `a`: words in the list are atoms
    * `c`: words in the list are charlists

  ## Examples

      iex> ~w(foo #{:bar} baz)
      ["foo", "bar", "baz"]

      iex> ~w(foo #{" bar baz "})
      ["foo", "bar", "baz"]

      iex> ~w(--source test/enum_test.exs)
      ["--source", "test/enum_test.exs"]

      iex> ~w(foo bar baz)a
      [:foo, :bar, :baz]

      iex> ~w(foo bar baz)c
      ['foo', 'bar', 'baz']


  """
  defmacro sigil_w({:<<>>, _meta, [string]}, modifiers) when is_binary(string) do
    split_words(:elixir_interpolation.unescape_string(string), modifiers, __CALLER__)
  end

  defmacro sigil_w({:<<>>, meta, pieces}, modifiers) do
    (
      binary = {:<<>>, meta, unescape_tokens(pieces)}
      split_words(binary, modifiers, __CALLER__)
    )
  end

  @doc ~S"""
  Pipes `value` to the given `fun` and returns the `value` itself.

  Useful for running synchronous side effects in a pipeline.

  ## Examples

      iex> tap(1, fn x -> x + 1 end)
      1

  Most commonly, this is used in pipelines. For example,
  let's suppose you want to inspect part of a data structure.
  You could write:

      %{a: 1}
      |> Map.update!(:a, & &1 + 2)
      |> tap(&IO.inspect(&1.a))
      |> Map.update!(:a, & &1 * 2)


  """
  defmacro tap(value, fun) do
    {:__block__, [], [{:=, [], [{:fun, [line: 1199], Kernel}, fun]}, {:=, [], [{:value, [line: 1199], Kernel}, value]}, {:__block__, [], [{:=, [], [{:_, [], Kernel}, {{:., [], [{:fun, [], Kernel}]}, [], [{:value, [], Kernel}]}]}, {:value, [], Kernel}]}]}
  end

  @doc ~S"""
  Pipes `value` into the given `fun`.

  In other words, it invokes `fun` with `value` as argument.
  This is most commonly used in pipelines, allowing you
  to pipe a value to a function outside of its first argument.

  ### Examples

      iex> 1 |> then(fn x -> x * 2 end)
      2

      iex> 1 |> then(fn x -> Enum.drop(["a", "b", "c"], x) end)
      ["b", "c"]

  """
  defmacro then(value, fun) do
    {{:., [], [fun]}, [], [value]}
  end

  @doc false
  defmacro to_char_list(arg) do
    (
      IO.warn("Kernel.to_char_list/1 is deprecated, use Kernel.to_charlist/1 instead", Macro.Env.stacktrace(__CALLER__))
      {{:., [], [{:__aliases__, [alias: false], [:Kernel]}, :to_charlist]}, [], [arg]}
    )
  end

  @doc ~S"""
  Converts the given term to a charlist according to the `List.Chars` protocol.

  ## Examples

      iex> to_charlist(:foo)
      'foo'


  """
  defmacro to_charlist(term) do
    {{:., [], [{:__aliases__, [alias: false], [:List, :Chars]}, :to_charlist]}, [], [term]}
  end

  @doc ~S"""
  Converts the argument to a string according to the
  `String.Chars` protocol.

  This is the function invoked when there is string interpolation.

  ## Examples

      iex> to_string(:foo)
      "foo"


  """
  defmacro to_string(term) do
    {{:., [], [String.Chars, :to_string]}, [], [term]}
  end

  @doc ~S"""
  Provides an `unless` macro.

  This macro evaluates and returns the `do` block passed in as the second
  argument if `condition` evaluates to a falsy value (`false` or `nil`).
  Otherwise, it returns the value of the `else` block if present or `nil` if not.

  See also `if/2`.

  ## Examples

      iex> unless(Enum.empty?([]), do: "Hello")
      nil

      iex> unless(Enum.empty?([1, 2, 3]), do: "Hello")
      "Hello"

      iex> unless Enum.sum([2, 2]) == 5 do
      ...>   "Math still works"
      ...> else
      ...>   "Math is broken"
      ...> end
      "Math still works"


  """
  defmacro unless(condition, clauses) do
    build_unless(condition, clauses)
  end

  @doc ~S"""
  Updates a nested structure via the given `path`.

  This is similar to `update_in/3`, except the path is extracted via
  a macro rather than passing a list. For example:

      update_in(opts[:foo][:bar], &(&1 + 1))

  Is equivalent to:

      update_in(opts, [:foo, :bar], &(&1 + 1))

  This also works with nested structs and the `struct.path.to.value` way to specify
  paths:

      update_in(struct.foo.bar, &(&1 + 1))

  Note that in order for this macro to work, the complete path must always
  be visible by this macro. For more information about the supported path
  expressions, please check `get_and_update_in/2` docs.

  ## Examples

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> update_in(users["john"][:age], &(&1 + 1))
      %{"john" => %{age: 28}, "meg" => %{age: 23}}

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> update_in(users["john"].age, &(&1 + 1))
      %{"john" => %{age: 28}, "meg" => %{age: 23}}


  """
  defmacro update_in(path, fun) do
    case(unnest(path, [], true, "update_in/2")) do
      {[h | t], true} ->
        nest_update_in(h, t, fun)
      {[h | t], false} ->
        expr = nest_get_and_update_in(h, t, {:fn, [], [{:->, [], [[{:x, [], Kernel}], {nil, {{:., [], [fun]}, [], [{:x, [], Kernel}]}}]}]})
        {{:., [], [:erlang, :element]}, [], [2, expr]}
    end
  end

  defmacro use(module) do
    super(module, [])
  end

  @doc ~S"""
  Uses the given module in the current context.

  When calling:

      use MyModule, some: :options

  the `__using__/1` macro from the `MyModule` module is invoked with the second
  argument passed to `use` as its argument. Since `__using__/1` is a macro, all
  the usual macro rules apply, and its return value should be quoted code
  that is then inserted where `use/2` is called.

  ## Examples

  For example, to write test cases using the `ExUnit` framework provided
  with Elixir, a developer should `use` the `ExUnit.Case` module:

      defmodule AssertionTest do
        use ExUnit.Case, async: true

        test "always pass" do
          assert true
        end
      end

  In this example, Elixir will call the `__using__/1` macro in the
  `ExUnit.Case` module with the keyword list `[async: true]` as its
  argument.

  In other words, `use/2` translates to:

      defmodule AssertionTest do
        require ExUnit.Case
        ExUnit.Case.__using__(async: true)

        test "always pass" do
          assert true
        end
      end

  where `ExUnit.Case` defines the `__using__/1` macro:

      defmodule ExUnit.Case do
        defmacro __using__(opts) do
          # do something with opts
          quote do
            # return some code to inject in the caller
          end
        end
      end

  ## Best practices

  `__using__/1` is typically used when there is a need to set some state (via
  module attributes) or callbacks (like `@before_compile`, see the documentation
  for `Module` for more information) into the caller.

  `__using__/1` may also be used to alias, require, or import functionality
  from different modules:

      defmodule MyModule do
        defmacro __using__(_opts) do
          quote do
            import MyModule.Foo
            import MyModule.Bar
            import MyModule.Baz

            alias MyModule.Repo
          end
        end
      end

  However, do not provide `__using__/1` if all it does is to import,
  alias or require the module itself. For example, avoid this:

      defmodule MyModule do
        defmacro __using__(_opts) do
          quote do
            import MyModule
          end
        end
      end

  In such cases, developers should instead import or alias the module
  directly, so that they can customize those as they wish,
  without the indirection behind `use/2`.

  Finally, developers should also avoid defining functions inside
  the `__using__/1` callback, unless those functions are the default
  implementation of a previously defined `@callback` or are functions
  meant to be overridden (see `defoverridable/1`). Even in these cases,
  defining functions should be seen as a "last resort".

  In case you want to provide some existing functionality to the user module,
  please define it in a module which will be imported accordingly; for example,
  `ExUnit.Case` doesn't define the `test/3` macro in the module that calls
  `use ExUnit.Case`, but it defines `ExUnit.Case.test/3` and just imports that
  into the caller when used.

  """
  defmacro use(module, opts) do
    (
      calls = Enum.map(expand_aliases(module, __CALLER__), fn
       expanded when is_atom(expanded) ->
          {:__block__, [], [{:require, [context: Kernel], [expanded]}, {{:., [], [expanded, :__using__]}, [], [opts]}]}
        _otherwise ->
          raise(ArgumentError, <<"invalid arguments for use, "::binary(), "expected a compile time atom or alias, got: "::binary(), Macro.to_string(module)::binary()>>)
      end)
      {:__block__, [], :elixir_quote.list(calls, [])}
    )
  end

  defmacro var!(x0) do
    super(x0, nil)
  end

  @doc ~S"""
  Marks that the given variable should not be hygienized.

  This macro expects a variable and it is typically invoked
  inside `Kernel.SpecialForms.quote/2` to mark that a variable
  should not be hygienized. See `Kernel.SpecialForms.quote/2`
  for more information.

  ## Examples

      iex> Kernel.var!(example) = 1
      1
      iex> Kernel.var!(example)
      1


  """
  defmacro var!({name, meta, atom}, context) when is_atom(name) and is_atom(atom) do
    (
      meta = :lists.keydelete(:counter, 1, meta)
      meta = :lists.keystore(:if_undefined, 1, meta, {:if_undefined, :raise})
      case(Macro.expand(context, __CALLER__)) do
        context when is_atom(context) ->
          {name, meta, context}
        other ->
          raise(ArgumentError, <<"expected var! context to expand to an atom, got: "::binary(), Macro.to_string(other)::binary()>>)
      end
    )
  end

  defmacro var!(other, _context) do
    raise(ArgumentError, <<"expected a variable to be given to var!, got: "::binary(), Macro.to_string(other)::binary()>>)
  end

  @doc ~S"""
  Pipe operator.

  This operator introduces the expression on the left-hand side as
  the first argument to the function call on the right-hand side.

  ## Examples

      iex> [1, [2], 3] |> List.flatten()
      [1, 2, 3]

  The example above is the same as calling `List.flatten([1, [2], 3])`.

  The `|>` operator is mostly useful when there is a desire to execute a series
  of operations resembling a pipeline:

      iex> [1, [2], 3] |> List.flatten() |> Enum.map(fn x -> x * 2 end)
      [2, 4, 6]

  In the example above, the list `[1, [2], 3]` is passed as the first argument
  to the `List.flatten/1` function, then the flattened list is passed as the
  first argument to the `Enum.map/2` function which doubles each element of the
  list.

  In other words, the expression above simply translates to:

      Enum.map(List.flatten([1, [2], 3]), fn x -> x * 2 end)

  ## Pitfalls

  There are two common pitfalls when using the pipe operator.

  The first one is related to operator precedence. For example,
  the following expression:

      String.graphemes "Hello" |> Enum.reverse

  Translates to:

      String.graphemes("Hello" |> Enum.reverse())

  which results in an error as the `Enumerable` protocol is not defined
  for binaries. Adding explicit parentheses resolves the ambiguity:

      String.graphemes("Hello") |> Enum.reverse()

  Or, even better:

      "Hello" |> String.graphemes() |> Enum.reverse()

  The second limitation is that Elixir always pipes to a function
  call. Therefore, to pipe into an anonymous function, you need to
  invoke it:

      some_fun = &Regex.replace(~r/l/, &1, "L")
      "Hello" |> some_fun.()

  Alternatively, you can use `then/2` for the same effect:

      some_fun = &Regex.replace(~r/l/, &1, "L")
      "Hello" |> then(some_fun)

  `then/2` is most commonly used when you want to pipe to a function
  but the value is expected outside of the first argument, such as
  above. By replacing `some_fun` by its value, we get:

      "Hello" |> then(&Regex.replace(~r/l/,&1, "L"))


  """
  defmacro left |> right do
    (
      [{h, _} | t] = Macro.unpipe({:|>, [], [left, right]})
      fun = fn {x, pos}, acc -> Macro.pipe(acc, x, pos) end
      :lists.foldl(fun, h, t)
    )
  end

  @doc ~S"""
  Boolean "or" operator.

  Provides a short-circuit operator that evaluates and returns the second
  expression only if the first one does not evaluate to a truthy value (that is,
  it is either `nil` or `false`). Returns the first expression otherwise.

  Not allowed in guard clauses.

  ## Examples

      iex> Enum.empty?([1]) || Enum.empty?([1])
      false

      iex> List.first([]) || true
      true

      iex> Enum.empty?([1]) || 1
      1

      iex> Enum.empty?([]) || throw(:bad)
      true

  Note that, unlike `or/2`, this operator accepts any expression
  as the first argument, not only booleans.

  """
  defmacro left || right do
    (
      assert_no_match_or_guard_scope(__CALLER__.context(), "||")
      {:case, [], [left, [do: [{:->, [], [[{:when, [], [{:x, [], Kernel}, {{:., [], [Kernel, :in]}, [], [{:x, [], Kernel}, [false, nil]]}]}], right]}, {:->, [], [[{:x, [], Kernel}], {:x, [], Kernel}]}]]]}
    )
  end

  # Functions

  @doc ~S"""
  Not equal to operator.

  Returns `true` if the two terms are not equal.

  This operator considers 1 and 1.0 to be equal. For match
  comparison, use `!==/2` instead.

  All terms in Elixir can be compared with each other.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> 1 != 2
      true

      iex> 1 != 1.0
      false


  """
  def left != right do
    left != right
  end

  @doc ~S"""
  Strictly not equal to operator.

  Returns `true` if the two terms are not exactly equal.
  See `===/2` for a definition of what is considered "exactly equal".

  All terms in Elixir can be compared with each other.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> 1 !== 2
      true

      iex> 1 !== 1.0
      true


  """
  def left !== right do
    left !== right
  end

  @doc ~S"""
  Arithmetic multiplication operator.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> 1 * 2
      2


  """
  def left * right do
    left * right
  end

  @doc ~S"""
  Power operator.

  It expects two numbers are input. If the left-hand side is an integer
  and the right-hand side is more than or equal to 0, then the result is
  integer. Otherwise it returns a float.

  ## Examples

      iex> 2 ** 2
      4
      iex> 2 ** -4
      0.0625

      iex> 2.0 ** 2
      4.0
      iex> 2 ** 2.0
      4.0


  """
  def base ** exponent when is_integer(base) and is_integer(exponent) and exponent >= 0 do
    integer_pow(base, 1, exponent)
  end

  def base ** exponent when :erlang.is_number(base) and :erlang.is_number(exponent) do
    :math.pow(base, exponent)
  end

  @doc ~S"""
  Arithmetic positive unary operator.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> +1
      1


  """
  def (+value) do
    :erlang.+(value)
  end

  @doc ~S"""
  Arithmetic addition operator.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> 1 + 2
      3


  """
  def left + right do
    left + right
  end

  @doc ~S"""
  List concatenation operator. Concatenates a proper list and a term, returning a list.

  The complexity of `a ++ b` is proportional to `length(a)`, so avoid repeatedly
  appending to lists of arbitrary length, for example, `list ++ [element]`.
  Instead, consider prepending via `[element | rest]` and then reversing.

  If the `right` operand is not a proper list, it returns an improper list.
  If the `left` operand is not a proper list, it raises `ArgumentError`.

  Inlined by the compiler.

  ## Examples

      iex> [1] ++ [2, 3]
      [1, 2, 3]

      iex> 'foo' ++ 'bar'
      'foobar'

      # returns an improper list
      iex> [1] ++ 2
      [1 | 2]

      # returns a proper list
      iex> [1] ++ [2]
      [1, 2]

      # improper list on the right will return an improper list
      iex> [1] ++ [2 | 3]
      [1, 2 | 3]


  """
  def left ++ right do
    left ++ right
  end

  @doc ~S"""
  Arithmetic negative unary operator.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> -2
      -2


  """
  def (-value) do
    :erlang.-(value)
  end

  @doc ~S"""
  Arithmetic subtraction operator.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> 1 - 2
      -1


  """
  def left - right do
    left - right
  end

  @doc ~S"""
  List subtraction operator. Removes the first occurrence of an element
  on the left list for each element on the right.

  This function is optimized so the complexity of `a -- b` is proportional
  to `length(a) * log(length(b))`. See also the [Erlang efficiency
  guide](https://erlang.org/doc/efficiency_guide/retired_myths.html).

  Inlined by the compiler.

  ## Examples

      iex> [1, 2, 3] -- [1, 2]
      [3]

      iex> [1, 2, 3, 2, 1] -- [1, 2, 2]
      [3, 1]

  The `--/2` operator is right associative, meaning:

      iex> [1, 2, 3] -- [2] -- [3]
      [1, 3]

  As it is equivalent to:

      iex> [1, 2, 3] -- ([2] -- [3])
      [1, 3]


  """
  def left -- right do
    left -- right
  end

  @doc ~S"""
  Arithmetic division operator.

  The result is always a float. Use `div/2` and `rem/2` if you want
  an integer division or the remainder.

  Raises `ArithmeticError` if `right` is 0 or 0.0.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      1 / 2
      #=> 0.5

      -3.0 / 2.0
      #=> -1.5

      5 / 1
      #=> 5.0

      7 / 0
      ** (ArithmeticError) bad argument in arithmetic expression


  """
  def left / right do
    left / right
  end

  @doc ~S"""
  Less-than operator.

  Returns `true` if `left` is less than `right`.

  This performs a structural comparison where all Elixir
  terms can be compared with each other. See the ["Structural
  comparison" section](#module-structural-comparison) section
  for more information.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> 1 < 2
      true


  """
  def left < right do
    left < right
  end

  @doc ~S"""
  Less-than or equal to operator.

  Returns `true` if `left` is less than or equal to `right`.

  This performs a structural comparison where all Elixir
  terms can be compared with each other. See the ["Structural
  comparison" section](#module-structural-comparison) section
  for more information.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> 1 <= 2
      true


  """
  def left <= right do
    left <= right
  end

  @doc ~S"""
  Equal to operator. Returns `true` if the two terms are equal.

  This operator considers 1 and 1.0 to be equal. For stricter
  semantics, use `===/2` instead.

  All terms in Elixir can be compared with each other.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> 1 == 2
      false

      iex> 1 == 1.0
      true


  """
  def left == right do
    left == right
  end

  @doc ~S"""
  Strictly equal to operator.

  Returns `true` if the two terms are exactly equal.

  The terms are only considered to be exactly equal if they
  have the same value and are of the same type. For example,
  `1 == 1.0` returns `true`, but since they are of different
  types, `1 === 1.0` returns `false`.

  All terms in Elixir can be compared with each other.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> 1 === 2
      false

      iex> 1 === 1.0
      false


  """
  def left === right do
    left === right
  end

  @doc ~S"""
  Text-based match operator. Matches the term on the `left`
  against the regular expression or string on the `right`.

  If `right` is a regular expression, returns `true` if `left` matches right.

  If `right` is a string, returns `true` if `left` contains `right`.

  ## Examples

      iex> "abcd" =~ ~r/c(d)/
      true

      iex> "abcd" =~ ~r/e/
      false

      iex> "abcd" =~ ~r//
      true

      iex> "abcd" =~ "bc"
      true

      iex> "abcd" =~ "ad"
      false

      iex> "abcd" =~ "abcd"
      true

      iex> "abcd" =~ ""
      true

  For more information about regular expressions, please check the `Regex` module.

  """
  def left =~ "" when is_binary(left) do
    true
  end

  def left =~ right when is_binary(left) and is_binary(right) do
    :binary.match(left, right) != :nomatch
  end

  def left =~ right when is_binary(left) do
    Regex.match?(right, left)
  end

  @doc ~S"""
  Greater-than operator.

  Returns `true` if `left` is more than `right`.

  This performs a structural comparison where all Elixir
  terms can be compared with each other. See the ["Structural
  comparison" section](#module-structural-comparison) section
  for more information.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> 1 > 2
      false


  """
  def left > right do
    left > right
  end

  @doc ~S"""
  Greater-than or equal to operator.

  Returns `true` if `left` is more than or equal to `right`.

  This performs a structural comparison where all Elixir
  terms can be compared with each other. See the ["Structural
  comparison" section](#module-structural-comparison) section
  for more information.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> 1 >= 2
      false


  """
  def left >= right do
    left >= right
  end

  def __info__(arg1) do
    # body not decompiled
  end

  @doc ~S"""
  Returns an integer or float which is the arithmetical absolute value of `number`.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> abs(-3.33)
      3.33

      iex> abs(-3)
      3


  """
  def abs(number) do
    :erlang.abs(number)
  end

  @doc ~S"""
  Invokes the given anonymous function `fun` with the list of
  arguments `args`.

  If the number of arguments is known at compile time, prefer
  `fun.(arg_1, arg_2, ..., arg_n)` as it is clearer than
  `apply(fun, [arg_1, arg_2, ..., arg_n])`.

  Inlined by the compiler.

  ## Examples

      iex> apply(fn x -> x * 2 end, [2])
      4


  """
  def apply(fun, args) do
    apply(fun, args)
  end

  @doc ~S"""
  Invokes the given function from `module` with the list of
  arguments `args`.

  `apply/3` is used to invoke functions where the module, function
  name or arguments are defined dynamically at runtime. For this
  reason, you can't invoke macros using `apply/3`, only functions.

  If the number of arguments and the function name are known at compile time,
  prefer `module.function(arg_1, arg_2, ..., arg_n)` as it is clearer than
  `apply(module, :function, [arg_1, arg_2, ..., arg_n])`.

  `apply/3` cannot be used to call private functions.

  Inlined by the compiler.

  ## Examples

      iex> apply(Enum, :reverse, [[1, 2, 3]])
      [3, 2, 1]


  """
  def apply(module, function_name, args) do
    apply(module, function_name, args)
  end

  @doc ~S"""
  Extracts the part of the binary starting at `start` with length `length`.
  Binaries are zero-indexed.

  If `start` or `length` reference in any way outside the binary, an
  `ArgumentError` exception is raised.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> binary_part("foo", 1, 2)
      "oo"

  A negative `length` can be used to extract bytes that come *before* the byte
  at `start`:

      iex> binary_part("Hello", 5, -3)
      "llo"

  An `ArgumentError` is raised when the length is outside of the binary:

      binary_part("Hello", 0, 10)
      ** (ArgumentError) argument error


  """
  def binary_part(binary, start, length) do
    binary_part(binary, start, length)
  end

  @doc ~S"""
  Returns an integer which is the size in bits of `bitstring`.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> bit_size(<<433::16, 3::3>>)
      19

      iex> bit_size(<<1, 2, 3>>)
      24


  """
  def bit_size(bitstring) do
    :erlang.bit_size(bitstring)
  end

  @doc ~S"""
  Returns the number of bytes needed to contain `bitstring`.

  That is, if the number of bits in `bitstring` is not divisible by 8, the
  resulting number of bytes will be rounded up (by excess). This operation
  happens in constant time.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> byte_size(<<433::16, 3::3>>)
      3

      iex> byte_size(<<1, 2, 3>>)
      3


  """
  def byte_size(bitstring) do
    byte_size(bitstring)
  end

  @doc ~S"""
  Returns the smallest integer greater than or equal to `number`.

  If you want to perform ceil operation on other decimal places,
  use `Float.ceil/2` instead.

  Allowed in guard tests. Inlined by the compiler.

  """
  def ceil(number) do
    :erlang.ceil(number)
  end

  @doc ~S"""
  Performs an integer division.

  Raises an `ArithmeticError` exception if one of the arguments is not an
  integer, or when the `divisor` is `0`.

  `div/2` performs *truncated* integer division. This means that
  the result is always rounded towards zero.

  If you want to perform floored integer division (rounding towards negative infinity),
  use `Integer.floor_div/2` instead.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      div(5, 2)
      #=> 2

      div(6, -4)
      #=> -1

      div(-99, 2)
      #=> -49

      div(100, 0)
      ** (ArithmeticError) bad argument in arithmetic expression


  """
  def div(dividend, divisor) do
    div(dividend, divisor)
  end

  @doc ~S"""
  Gets the element at the zero-based `index` in `tuple`.

  It raises `ArgumentError` when index is negative or it is out of range of the tuple elements.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      tuple = {:foo, :bar, 3}
      elem(tuple, 1)
      #=> :bar

      elem({}, 0)
      ** (ArgumentError) argument error

      elem({:foo, :bar}, 2)
      ** (ArgumentError) argument error


  """
  def elem(tuple, index) do
    elem(tuple, index)
  end

  @doc ~S"""
  Stops the execution of the calling process with the given reason.

  Since evaluating this function causes the process to terminate,
  it has no return value.

  Inlined by the compiler.

  ## Examples

  When a process reaches its end, by default it exits with
  reason `:normal`. You can also call `exit/1` explicitly if you
  want to terminate a process but not signal any failure:

      exit(:normal)

  In case something goes wrong, you can also use `exit/1` with
  a different reason:

      exit(:seems_bad)

  If the exit reason is not `:normal`, all the processes linked to the process
  that exited will crash (unless they are trapping exits).

  ## OTP exits

  Exits are used by the OTP to determine if a process exited abnormally
  or not. The following exits are considered "normal":

    * `exit(:normal)`
    * `exit(:shutdown)`
    * `exit({:shutdown, term})`

  Exiting with any other reason is considered abnormal and treated
  as a crash. This means the default supervisor behaviour kicks in,
  error reports are emitted, and so forth.

  This behaviour is relied on in many different places. For example,
  `ExUnit` uses `exit(:shutdown)` when exiting the test process to
  signal linked processes, supervision trees and so on to politely
  shut down too.

  ## CLI exits

  Building on top of the exit signals mentioned above, if the
  process started by the command line exits with any of the three
  reasons above, its exit is considered normal and the Operating
  System process will exit with status 0.

  It is, however, possible to customize the operating system exit
  signal by invoking:

      exit({:shutdown, integer})

  This will cause the operating system process to exit with the status given by
  `integer` while signaling all linked Erlang processes to politely
  shut down.

  Any other exit reason will cause the operating system process to exit with
  status `1` and linked Erlang processes to crash.

  """
  def exit(reason) do
    :erlang.exit(reason)
  end

  @doc ~S"""
  Returns the largest integer smaller than or equal to `number`.

  If you want to perform floor operation on other decimal places,
  use `Float.floor/2` instead.

  Allowed in guard tests. Inlined by the compiler.

  """
  def floor(number) do
    :erlang.floor(number)
  end

  @doc ~S"""
  Returns `true` if `module` is loaded and contains a
  public `function` with the given `arity`, otherwise `false`.

  Note that this function does not load the module in case
  it is not loaded. Check `Code.ensure_loaded/1` for more
  information.

  Inlined by the compiler.

  ## Examples

      iex> function_exported?(Enum, :map, 2)
      true

      iex> function_exported?(Enum, :map, 10)
      false

      iex> function_exported?(List, :to_string, 1)
      true

  """
  def function_exported?(module, function, arity) do
    :erlang.function_exported(module, function, arity)
  end

  @doc ~S"""
  Gets a value and updates a nested structure.

  `data` is a nested structure (that is, a map, keyword
  list, or struct that implements the `Access` behaviour).

  The `fun` argument receives the value of `key` (or `nil` if `key`
  is not present) and must return one of the following values:

    * a two-element tuple `{current_value, new_value}`. In this case,
      `current_value` is the retrieved value which can possibly be operated on before
      being returned. `new_value` is the new value to be stored under `key`.

    * `:pop`, which implies that the current value under `key`
      should be removed from the structure and returned.

  This function uses the `Access` module to traverse the structures
  according to the given `keys`, unless the `key` is a function,
  which is detailed in a later section.

  ## Examples

  This function is useful when there is a need to retrieve the current
  value (or something calculated in function of the current value) and
  update it at the same time. For example, it could be used to read the
  current age of a user while increasing it by one in one pass:

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> get_and_update_in(users, ["john", :age], &{&1, &1 + 1})
      {27, %{"john" => %{age: 28}, "meg" => %{age: 23}}}

  Note the current value given to the anonymous function may be `nil`.
  If any of the intermediate values are nil, it will raise:

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> get_and_update_in(users, ["jane", :age], &{&1, &1 + 1})
      ** (ArgumentError) could not put/update key :age on a nil value

  ## Functions as keys

  If a key is a function, the function will be invoked passing three
  arguments:

    * the operation (`:get_and_update`)
    * the data to be accessed
    * a function to be invoked next

  This means `get_and_update_in/3` can be extended to provide custom
  lookups. The downside is that functions cannot be stored as keys
  in the accessed data structures.

  When one of the keys is a function, the function is invoked.
  In the example below, we use a function to get and increment all
  ages inside a list:

      iex> users = [%{name: "john", age: 27}, %{name: "meg", age: 23}]
      iex> all = fn :get_and_update, data, next ->
      ...>   data |> Enum.map(next) |> Enum.unzip()
      ...> end
      iex> get_and_update_in(users, [all, :age], &{&1, &1 + 1})
      {[27, 23], [%{name: "john", age: 28}, %{name: "meg", age: 24}]}

  If the previous value before invoking the function is `nil`,
  the function *will* receive `nil` as a value and must handle it
  accordingly (be it by failing or providing a sane default).

  The `Access` module ships with many convenience accessor functions,
  like the `all` anonymous function defined above. See `Access.all/0`,
  `Access.key/2`, and others as examples.

  """
  def get_and_update_in(data, [head], fun) when :erlang.is_function(head, 3) do
    head.(:get_and_update, data, fun)
  end

  def get_and_update_in(data, [head | tail], fun) when :erlang.is_function(head, 3) do
    head.(:get_and_update, data, fn x1 -> Kernel.get_and_update_in(x1, tail, fun) end)
  end

  def get_and_update_in(data, [head], fun) when :erlang.is_function(fun, 1) do
    Access.get_and_update(data, head, fun)
  end

  def get_and_update_in(data, [head | tail], fun) when :erlang.is_function(fun, 1) do
    Access.get_and_update(data, head, fn x1 -> Kernel.get_and_update_in(x1, tail, fun) end)
  end

  @doc ~S"""
  Gets a value from a nested structure.

  Uses the `Access` module to traverse the structures
  according to the given `keys`, unless the `key` is a
  function, which is detailed in a later section.

  ## Examples

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> get_in(users, ["john", :age])
      27

  `get_in/2` can also use the accessors in the `Access` module
  to traverse more complex data structures. For example, here we
  use `Access.all/0` to traverse a list:

      iex> users = [%{name: "john", age: 27}, %{name: "meg", age: 23}]
      iex> get_in(users, [Access.all(), :age])
      [27, 23]

  In case any of the components returns `nil`, `nil` will be returned
  and `get_in/2` won't traverse any futher:

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> get_in(users, ["unknown", :age])
      nil

      iex> users = nil
      iex> get_in(users, [Access.all(), :age])
      nil

  The main feature of `get_in/2` is precisely that it aborts traversal
  when a `nil` value is found. Unless you need nil-safety, you are likely
  better off by writing "regular" Elixir code:

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> users["john"][:age]
      27

      iex> users = [%{name: "john", age: 27}, %{name: "meg", age: 23}]
      iex> Enum.map(users, fn user -> user[:age] end)
      [27, 23]

  Alternatively, if you need to access complex data-structures, you can
  use pattern matching:

      case users do
        %{"john" => %{age: age}} -> age
        _ -> default_value
      end

  ## Functions as keys

  If a key given to `get_in/2` is a function, the function will be invoked
  passing three arguments:

    * the operation (`:get`)
    * the data to be accessed
    * a function to be invoked next

  This means `get_in/2` can be extended to provide custom lookups.
  That's precisely how the `Access.all/0` key in the previous section
  behaves. For example, we can manually implement such traversal as
  follows:

      iex> users = [%{name: "john", age: 27}, %{name: "meg", age: 23}]
      iex> all = fn :get, data, next -> Enum.map(data, next) end
      iex> get_in(users, [all, :age])
      [27, 23]

  The `Access` module ships with many convenience accessor functions.
  See `Access.all/0`, `Access.key/2`, and others as examples.

  ## Working with structs

  By default, structs do not implement the `Access` behaviour required
  by this function. Therefore, you can't do this:

      get_in(some_struct, [:some_key, :nested_key])

  The good news is that structs have predefined shape. Therefore,
  you can write instead:

      some_struct.some_key.nested_key

  If, by any chance, `some_key` can return nil, you can always
  fallback to pattern matching to provide nested struct handling:

      case some_struct do
        %{some_key: %{nested_key: value}} -> value
        %{} -> nil
      end


  """
  def get_in(nil, [_ | _]) do
    nil
  end

  def get_in(data, [h]) when :erlang.is_function(h) do
    h.(:get, data, fn x1 -> x1 end)
  end

  def get_in(data, [h | t]) when :erlang.is_function(h) do
    h.(:get, data, fn x1 -> Kernel.get_in(x1, t) end)
  end

  def get_in(data, [h]) do
    data[h]
  end

  def get_in(data, [h | t]) do
    Kernel.get_in(data[h], t)
  end

  @doc ~S"""
  Returns the head of a list. Raises `ArgumentError` if the list is empty.

  It works with improper lists.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      hd([1, 2, 3, 4])
      #=> 1

      hd([1 | 2])
      #=> 1

  Giving it an empty list raises:

      hd([])
      #=> ** (ArgumentError) argument error


  """
  def hd(list) do
    :erlang.hd(list)
  end

  def inspect(term) do
    super(term, [])
  end

  @doc ~S"""
  Inspects the given argument according to the `Inspect` protocol.
  The second argument is a keyword list with options to control
  inspection.

  ## Options

  `inspect/2` accepts a list of options that are internally
  translated to an `Inspect.Opts` struct. Check the docs for
  `Inspect.Opts` to see the supported options.

  ## Examples

      iex> inspect(:foo)
      ":foo"

      iex> inspect([1, 2, 3, 4, 5], limit: 3)
      "[1, 2, 3, ...]"

      iex> inspect([1, 2, 3], pretty: true, width: 0)
      "[1,\n 2,\n 3]"

      iex> inspect("olá" <> <<0>>)
      "<<111, 108, 195, 161, 0>>"

      iex> inspect("olá" <> <<0>>, binaries: :as_strings)
      "\"olá\\0\""

      iex> inspect("olá", binaries: :as_binaries)
      "<<111, 108, 195, 161>>"

      iex> inspect('bar')
      "'bar'"

      iex> inspect([0 | 'bar'])
      "[0, 98, 97, 114]"

      iex> inspect(100, base: :octal)
      "0o144"

      iex> inspect(100, base: :hex)
      "0x64"

  Note that the `Inspect` protocol does not necessarily return a valid
  representation of an Elixir term. In such cases, the inspected result
  must start with `#`. For example, inspecting a function will return:

      inspect(fn a, b -> a + b end)
      #=> #Function<...>

  The `Inspect` protocol can be derived to hide certain fields
  from structs, so they don't show up in logs, inspects and similar.
  See the "Deriving" section of the documentation of the `Inspect`
  protocol for more information.

  """
  def inspect(term, opts) when is_list(opts) do
    (
      opts = Inspect.Opts.new(opts)
      limit = case(opts.pretty()) do
        true ->
          opts.width()
        false ->
          :infinity
      end
      doc = Inspect.Algebra.group(Inspect.Algebra.to_doc(term, opts))
      :erlang.iolist_to_binary(Inspect.Algebra.format(doc, limit))
    )
  end

  @doc ~S"""
  Returns `true` if `term` is an atom; otherwise returns `false`.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> is_atom(false)
      true

      iex> is_atom(:name)
      true

      iex> is_atom(AnAtom)
      true

      iex> is_atom("true")
      false


  """
  def is_atom(term) do
    is_atom(term)
  end

  @doc ~S"""
  Returns `true` if `term` is a binary; otherwise returns `false`.

  A binary always contains a complete number of bytes.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> is_binary("foo")
      true
      iex> is_binary(<<1::3>>)
      false


  """
  def is_binary(term) do
    is_binary(term)
  end

  @doc ~S"""
  Returns `true` if `term` is a bitstring (including a binary); otherwise returns `false`.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> is_bitstring("foo")
      true
      iex> is_bitstring(<<1::3>>)
      true


  """
  def is_bitstring(term) do
    :erlang.is_bitstring(term)
  end

  @doc ~S"""
  Returns `true` if `term` is either the atom `true` or the atom `false` (i.e.,
  a boolean); otherwise returns `false`.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> is_boolean(false)
      true

      iex> is_boolean(true)
      true

      iex> is_boolean(:test)
      false


  """
  def is_boolean(term) do
    :erlang.is_boolean(term)
  end

  @doc ~S"""
  Returns `true` if `term` is a floating-point number; otherwise returns `false`.

  Allowed in guard tests. Inlined by the compiler.

  """
  def is_float(term) do
    :erlang.is_float(term)
  end

  @doc ~S"""
  Returns `true` if `term` is a function; otherwise returns `false`.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> is_function(fn x -> x + x end)
      true

      iex> is_function("not a function")
      false


  """
  def is_function(term) do
    :erlang.is_function(term)
  end

  @doc ~S"""
  Returns `true` if `term` is a function that can be applied with `arity` number of arguments;
  otherwise returns `false`.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> is_function(fn x -> x * 2 end, 1)
      true
      iex> is_function(fn x -> x * 2 end, 2)
      false


  """
  def is_function(term, arity) do
    :erlang.is_function(term, arity)
  end

  @doc ~S"""
  Returns `true` if `term` is an integer; otherwise returns `false`.

  Allowed in guard tests. Inlined by the compiler.

  """
  def is_integer(term) do
    is_integer(term)
  end

  @doc ~S"""
  Returns `true` if `term` is a list with zero or more elements; otherwise returns `false`.

  Allowed in guard tests. Inlined by the compiler.

  """
  def is_list(term) do
    is_list(term)
  end

  @doc ~S"""
  Returns `true` if `term` is a map; otherwise returns `false`.

  Allowed in guard tests. Inlined by the compiler.

  """
  def is_map(term) do
    is_map(term)
  end

  @doc ~S"""
  Returns `true` if `key` is a key in `map`; otherwise returns `false`.

  It raises `BadMapError` if the first element is not a map.

  Allowed in guard tests. Inlined by the compiler.

  """
  def is_map_key(map, key) do
    :erlang.is_map_key(key, map)
  end

  @doc ~S"""
  Returns `true` if `term` is either an integer or a floating-point number;
  otherwise returns `false`.

  Allowed in guard tests. Inlined by the compiler.

  """
  def is_number(term) do
    :erlang.is_number(term)
  end

  @doc ~S"""
  Returns `true` if `term` is a PID (process identifier); otherwise returns `false`.

  Allowed in guard tests. Inlined by the compiler.

  """
  def is_pid(term) do
    is_pid(term)
  end

  @doc ~S"""
  Returns `true` if `term` is a port identifier; otherwise returns `false`.

  Allowed in guard tests. Inlined by the compiler.

  """
  def is_port(term) do
    :erlang.is_port(term)
  end

  @doc ~S"""
  Returns `true` if `term` is a reference; otherwise returns `false`.

  Allowed in guard tests. Inlined by the compiler.

  """
  def is_reference(term) do
    :erlang.is_reference(term)
  end

  @doc ~S"""
  Returns `true` if `term` is a tuple; otherwise returns `false`.

  Allowed in guard tests. Inlined by the compiler.

  """
  def is_tuple(term) do
    is_tuple(term)
  end

  @doc ~S"""
  Returns the length of `list`.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> length([1, 2, 3, 4, 5, 6, 7, 8, 9])
      9


  """
  def length(list) do
    length(list)
  end

  @doc ~S"""
  Returns `true` if `module` is loaded and contains a
  public `macro` with the given `arity`, otherwise `false`.

  Note that this function does not load the module in case
  it is not loaded. Check `Code.ensure_loaded/1` for more
  information.

  If `module` is an Erlang module (as opposed to an Elixir module), this
  function always returns `false`.

  ## Examples

      iex> macro_exported?(Kernel, :use, 2)
      true

      iex> macro_exported?(:erlang, :abs, 1)
      false


  """
  def macro_exported?(module, macro, arity) when is_atom(module) and is_atom(macro) and is_integer(arity) and (arity >= 0 and arity <= 255) do
    :erlang.function_exported(module, :__info__, 1) and :lists.member({macro, arity}, module.__info__(:macros))
  end

  @doc ~S"""
  Returns an almost unique reference.

  The returned reference will re-occur after approximately 2^82 calls;
  therefore it is unique enough for practical purposes.

  Inlined by the compiler.

  ## Examples

      make_ref()
      #=> #Reference<0.0.0.135>


  """
  def make_ref() do
    :erlang.make_ref()
  end

  @doc ~S"""
  Returns the size of a map.

  The size of a map is the number of key-value pairs that the map contains.

  This operation happens in constant time.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> map_size(%{a: "foo", b: "bar"})
      2


  """
  def map_size(map) do
    map_size(map)
  end

  @doc ~S"""
  Returns the biggest of the two given terms according to
  their structural comparison.

  If the terms compare equal, the first one is returned.

  This performs a structural comparison where all Elixir
  terms can be compared with each other. See the ["Structural
  comparison" section](#module-structural-comparison) section
  for more information.

  Inlined by the compiler.

  ## Examples

      iex> max(1, 2)
      2
      iex> max(:a, :b)
      :b


  """
  def max(first, second) do
    :erlang.max(first, second)
  end

  @doc ~S"""
  Returns the smallest of the two given terms according to
  their structural comparison.

  If the terms compare equal, the first one is returned.

  This performs a structural comparison where all Elixir
  terms can be compared with each other. See the ["Structural
  comparison" section](#module-structural-comparison) section
  for more information.

  Inlined by the compiler.

  ## Examples

      iex> min(1, 2)
      1
      iex> min("foo", "bar")
      "bar"


  """
  def min(first, second) do
    min(first, second)
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  @doc ~S"""
  Returns an atom representing the name of the local node.
  If the node is not alive, `:nonode@nohost` is returned instead.

  Allowed in guard tests. Inlined by the compiler.

  """
  def node() do
    Node.self()
  end

  @doc ~S"""
  Returns the node where the given argument is located.
  The argument can be a PID, a reference, or a port.
  If the local node is not alive, `:nonode@nohost` is returned.

  Allowed in guard tests. Inlined by the compiler.

  """
  def node(arg) do
    :erlang.node(arg)
  end

  @doc ~S"""
  Strictly boolean "not" operator.

  `value` must be a boolean; if it's not, an `ArgumentError` exception is raised.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> not false
      true


  """
  def unquote(:not)(value) do
    not(value)
  end

  @doc ~S"""
  Pops a key from the given nested structure.

  Uses the `Access` protocol to traverse the structures
  according to the given `keys`, unless the `key` is a
  function. If the key is a function, it will be invoked
  as specified in `get_and_update_in/3`.

  ## Examples

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> pop_in(users, ["john", :age])
      {27, %{"john" => %{}, "meg" => %{age: 23}}}

  In case any entry returns `nil`, its key will be removed
  and the deletion will be considered a success.

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> pop_in(users, ["jane", :age])
      {nil, %{"john" => %{age: 27}, "meg" => %{age: 23}}}


  """
  def pop_in(nil, [key | _]) do
    raise(ArgumentError, <<"could not pop key "::binary(), Kernel.inspect(key)::binary(), " on a nil value"::binary()>>)
  end

  def pop_in(data, [_ | _] = keys) do
    pop_in_data(data, keys)
  end

  @doc ~S"""
  Puts `value` at the given zero-based `index` in `tuple`.

  Inlined by the compiler.

  ## Examples

      iex> tuple = {:foo, :bar, 3}
      iex> put_elem(tuple, 0, :baz)
      {:baz, :bar, 3}


  """
  def put_elem(tuple, index, value) do
    put_elem(tuple, index + 1 - 1, value)
  end

  @doc ~S"""
  Puts a value in a nested structure.

  Uses the `Access` module to traverse the structures
  according to the given `keys`, unless the `key` is a
  function. If the key is a function, it will be invoked
  as specified in `get_and_update_in/3`.

  ## Examples

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> put_in(users, ["john", :age], 28)
      %{"john" => %{age: 28}, "meg" => %{age: 23}}

  If any of the intermediate values are nil, it will raise:

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> put_in(users, ["jane", :age], "oops")
      ** (ArgumentError) could not put/update key :age on a nil value


  """
  def put_in(data, [_ | _] = keys, value) do
    elem(Kernel.get_and_update_in(data, keys, fn _ -> {nil, value} end), 1)
  end

  @doc ~S"""
  Computes the remainder of an integer division.

  `rem/2` uses truncated division, which means that
  the result will always have the sign of the `dividend`.

  Raises an `ArithmeticError` exception if one of the arguments is not an
  integer, or when the `divisor` is `0`.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> rem(5, 2)
      1
      iex> rem(6, -4)
      2


  """
  def rem(dividend, divisor) do
    rem(dividend, divisor)
  end

  @doc ~S"""
  Rounds a number to the nearest integer.

  If the number is equidistant to the two nearest integers, rounds away from zero.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> round(5.6)
      6

      iex> round(5.2)
      5

      iex> round(-9.9)
      -10

      iex> round(-9)
      -9

      iex> round(2.5)
      3

      iex> round(-2.5)
      -3


  """
  def round(number) do
    :erlang.round(number)
  end

  @doc ~S"""
  Returns the PID (process identifier) of the calling process.

  Allowed in guard clauses. Inlined by the compiler.

  """
  def self() do
    self()
  end

  @doc ~S"""
  Sends a message to the given `dest` and returns the message.

  `dest` may be a remote or local PID, a local port, a locally
  registered name, or a tuple in the form of `{registered_name, node}` for a
  registered name at another node.

  Inlined by the compiler.

  ## Examples

      iex> send(self(), :hello)
      :hello


  """
  def send(dest, message) do
    send(dest, message)
  end

  @doc ~S"""
  Spawns the given function and returns its PID.

  Typically developers do not use the `spawn` functions, instead they use
  abstractions such as `Task`, `GenServer` and `Agent`, built on top of
  `spawn`, that spawns processes with more conveniences in terms of
  introspection and debugging.

  Check the `Process` module for more process-related functions.

  The anonymous function receives 0 arguments, and may return any value.

  Inlined by the compiler.

  ## Examples

      current = self()
      child = spawn(fn -> send(current, {self(), 1 + 2}) end)

      receive do
        {^child, 3} -> IO.puts("Received 3 back")
      end


  """
  def spawn(fun) do
    :erlang.spawn(fun)
  end

  @doc ~S"""
  Spawns the given function `fun` from the given `module` passing it the given
  `args` and returns its PID.

  Typically developers do not use the `spawn` functions, instead they use
  abstractions such as `Task`, `GenServer` and `Agent`, built on top of
  `spawn`, that spawns processes with more conveniences in terms of
  introspection and debugging.

  Check the `Process` module for more process-related functions.

  Inlined by the compiler.

  ## Examples

      spawn(SomeModule, :function, [1, 2, 3])


  """
  def spawn(module, fun, args) do
    :erlang.spawn(module, fun, args)
  end

  @doc ~S"""
  Spawns the given function, links it to the current process, and returns its PID.

  Typically developers do not use the `spawn` functions, instead they use
  abstractions such as `Task`, `GenServer` and `Agent`, built on top of
  `spawn`, that spawns processes with more conveniences in terms of
  introspection and debugging.

  Check the `Process` module for more process-related functions. For more
  information on linking, check `Process.link/1`.

  The anonymous function receives 0 arguments, and may return any value.

  Inlined by the compiler.

  ## Examples

      current = self()
      child = spawn_link(fn -> send(current, {self(), 1 + 2}) end)

      receive do
        {^child, 3} -> IO.puts("Received 3 back")
      end


  """
  def spawn_link(fun) do
    :erlang.spawn_link(fun)
  end

  @doc ~S"""
  Spawns the given function `fun` from the given `module` passing it the given
  `args`, links it to the current process, and returns its PID.

  Typically developers do not use the `spawn` functions, instead they use
  abstractions such as `Task`, `GenServer` and `Agent`, built on top of
  `spawn`, that spawns processes with more conveniences in terms of
  introspection and debugging.

  Check the `Process` module for more process-related functions. For more
  information on linking, check `Process.link/1`.

  Inlined by the compiler.

  ## Examples

      spawn_link(SomeModule, :function, [1, 2, 3])


  """
  def spawn_link(module, fun, args) do
    :erlang.spawn_link(module, fun, args)
  end

  @doc ~S"""
  Spawns the given function, monitors it and returns its PID
  and monitoring reference.

  Typically developers do not use the `spawn` functions, instead they use
  abstractions such as `Task`, `GenServer` and `Agent`, built on top of
  `spawn`, that spawns processes with more conveniences in terms of
  introspection and debugging.

  Check the `Process` module for more process-related functions.

  The anonymous function receives 0 arguments, and may return any value.

  Inlined by the compiler.

  ## Examples

      current = self()
      spawn_monitor(fn -> send(current, {self(), 1 + 2}) end)


  """
  def spawn_monitor(fun) do
    :erlang.spawn_monitor(fun)
  end

  @doc ~S"""
  Spawns the given module and function passing the given args,
  monitors it and returns its PID and monitoring reference.

  Typically developers do not use the `spawn` functions, instead they use
  abstractions such as `Task`, `GenServer` and `Agent`, built on top of
  `spawn`, that spawns processes with more conveniences in terms of
  introspection and debugging.

  Check the `Process` module for more process-related functions.

  Inlined by the compiler.

  ## Examples

      spawn_monitor(SomeModule, :function, [1, 2, 3])


  """
  def spawn_monitor(module, fun, args) do
    :erlang.spawn_monitor(module, fun, args)
  end

  def struct(struct) do
    super(struct, [])
  end

  @doc ~S"""
  Creates and updates a struct.

  The `struct` argument may be an atom (which defines `defstruct`)
  or a `struct` itself. The second argument is any `Enumerable` that
  emits two-element tuples (key-value pairs) during enumeration.

  Keys in the `Enumerable` that don't exist in the struct are automatically
  discarded. Note that keys must be atoms, as only atoms are allowed when
  defining a struct. If keys in the `Enumerable` are duplicated, the last
  entry will be taken (same behaviour as `Map.new/1`).

  This function is useful for dynamically creating and updating structs, as
  well as for converting maps to structs; in the latter case, just inserting
  the appropriate `:__struct__` field into the map may not be enough and
  `struct/2` should be used instead.

  ## Examples

      defmodule User do
        defstruct name: "john"
      end

      struct(User)
      #=> %User{name: "john"}

      opts = [name: "meg"]
      user = struct(User, opts)
      #=> %User{name: "meg"}

      struct(user, unknown: "value")
      #=> %User{name: "meg"}

      struct(User, %{name: "meg"})
      #=> %User{name: "meg"}

      # String keys are ignored
      struct(User, %{"name" => "meg"})
      #=> %User{name: "john"}


  """
  def struct(struct, fields) do
    struct(struct, fields, fn
     {:__struct__, _val}, acc ->
        acc
      {key, val}, acc ->
        case(acc) do
          %{^key => _} ->
            %{acc | key => val}
          _ ->
            acc
        end
    end)
  end

  def struct!(struct) do
    super(struct, [])
  end

  @doc ~S"""
  Similar to `struct/2` but checks for key validity.

  The function `struct!/2` emulates the compile time behaviour
  of structs. This means that:

    * when building a struct, as in `struct!(SomeStruct, key: :value)`,
      it is equivalent to `%SomeStruct{key: :value}` and therefore this
      function will check if every given key-value belongs to the struct.
      If the struct is enforcing any key via `@enforce_keys`, those will
      be enforced as well;

    * when updating a struct, as in `struct!(%SomeStruct{}, key: :value)`,
      it is equivalent to `%SomeStruct{struct | key: :value}` and therefore this
      function will check if every given key-value belongs to the struct.
      However, updating structs does not enforce keys, as keys are enforced
      only when building;


  """
  def struct!(struct, fields) when is_atom(struct) do
    validate_struct!(struct.__struct__(fields), struct, 1)
  end

  def struct!(struct, fields) when is_map(struct) do
    struct(struct, fields, fn
     {:__struct__, _}, acc ->
        acc
      {key, val}, acc ->
        :maps.update(key, val, acc)
    end)
  end

  @doc ~S"""
  A non-local return from a function.

  Check `Kernel.SpecialForms.try/1` for more information.

  Inlined by the compiler.

  """
  def throw(term) do
    :erlang.throw(term)
  end

  @doc ~S"""
  Returns the tail of a list. Raises `ArgumentError` if the list is empty.

  It works with improper lists.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      tl([1, 2, 3, :go])
      #=> [2, 3, :go]

      tl([:one])
      #=> []

      tl([:a, :b | :c])
      #=> [:b | :c]

      tl([:a | %{b: 1}])
      #=> %{b: 1}

  Giving it an empty list raises:

      tl([])
      #=> ** (ArgumentError) argument error


  """
  def tl(list) do
    :erlang.tl(list)
  end

  @doc ~S"""
  Returns the integer part of `number`.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> trunc(5.4)
      5

      iex> trunc(-5.99)
      -5

      iex> trunc(-5)
      -5


  """
  def trunc(number) do
    :erlang.trunc(number)
  end

  @doc ~S"""
  Returns the size of a tuple.

  This operation happens in constant time.

  Allowed in guard tests. Inlined by the compiler.

  ## Examples

      iex> tuple_size({:a, :b, :c})
      3


  """
  def tuple_size(tuple) do
    :erlang.tuple_size(tuple)
  end

  @doc ~S"""
  Updates a key in a nested structure.

  Uses the `Access` module to traverse the structures
  according to the given `keys`, unless the `key` is a
  function. If the key is a function, it will be invoked
  as specified in `get_and_update_in/3`.

  `data` is a nested structure (that is, a map, keyword
  list, or struct that implements the `Access` behaviour).
  The `fun` argument receives the value of `key` (or `nil`
  if `key` is not present) and the result replaces the value
  in the structure.

  ## Examples

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> update_in(users, ["john", :age], &(&1 + 1))
      %{"john" => %{age: 28}, "meg" => %{age: 23}}

  Note the current value given to the anonymous function may be `nil`.
  If any of the intermediate values are nil, it will raise:

      iex> users = %{"john" => %{age: 27}, "meg" => %{age: 23}}
      iex> update_in(users, ["jane", :age], & &1 + 1)
      ** (ArgumentError) could not put/update key :age on a nil value


  """
  def update_in(data, [_ | _] = keys, fun) when :erlang.is_function(fun) do
    elem(Kernel.get_and_update_in(data, keys, fn x -> {nil, fun.(x)} end), 1)
  end

  # Private Functions

  defp unquote(:"-MACRO-binding/2-fun-0-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp unquote(:"-MACRO-in/3-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-MACRO-in/3-fun-2-")(arg1, arg2, arg3, arg4, arg5, arg6) do
    # body not decompiled
  end

  defp unquote(:"-MACRO-in/3-fun-3-")(arg1, arg2, arg3, arg4, arg5) do
    # body not decompiled
  end

  defp unquote(:"-MACRO-raise/2-fun-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-MACRO-raise/2-fun-1-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-MACRO-use/3-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-MACRO-|>/3-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-expand_aliases/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-expand_attribute/3-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-expand_module_alias/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-fun.module_var/1-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-get_and_update_in/3-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-get_and_update_in/3-fun-1-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-get_in/2-fun-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-get_in/2-fun-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-in_list/6-fun-0-")(arg1, arg2, arg3, arg4, arg5) do
    # body not decompiled
  end

  defp unquote(:"-in_list/6-fun-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-pop_in_data/2-fun-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-pop_in_data/2-fun-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-pop_in_data/2-fun-2-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-put_in/3-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-small_literal_list?/1-fun-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-split_words/3-fun-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-struct!/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-struct/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-unescape_list_tokens/1-fun-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-unescape_tokens/1-fun-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-unescape_tokens/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-update_in/3-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-validate_variable_only_args!/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp alias_defmodule({:__aliases__, _, [Elixir, _ | _]}, module, _env) do
    {module, module, nil}
  end

  defp alias_defmodule({:__aliases__, _, _}, module, %{module: nil}) do
    {module, module, nil}
  end

  defp alias_defmodule({:__aliases__, _, [h | t]}, _module, env) when is_atom(h) do
    (
      module = :elixir_aliases.concat([env.module(), h])
      alias = String.to_atom(<<"Elixir."::binary(), Atom.to_string(h)::binary()>>)
      case(t) do
        [] ->
          {module, module, alias}
        _ ->
          {String.to_atom(Enum.join([module | t], ".")), module, alias}
      end
    )
  end

  defp alias_defmodule(_raw, module, _env) do
    {module, module, nil}
  end

  defp alias_meta({:__aliases__, meta, _}) do
    meta
  end

  defp alias_meta(_) do
    []
  end

  defp assert_module_scope(env, fun, arity) do
    case(env.module()) do
      nil ->
        raise(ArgumentError, <<"cannot invoke "::binary(), String.Chars.to_string(fun)::binary(), "/"::binary(), String.Chars.to_string(arity)::binary(), " outside module"::binary()>>)
      mod ->
        mod
    end
  end

  defp assert_no_function_scope(env, fun, arity) do
    case(env.function()) do
      nil ->
        :ok
      _ ->
        raise(ArgumentError, <<"cannot invoke "::binary(), String.Chars.to_string(fun)::binary(), "/"::binary(), String.Chars.to_string(arity)::binary(), " inside function/macro"::binary()>>)
    end
  end

  defp assert_no_match_or_guard_scope(context, exp) do
    case(context) do
      :match ->
        invalid_match!(exp)
      :guard ->
        raise(ArgumentError, <<"invalid expression in guard, "::binary(), String.Chars.to_string(exp)::binary(), " is not allowed in guards. "::binary(), "To learn more about guards, visit: https://hexdocs.pm/elixir/patterns-and-guards.html"::binary()>>)
      _ ->
        :ok
    end
  end

  defp build_boolean_check(operator, check, true_clause, false_clause) do
    optimize_boolean({:case, [], [check, [do: [{:->, [], [[false], false_clause]}, {:->, [], [[true], true_clause]}, {:->, [], [[{:other, [], Kernel}], {{:., [], [:erlang, :error]}, [], [{:{}, [], [:badbool, operator, {:other, [], Kernel}]}]}]}]]]})
  end

  defp build_if(condition, [do: do_clause]) do
    build_if(condition) do
      do_clause
    else
      nil
    end
  end

  defp build_if(condition, [do: do_clause, else: else_clause]) do
    optimize_boolean({:case, [], [condition, [do: [{:->, [], [[{:when, [], [{:x, [], Kernel}, {{:., [], [Kernel, :in]}, [], [{:x, [], Kernel}, [false, nil]]}]}], else_clause]}, {:->, [], [[{:_, [], Kernel}], do_clause]}]]]})
  end

  defp build_if(_condition, _arguments) do
    raise(ArgumentError, "invalid or duplicate keys for if, only \"do\" and an optional \"else\" are permitted")
  end

  defp build_unless(condition, [do: do_clause]) do
    build_unless(condition) do
      do_clause
    else
      nil
    end
  end

  defp build_unless(condition, [do: do_clause, else: else_clause]) do
    {:if, [context: Kernel, import: Kernel], [condition, [do: else_clause, else: do_clause]]}
  end

  defp build_unless(_condition, _arguments) do
    raise(ArgumentError, <<"invalid or duplicate keys for unless, "::binary(), "only \"do\" and an optional \"else\" are permitted"::binary()>>)
  end

  defp comp(left, {:|, _, [head, tail]}, expand, right, in_body?) do
    case(expand.(tail)) do
      [] ->
        {{:., [], [:erlang, :"=:="]}, [], [left, head]}
      [tail_head | tail] ->
        {{:., [], [:erlang, :orelse]}, [], [{{:., [], [:erlang, :"=:="]}, [], [left, head]}, in_list(left, tail_head, tail, expand, right, in_body?)]}
      tail when in_body? ->
        {{:., [], [:erlang, :orelse]}, [], [{{:., [], [:erlang, :"=:="]}, [], [left, head]}, {{:., [], [:lists, :member]}, [], [left, tail]}]}
      _ ->
        raise_on_invalid_args_in_2(right)
    end
  end

  defp comp(left, right, _expand, _right, _in_body?) do
    {{:., [], [:erlang, :"=:="]}, [], [left, right]}
  end

  defp decreasing_compare(var, first, last) do
    {{:., [], [:erlang, :andalso]}, [], [{{:., [], [:erlang, :"=<"]}, [], [var, first]}, {{:., [], [:erlang, :>=]}, [], [var, last]}]}
  end

  defp define(kind, call, expr, env) do
    (
      module = assert_module_scope(env, kind, 2)
      assert_no_function_scope(env, kind, 2)
      unquoted_call = :elixir_quote.has_unquotes(call)
      unquoted_expr = :elixir_quote.has_unquotes(expr)
      escaped_call = :elixir_quote.escape(call, :none, true)
      escaped_expr = case(unquoted_expr) do
        true ->
          :elixir_quote.escape(expr, :none, true)
        false ->
          key = :erlang.unique_integer()
          :elixir_module.write_cache(module, key, expr)
          {{:., [], [:elixir_module, :read_cache]}, [], [module, key]}
      end
      check_clauses = not(case(unquoted_expr) do
        false ->
          unquoted_call
        true ->
          true
        other ->
          :erlang.error({:badbool, :or, other})
      end)
      pos = :elixir_locals.cache_env(env)
      {{:., [], [:elixir_def, :store_definition]}, [], [kind, check_clauses, escaped_call, escaped_expr, pos]}
    )
  end

  defp define_guard(kind, guard, env) do
    case(:elixir_utils.extract_guards(guard)) do
      {call, [_, _ | _]} ->
        raise(ArgumentError, <<"invalid syntax in defguard "::binary(), Macro.to_string(call)::binary(), ", "::binary(), "only a single when clause is allowed"::binary()>>)
      {call, impls} ->
        case(Macro.decompose_call(call)) do
          {_name, args} ->
            validate_variable_only_args!(call, args)
            macro_definition = case(impls) do
              [] ->
                define(kind, call, nil, env)
              [guard] ->
                quoted = {:__block__, [], [{:require, [context: Kernel], [{:__aliases__, [alias: false], [:Kernel, :Utils]}]}, {{:., [], [{:__aliases__, [alias: false], [:Kernel, :Utils]}, :defguard]}, [], [args, guard]}]}
                define(kind, call, [do: quoted], env)
            end
            {:__block__, [], [{:@, [context: Kernel, import: :elixir_bootstrap], [{:doc, [context: Kernel], [[guard: true]]}]}, macro_definition]}
          _invalid_definition ->
            raise(ArgumentError, <<"invalid syntax in defguard "::binary(), Macro.to_string(call)::binary()>>)
        end
    end
  end

  defp do_at([arg], meta, name, function?, env) do
    (
      line = case(:lists.keymember(:context, 1, meta)) do
        true ->
          nil
        false ->
          env.line()
      end
      cond() do
        function? ->
          raise(ArgumentError, <<"cannot set attribute @"::binary(), String.Chars.to_string(name)::binary(), " inside function/macro"::binary()>>)
        name == :behavior ->
          warn_message = "@behavior attribute is not supported, please use @behaviour instead"
          IO.warn(warn_message, Macro.Env.stacktrace(env))
        :lists.member(name, [:moduledoc, :typedoc, :doc]) ->
          arg = {env.line(), arg}
          {{:., [], [{:__aliases__, [alias: false], [:Module]}, :__put_attribute__]}, [], [{:__MODULE__, [], Kernel}, name, arg, line]}
        true ->
          arg = expand_attribute(name, arg, env)
          {{:., [], [{:__aliases__, [alias: false], [:Module]}, :__put_attribute__]}, [], [{:__MODULE__, [], Kernel}, name, arg, line]}
      end
    )
  end

  defp do_at([], meta, name, function?, env) do
    (
      IO.warn(<<"the @"::binary(), String.Chars.to_string(name)::binary(), "() notation (with parenthesis) is deprecated, please use @"::binary(), String.Chars.to_string(name)::binary(), " (without parenthesis) instead"::binary()>>, Macro.Env.stacktrace(env))
      do_at(nil, meta, name, function?, env)
    )
  end

  defp do_at(args, _meta, name, function?, env) when is_atom(args) do
    (
      line = env.line()
      doc_attr? = :lists.member(name, [:moduledoc, :typedoc, :doc])
      case(function?) do
        true ->
          value = case(Module.__get_attribute__(env.module(), name, line)) do
            {_, doc} when doc_attr? ->
              doc
            other ->
              other
          end
          try() do
            :elixir_quote.escape(value, :none, false)
          rescue
            ex in [ArgumentError] ->
              raise(ArgumentError, <<"cannot inject attribute @"::binary(), String.Chars.to_string(name)::binary(), " into function/macro because "::binary(), Exception.message(ex)::binary()>>)
          end
        false when doc_attr? ->
          {:case, [], [{{:., [], [{:__aliases__, [alias: false], [:Module]}, :__get_attribute__]}, [], [{:__MODULE__, [], Kernel}, name, line]}, [do: [{:->, [], [[{{:_, [], Kernel}, {:doc, [], Kernel}}], {:doc, [], Kernel}]}, {:->, [], [[{:other, [], Kernel}], {:other, [], Kernel}]}]]]}
        false ->
          {{:., [], [{:__aliases__, [alias: false], [:Module]}, :__get_attribute__]}, [], [{:__MODULE__, [], Kernel}, name, line]}
      end
    )
  end

  defp do_at([{call, meta, ctx_or_args}, [{:do, _} | _] = kw], _meta, name, _function?, _env) do
    (
      args = case(is_atom(ctx_or_args)) do
        true ->
          []
        false ->
          ctx_or_args
      end
      code = <<"\n@"::binary(), String.Chars.to_string(name)::binary(), " ("::binary(), Macro.to_string({call, meta, args ++ [kw]})::binary(), ")"::binary()>>
      raise(ArgumentError, <<"expected 0 or 1 argument for @"::binary(), String.Chars.to_string(name)::binary(), ", got 2.\n\nIt seems you are trying to use the do-syntax with @module attributes but the do-block is binding to the attribute name. You probably want to wrap the argument value in parentheses, like this:\n"::binary(), String.Chars.to_string(String.replace(code, "\n", "\n    "))::binary(), "\n"::binary()>>)
    )
  end

  defp do_at(args, _meta, name, _function?, _env) do
    raise(ArgumentError, <<"expected 0 or 1 argument for @"::binary(), String.Chars.to_string(name)::binary(), ", got: "::binary(), String.Chars.to_string(length(args))::binary()>>)
  end

  defp expand_aliases({{:., _, [base, :{}]}, _, refs}, env) do
    (
      base = Macro.expand(base, env)
      Enum.map(refs, fn
       {:__aliases__, _, ref} ->
          Module.concat([base | ref])
        ref when is_atom(ref) ->
          Module.concat(base, ref)
        other ->
          other
      end)
    )
  end

  defp expand_aliases(module, env) do
    [Macro.expand(module, env)]
  end

  defp expand_attribute(:compile, arg, env) do
    Macro.prewalk(arg, fn
     {elem, {:__aliases__, _, _} = alias} ->
        {elem, Macro.expand(alias, %{env | function: {:__info__, 1}})}
      {:{}, meta, [{:__aliases__, _, _} = alias, fun, arity]} ->
        {:{}, meta, [Macro.expand(alias, %{env | function: {:__info__, 1}}), fun, arity]}
      node ->
        node
    end)
  end

  defp expand_attribute(_, arg, _) do
    arg
  end

  defp expand_concat_argument(arg, :left, %{context: :match} = caller) do
    (
      expanded_arg = case(bootstrapped?(Macro)) do
        true ->
          Macro.expand(arg, caller)
        false ->
          arg
      end
      case(expanded_arg) do
        {var, _, nil} when is_atom(var) ->
          invalid_concat_left_argument_error(Atom.to_string(var))
        {:^, _, [{var, _, nil}]} when is_atom(var) ->
          invalid_concat_left_argument_error(<<"^"::binary(), String.Chars.to_string(Atom.to_string(var))::binary()>>)
        _ ->
          expanded_arg
      end
    )
  end

  defp expand_concat_argument(arg, _, _) do
    arg
  end

  defp expand_module_alias({:__aliases__, _, _} = original, env) do
    case(:elixir_aliases.expand_or_concat(original, env)) do
      receiver when is_atom(receiver) ->
        receiver
      aliases ->
        aliases = :lists.map(fn x1 -> Macro.expand(x1, env) end, aliases)
        case(:lists.all(&is_atom/1, aliases)) do
          true ->
            :elixir_aliases.concat(aliases)
          false ->
            original
        end
    end
  end

  defp expand_module_alias(other, env) do
    Macro.expand(other, env)
  end

  defp extract_calendar(string) do
    case(:binary.split(string, " ", [:global])) do
      [_] ->
        {Calendar.ISO, string}
      parts ->
        maybe_atomize_calendar(List.last(parts), string)
    end
  end

  defp extract_concatenations({:<>, _, [left, right]}, caller) do
    [wrap_concatenation(left, :left, caller) | extract_concatenations(right, caller)]
  end

  defp extract_concatenations(other, caller) do
    [wrap_concatenation(other, :right, caller)]
  end

  defp in_list(left, head, tail, expand, right, in_body?) do
    (
      [head | tail] = :lists.map(fn x1 -> comp(left, x1, expand, right, in_body?) end, [head | tail])
      :lists.foldl(fn x1, x2 -> {{:., [], [:erlang, :orelse]}, [], [x2, x1]} end, head, tail)
    )
  end

  defp in_range(left, first, last, nil) do
    {:and, [context: Kernel, import: Kernel], [{:and, [context: Kernel, import: Kernel], [{:and, [context: Kernel, import: Kernel], [{{:., [], [:erlang, :is_integer]}, [], [left]}, {{:., [], [:erlang, :is_integer]}, [], [first]}]}, {{:., [], [:erlang, :is_integer]}, [], [last]}]}, {:or, [context: Kernel, import: Kernel], [{:and, [context: Kernel, import: Kernel], [{{:., [], [:erlang, :"=<"]}, [], [first, last]}, increasing_compare(left, first, last)]}, {:and, [context: Kernel, import: Kernel], [{{:., [], [:erlang, :<]}, [], [last, first]}, decreasing_compare(left, first, last)]}]}]}
  end

  defp in_range(left, first, last, step) when is_integer(step) do
    in_range_literal(left, first, last, step)
  end

  defp in_range(left, first, last, step) do
    (
      quoted = {:and, [context: Kernel, import: Kernel], [{:and, [context: Kernel, import: Kernel], [{:and, [context: Kernel, import: Kernel], [{{:., [], [:erlang, :is_integer]}, [], [left]}, {{:., [], [:erlang, :is_integer]}, [], [first]}]}, {{:., [], [:erlang, :is_integer]}, [], [last]}]}, {:or, [context: Kernel, import: Kernel], [{:and, [context: Kernel, import: Kernel], [{{:., [], [:erlang, :>]}, [], [step, 0]}, increasing_compare(left, first, last)]}, {:and, [context: Kernel, import: Kernel], [{{:., [], [:erlang, :<]}, [], [step, 0]}, decreasing_compare(left, first, last)]}]}]}
      in_range_step(quoted, left, first, step)
    )
  end

  defp in_range_literal(left, first, first, _step) when is_integer(first) do
    {{:., [], [:erlang, :"=:="]}, [], [left, first]}
  end

  defp in_range_literal(left, first, last, step) when step > 0 do
    (
      quoted = {{:., [], [:erlang, :andalso]}, [], [{{:., [], [:erlang, :is_integer]}, [], [left]}, increasing_compare(left, first, last)]}
      in_range_step(quoted, left, first, step)
    )
  end

  defp in_range_literal(left, first, last, step) when step < 0 do
    (
      quoted = {{:., [], [:erlang, :andalso]}, [], [{{:., [], [:erlang, :is_integer]}, [], [left]}, decreasing_compare(left, first, last)]}
      in_range_step(quoted, left, first, step)
    )
  end

  defp in_range_step(quoted, _left, _first, step) when step == 1 or step == :erlang.-(1) do
    quoted
  end

  defp in_range_step(quoted, left, first, step) do
    {{:., [], [:erlang, :andalso]}, [], [quoted, {{:., [], [:erlang, :"=:="]}, [], [{{:., [], [:erlang, :rem]}, [], [{:-, [context: Kernel, import: Kernel], [left, first]}, step]}, 0]}]}
  end

  defp in_var(false, ast, fun) do
    fun.(ast)
  end

  defp in_var(true, {atom, _, context} = var, fun) when is_atom(atom) and is_atom(context) do
    fun.(var)
  end

  defp in_var(true, ast, fun) do
    {:__block__, [], [{:=, [], [{:var, [], Kernel}, ast]}, fun.({:var, [], Kernel})]}
  end

  defp increasing_compare(var, first, last) do
    {{:., [], [:erlang, :andalso]}, [], [{{:., [], [:erlang, :>=]}, [], [var, first]}, {{:., [], [:erlang, :"=<"]}, [], [var, last]}]}
  end

  defp integer_pow(_, _, 0) do
    1
  end

  defp integer_pow(b, a, 1) do
    b * a
  end

  defp integer_pow(b, a, e) when Bitwise.band(e, 1) == 0 do
    integer_pow(b * b, a, Bitwise.bsr(e, 1))
  end

  defp integer_pow(b, a, e) do
    integer_pow(b * b, a * b, Bitwise.bsr(e, 1))
  end

  defp invalid_concat_left_argument_error(arg) do
    raise(ArgumentError, <<"the left argument of <> operator inside a match should always be a literal "::binary(), "binary because its size can't be verified. Got: "::binary(), String.Chars.to_string(arg)::binary()>>)
  end

  defp invalid_match!(exp) do
    raise(ArgumentError, <<"invalid expression in match, "::binary(), String.Chars.to_string(exp)::binary(), " is not allowed in patterns "::binary(), "such as function clauses, case clauses or on the left side of the = operator"::binary()>>)
  end

  defp maybe_atomize_calendar(<<alias::integer(), _::binary()>> = last_part, string) when alias >= 65 and alias <= 90 do
    (
      string = binary_part(string, 0, byte_size(string) - byte_size(last_part) - 1)
      {String.to_atom(<<"Elixir."::binary(), last_part::binary()>>), string}
    )
  end

  defp maybe_atomize_calendar(_last_part, string) do
    {Calendar.ISO, string}
  end

  defp maybe_raise!({:error, reason}, calendar, type, string) do
    raise(ArgumentError, <<"cannot parse "::binary(), Kernel.inspect(string)::binary(), " as "::binary(), String.Chars.to_string(type)::binary(), " for "::binary(), Kernel.inspect(calendar)::binary(), ", "::binary(), "reason: "::binary(), Kernel.inspect(reason)::binary()>>)
  end

  defp maybe_raise!(other, _calendar, _type, _string) do
    other
  end

  defp module_var({name, kind}) when is_atom(kind) do
    {name, [generated: true], kind}
  end

  defp module_var({name, kind}) do
    {name, [counter: kind, generated: true], nil}
  end

  defp nest_get_and_update_in([], fun) do
    fun
  end

  defp nest_get_and_update_in(list, fun) do
    {:fn, [], [{:->, [], [[{:x, [], Kernel}], nest_get_and_update_in({:x, [], Kernel}, list, fun)]}]}
  end

  defp nest_get_and_update_in(h, [{:access, key} | t], fun) do
    {{:., [], [{:__aliases__, [alias: false], [:Access]}, :get_and_update]}, [], [h, key, nest_get_and_update_in(t, fun)]}
  end

  defp nest_get_and_update_in(h, [{:map, key} | t], fun) do
    {{:., [], [{:__aliases__, [alias: false], [:Map]}, :get_and_update!]}, [], [h, key, nest_get_and_update_in(t, fun)]}
  end

  defp nest_pop_in(kind, list) do
    {:fn, [], [{:->, [], [[{:x, [], Kernel}], nest_pop_in(kind, {:x, [], Kernel}, list)]}]}
  end

  defp nest_pop_in(:map, h, [access: key]) do
    {:case, [], [h, [do: [{:->, [], [[nil], {nil, nil}]}, {:->, [], [[{:h, [], Kernel}], {{:., [], [{:__aliases__, [alias: false], [:Access]}, :pop]}, [], [{:h, [], Kernel}, key]}]}]]]}
  end

  defp nest_pop_in(_, _, [map: key]) do
    raise(ArgumentError, <<"cannot use pop_in when the last segment is a map/struct field. "::binary(), "This would effectively remove the field "::binary(), Kernel.inspect(key)::binary(), " from the map/struct"::binary()>>)
  end

  defp nest_pop_in(_, h, [{:map, key} | t]) do
    {{:., [], [{:__aliases__, [alias: false], [:Map]}, :get_and_update!]}, [], [h, key, nest_pop_in(:map, t)]}
  end

  defp nest_pop_in(_, h, [access: key]) do
    {:case, [], [h, [do: [{:->, [], [[nil], :pop]}, {:->, [], [[{:h, [], Kernel}], {{:., [], [{:__aliases__, [alias: false], [:Access]}, :pop]}, [], [{:h, [], Kernel}, key]}]}]]]}
  end

  defp nest_pop_in(_, h, [{:access, key} | t]) do
    {{:., [], [{:__aliases__, [alias: false], [:Access]}, :get_and_update]}, [], [h, key, nest_pop_in(:access, t)]}
  end

  defp nest_update_in([], fun) do
    fun
  end

  defp nest_update_in(list, fun) do
    {:fn, [], [{:->, [], [[{:x, [], Kernel}], nest_update_in({:x, [], Kernel}, list, fun)]}]}
  end

  defp nest_update_in(h, [{:map, key} | t], fun) do
    {{:., [], [{:__aliases__, [alias: false], [:Map]}, :update!]}, [], [h, key, nest_update_in(t, fun)]}
  end

  defp optimize_boolean({:case, meta, args}) do
    {:case, [{:optimize_boolean, true} | meta], args}
  end

  defp parse_with_calendar!(string, fun, context) do
    (
      {calendar, string} = extract_calendar(string)
      result = apply(calendar, fun, [string])
      {maybe_raise!(result, calendar, context, string), calendar}
    )
  end

  defp pop_in_data(nil, [_ | _]) do
    :pop
  end

  defp pop_in_data(data, [fun]) when :erlang.is_function(fun) do
    fun.(:get_and_update, data, fn _ -> :pop end)
  end

  defp pop_in_data(data, [fun | tail]) when :erlang.is_function(fun) do
    fun.(:get_and_update, data, fn x1 -> pop_in_data(x1, tail) end)
  end

  defp pop_in_data(data, [key]) do
    Access.pop(data, key)
  end

  defp pop_in_data(data, [key | tail]) do
    Access.get_and_update(data, key, fn x1 -> pop_in_data(x1, tail) end)
  end

  defp proper_start?({{:., _, [expr, _]}, _, _args}) when is_atom(expr) or elem(expr, 0) == :__aliases__ or elem(expr, 0) == :__MODULE__ do
    true
  end

  defp proper_start?({atom, _, _args}) when is_atom(atom) do
    true
  end

  defp proper_start?(other) do
    not(is_tuple(other))
  end

  defp raise_on_invalid_args_in_2(right) do
    raise(ArgumentError, <<"invalid right argument for operator \"in\", it expects a compile-time proper list "::binary(), "or compile-time range on the right side when used in guard expressions, got: "::binary(), Macro.to_string(right)::binary()>>)
  end

  defp range(_context, first, last) when is_integer(first) and is_integer(last) do
    (
      step = case(first <= last) do
        false ->
          :erlang.-(1)
        true ->
          1
      end
      {:%{}, [], [__struct__: Range, first: first, last: last, step: step]}
    )
  end

  defp range(nil, first, last) do
    {{:., [], [{:__aliases__, [], [Elixir, :Range]}, :new]}, [], [first, last]}
  end

  defp range(:guard, first, last) do
    {:%{}, [], [__struct__: Range, first: first, last: last, step: nil]}
  end

  defp range(:match, first, last) do
    {:%{}, [], [__struct__: Range, first: first, last: last]}
  end

  defp range(context, first, last, step) when is_integer(first) and is_integer(last) and is_integer(step) or context != nil do
    {:%{}, [], [__struct__: Range, first: first, last: last, step: step]}
  end

  defp range(nil, first, last, step) do
    {{:., [], [{:__aliases__, [], [Elixir, :Range]}, :new]}, [], [first, last, step]}
  end

  defp small_literal_list?(list) when is_list(list) and length(list) <= 32 do
    :lists.all(fn x -> is_binary(x) or is_atom(x) or :erlang.is_number(x) end, list)
  end

  defp small_literal_list?(_list) do
    false
  end

  defp split_words(string, [], caller) do
    split_words(string, 's', caller)
  end

  defp split_words(string, [mod], caller) when mod == 115 or mod == 97 or mod == 99 do
    case(is_binary(string)) do
      true ->
        parts = String.split(string)
        parts_with_trailing_comma = :lists.filter(fn x1 -> byte_size(x1) > 1 and :binary.last(x1) == 44 end, parts)
        case(parts_with_trailing_comma != []) do
          false ->
            nil
          true ->
            stacktrace = Macro.Env.stacktrace(caller)
            IO.warn(<<"the sigils ~w/~W do not allow trailing commas at the end of each word. "::binary(), "If the comma is necessary, define a regular list with [...], otherwise remove the comma."::binary()>>, stacktrace)
        end
        case(mod) do
          115 ->
            parts
          97 ->
            :lists.map(&String.to_atom/1, parts)
          99 ->
            :lists.map(&String.to_charlist/1, parts)
        end
      false ->
        parts = {{:., [], [{:__aliases__, [alias: false], [:String]}, :split]}, [], [string]}
        case(mod) do
          115 ->
            parts
          97 ->
            {{:., [], [:lists, :map]}, [], [{:&, [], [{:/, [context: Kernel, import: Kernel], [{{:., [], [{:__aliases__, [alias: false], [:String]}, :to_atom]}, [no_parens: true], []}, 1]}]}, parts]}
          99 ->
            {{:., [], [:lists, :map]}, [], [{:&, [], [{:/, [context: Kernel, import: Kernel], [{{:., [], [{:__aliases__, [alias: false], [:String]}, :to_charlist]}, [no_parens: true], []}, 1]}]}, parts]}
        end
    end
  end

  defp split_words(_string, _mods, _caller) do
    raise(ArgumentError, "modifier must be one of: s, a, c")
  end

  defp struct(struct, [], _fun) when is_atom(struct) do
    validate_struct!(struct.__struct__(), struct, 0)
  end

  defp struct(struct, fields, fun) when is_atom(struct) do
    struct(validate_struct!(struct.__struct__(), struct, 0), fields, fun)
  end

  defp struct(%_{} = struct, [], _fun) do
    struct
  end

  defp struct(%_{} = struct, fields, fun) do
    Enum.reduce(fields, struct, fun)
  end

  defp to_calendar_struct(type, fields) do
    {:%{}, [], :elixir_quote.list([__struct__: type] ++ fields, [])}
  end

  defp typespec?(:type) do
    true
  end

  defp typespec?(:typep) do
    true
  end

  defp typespec?(:opaque) do
    true
  end

  defp typespec?(:spec) do
    true
  end

  defp typespec?(:callback) do
    true
  end

  defp typespec?(:macrocallback) do
    true
  end

  defp typespec?(_) do
    false
  end

  defp unescape_list_tokens(tokens) do
    (
      escape = fn
       {:"::", _, [expr, _]} ->
          expr
        binary when is_binary(binary) ->
          :elixir_interpolation.unescape_string(binary)
      end
      :lists.map(escape, tokens)
    )
  end

  defp unescape_tokens(tokens) do
    :lists.map(fn token -> case(is_binary(token)) do
      true ->
        :elixir_interpolation.unescape_string(token)
      false ->
        token
    end end, tokens)
  end

  defp unescape_tokens(tokens, unescape_map) do
    :lists.map(fn token -> case(is_binary(token)) do
      true ->
        :elixir_interpolation.unescape_string(token, unescape_map)
      false ->
        token
    end end, tokens)
  end

  defp unnest({{:., _, [Access, :get]}, _, [expr, key]}, acc, _all_map?, kind) do
    unnest(expr, [{:access, key} | acc], false, kind)
  end

  defp unnest({{:., _, [expr, key]}, _, []}, acc, all_map?, kind) when is_tuple(expr) and elem(expr, 0) != :__aliases__ and elem(expr, 0) != :__MODULE__ do
    unnest(expr, [{:map, key} | acc], all_map?, kind)
  end

  defp unnest(other, [], _all_map?, kind) do
    raise(ArgumentError, <<"expected expression given to "::binary(), String.Chars.to_string(kind)::binary(), " to access at least one element, "::binary(), "got: "::binary(), Macro.to_string(other)::binary()>>)
  end

  defp unnest(other, acc, all_map?, kind) do
    case(proper_start?(other)) do
      true ->
        {[other | acc], all_map?}
      false ->
        raise(ArgumentError, <<"expression given to "::binary(), String.Chars.to_string(kind)::binary(), " must start with a variable, local or remote call "::binary(), "and be followed by an element access, got: "::binary(), Macro.to_string(other)::binary()>>)
    end
  end

  defp validate_range!(first, last) when :erlang.is_float(first) or :erlang.is_float(last) or is_atom(first) or is_atom(last) or is_binary(first) or is_binary(last) or is_list(first) or is_list(last) do
    raise(ArgumentError, <<"ranges (first..last//step) expect both sides to be integers, "::binary(), "got: "::binary(), Macro.to_string({:.., [], [first, last]})::binary()>>)
  end

  defp validate_range!(_, _) do
    :ok
  end

  defp validate_step!(step) when :erlang.is_float(step) or is_atom(step) or is_binary(step) or is_list(step) or step == 0 do
    raise(ArgumentError, <<"ranges (first..last//step) expect the step to be a non-zero integer, "::binary(), "got: "::binary(), Macro.to_string(step)::binary()>>)
  end

  defp validate_step!(_) do
    :ok
  end

  defp validate_struct!(%{__struct__: module} = struct, module, _arity) do
    struct
  end

  defp validate_struct!(%{__struct__: struct_name}, module, arity) when is_atom(struct_name) do
    (
      error_message = <<"expected struct name returned by "::binary(), Kernel.inspect(module)::binary(), ".__struct__/"::binary(), String.Chars.to_string(arity)::binary(), " to be "::binary(), Kernel.inspect(module)::binary(), ", got: "::binary(), Kernel.inspect(struct_name)::binary()>>
      raise(ArgumentError, error_message)
    )
  end

  defp validate_struct!(expr, module, arity) do
    (
      error_message = <<"expected "::binary(), Kernel.inspect(module)::binary(), ".__struct__/"::binary(), String.Chars.to_string(arity)::binary(), " to return a map with a :__struct__ "::binary(), "key that holds the name of the struct (atom), got: "::binary(), Kernel.inspect(expr)::binary()>>
      raise(ArgumentError, error_message)
    )
  end

  defp validate_variable_only_args!(call, args) do
    Enum.each(args, fn
     {ref, _meta, context} when is_atom(ref) and is_atom(context) ->
        :ok
      {:\\, _m1, [{ref, _m2, context}, _default]} when is_atom(ref) and is_atom(context) ->
        :ok
      _match ->
        raise(ArgumentError, <<"invalid syntax in defguard "::binary(), Macro.to_string(call)::binary()>>)
    end)
  end

  defp wrap_binding(true, var) do
    {:^, [], [var]}
  end

  defp wrap_binding(_, var) do
    var
  end

  defp wrap_concatenation(binary, _side, _caller) when is_binary(binary) do
    binary
  end

  defp wrap_concatenation(literal, _side, _caller) when is_list(literal) or is_atom(literal) or is_integer(literal) or :erlang.is_float(literal) do
    raise(ArgumentError, <<"expected binary argument in <> operator but got: "::binary(), Macro.to_string(literal)::binary()>>)
  end

  defp wrap_concatenation(other, side, caller) do
    (
      expanded = expand_concat_argument(other, side, caller)
      {:"::", [], [expanded, {:binary, [], nil}]}
    )
  end
end
