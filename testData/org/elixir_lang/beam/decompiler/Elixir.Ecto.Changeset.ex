# Source code recreated from a .beam file by IntelliJ Elixir
defmodule Ecto.Changeset do
  @moduledoc ~S"""
  Changesets allow filtering, casting, validation and
  definition of constraints when manipulating structs.

  There is an example of working with changesets in the introductory
  documentation in the `Ecto` module. The functions `cast/4` and
  `change/2` are the usual entry points for creating changesets.
  The first one is used to cast and validate external parameters,
  such as parameters sent through a form, API, command line, etc.
  The second one is used to change data directly from your application.

  The remaining functions in this module, such as validations,
  constraints, association handling, are about manipulating
  changesets. Let's discuss some of this extra functionality.

  ## External vs internal data

  Changesets allow working with both kinds of data:

    * internal to the application - for example programmatically generated,
      or coming from other subsystems. This use case is primarily covered
      by the `change/2` and `put_change/3` functions.

    * external to the application - for example data provided by the user in
      a form that needs to be type-converted and properly validated. This
      use case is primarily covered by the `cast/4` function.

  ## Validations and constraints

  Ecto changesets provide both validations and constraints which
  are ultimately turned into errors in case something goes wrong.

  The difference between them is that most validations can be
  executed without a need to interact with the database and, therefore,
  are always executed before attempting to insert or update the entry
  in the database. Some validations may happen against the database but
  they are inherently unsafe. Those validations start with a `unsafe_`
  prefix, such as `unsafe_validate_unique/3`.

  On the other hand, constraints rely on the database and are always safe.
  As a consequence, validations are always checked before constraints.
  Constraints won't even be checked in case validations failed.

  Let's see an example:

      defmodule User do
        use Ecto.Schema
        import Ecto.Changeset

        schema "users" do
          field :name
          field :email
          field :age, :integer
        end

        def changeset(user, params \\ %{}) do
          user
          |> cast(params, [:name, :email, :age])
          |> validate_required([:name, :email])
          |> validate_format(:email, ~r/@/)
          |> validate_inclusion(:age, 18..100)
          |> unique_constraint(:email)
        end
      end

  In the `changeset/2` function above, we define three validations.
  They check that `name` and `email` fields are present in the
  changeset, the e-mail is of the specified format, and the age is
  between 18 and 100 - as well as a unique constraint in the email
  field.

  Let's suppose the e-mail is given but the age is invalid. The
  changeset would have the following errors:

      changeset = User.changeset(%User{}, %{age: 0, email: "mary@example.com"})
      {:error, changeset} = Repo.insert(changeset)
      changeset.errors #=> [age: {"is invalid", []}, name: {"can't be blank", []}]

  In this case, we haven't checked the unique constraint in the
  e-mail field because the data did not validate. Let's fix the
  age and the name, and assume that the e-mail already exists in the
  database:

      changeset = User.changeset(%User{}, %{age: 42, name: "Mary", email: "mary@example.com"})
      {:error, changeset} = Repo.insert(changeset)
      changeset.errors #=> [email: {"has already been taken", []}]

  Validations and constraints define an explicit boundary when the check
  happens. By moving constraints to the database, we also provide a safe,
  correct and data-race free means of checking the user input.

  ### Deferred constraints

  Some databases support deferred constraints, i.e., constraints which are
  checked at the end of the transaction rather than at the end of each statement.

  Changesets do not support this type of constraints. When working with deferred
  constraints, a violation while invoking `c:Ecto.Repo.insert/2` or `c:Ecto.Repo.update/2` won't
  return `{:error, changeset}`, but rather raise an error at the end of the
  transaction.

  ## Empty values

  Many times, the data given on cast needs to be further pruned, specially
  regarding empty values. For example, if you are gathering data to be
  cast from the command line or through an HTML form or any other text-based
  format, it is likely those means cannot express nil values. For
  those reasons, changesets include the concept of empty values, which are
  values that will be automatically converted to the field's default value
  on `cast/4`. Those values are stored in the changeset `empty_values` field
  and default to `[""]`. You can also pass the `:empty_values` option to
  `cast/4` in case you want to change how a particular `cast/4` work.

  ## Associations, embeds and on replace

  Using changesets you can work with associations as well as with embedded
  structs. There are two primary APIs:

    * `cast_assoc/3` and `cast_embed/3` - those functions are used when
      working with external data. In particular, they allow you to change
      associations and embeds alongside the parent struct, all at once.

    * `put_assoc/4` and `put_embed/4` - it allows you to replace the
      association or embed as a whole. This can be used to move associated
      data from one entry to another, to completely remove or replace
      existing entries.

  See the documentation for those functions for more information.

  ### The `:on_replace` option

  When using any of those APIs, you may run into situations where Ecto sees
  data is being replaced. For example, imagine a Post has many Comments where
  the comments have IDs 1, 2 and 3. If you call `cast_assoc/3` passing only
  the IDs 1 and 2, Ecto will consider 3 is being "replaced" and it will raise
  by default. Such behaviour can be changed when defining the relation by
  setting `:on_replace` option when defining your association/embed according
  to the values below:

    * `:raise` (default) - do not allow removing association or embedded
      data via parent changesets
    * `:mark_as_invalid` - if attempting to remove the association or
      embedded data via parent changeset - an error will be added to the parent
      changeset, and it will be marked as invalid
    * `:nilify` - sets owner reference column to `nil` (available only for
      associations). Use this on a `belongs_to` column to allow the association
      to be cleared out so that it can be set to a new value. Will set `action`
      on associated changesets to `:replace`
    * `:update` - updates the association, available only for `has_one`, `belongs_to`
      and `embeds_one`. This option will update all the fields given to the changeset
      including the id for the association
    * `:delete` - removes the association or related data from the database.
      This option has to be used carefully (see below). Will set `action` on associated
      changesets to `:replace`
    * `:delete_if_exists` - like `:delete` except that it ignores any stale entry
      error. For instance, if you set `on_replace: :delete` but the replaced
      resource was already deleted by a separate request, it will raise a
      `Ecto.StaleEntryError`. `:delete_if_exists` makes it so it will only delete
      if the entry still exists

  The `:delete` and `:delete_if_exists` options must be used carefully as they allow
  users to delete any associated data by simply not sending the associated data.
  If you need deletion, it is often preferred to add a separate boolean virtual field
  in the schema and manually mark the changeset for deletion if the `:delete` field is
  set in the params, as in the example below. Note that we don't call `cast/4` in this
  case because we don't want to prevent deletion if a change is invalid (changes are
  irrelevant if the entity needs to be deleted).

      defmodule Comment do
        use Ecto.Schema
        import Ecto.Changeset

        schema "comments" do
          field :body, :string
          field :delete, :boolean, virtual: true
        end

        def changeset(comment, %{"delete" => "true"}) do
          %{Ecto.Changeset.change(comment, delete: true) | action: :delete}
        end

        def changeset(comment, params) do
          cast(comment, params, [:body])
        end
      end

  ## Schemaless changesets

  In the changeset examples so far, we have always used changesets to validate
  and cast data contained in a struct defined by an Ecto schema, such as the `%User{}`
  struct defined by the `User` module.

  However, changesets can also be used with "regular" structs too by passing a tuple
  with the data and its types:

      user = %User{}
      types = %{first_name: :string, last_name: :string, email: :string}
      changeset =
        {user, types}
        |> Ecto.Changeset.cast(params, Map.keys(types))
        |> Ecto.Changeset.validate_required(...)
        |> Ecto.Changeset.validate_length(...)

  where the user struct refers to the definition in the following module:

      defmodule User do
        defstruct [:name, :age]
      end

  Changesets can also be used with data in a plain map, by following the same API:

      data  = %{}
      types = %{name: :string}
      params = %{name: "Callum"}
      changeset =
        {data, types}
        |> Ecto.Changeset.cast(params, Map.keys(types))
        |> Ecto.Changeset.validate_required(...)
        |> Ecto.Changeset.validate_length(...)

  Such functionality makes Ecto extremely useful to cast, validate and prune data even
  if it is not meant to be persisted to the database.

  ### Changeset actions

  Changesets have an action field which is usually set by `Ecto.Repo`
  whenever one of the operations such as `insert` or `update` is called:

      changeset = User.changeset(%User{}, %{age: 42, email: "mary@example.com"})
      {:error, changeset} = Repo.insert(changeset)
      changeset.action
      #=> :insert

  This means that when working with changesets that are not meant to be
  persisted to the database, such as schemaless changesets, you may need
  to explicitly set the action to one specific value. Frameworks such as
  Phoenix use the action value to define how HTML forms should act.

  Instead of setting the action manually, you may use `apply_action/2` that
  emulates operations such as `c:Ecto.Repo.insert`. `apply_action/2` will return
  `{:ok, changes}` if the changeset is valid or `{:error, changeset}`, with
  the given `action` set in the changeset in case of errors.

  ## The Ecto.Changeset struct

  The public fields are:

    * `valid?`       - Stores if the changeset is valid
    * `data`         - The changeset source data, for example, a struct
    * `params`       - The parameters as given on changeset creation
    * `changes`      - The `changes` from parameters that were approved in casting
    * `errors`       - All errors from validations
    * `required`     - All required fields as a list of atoms
    * `action`       - The action to be performed with the changeset
    * `types`        - Cache of the data's field types
    * `empty_values` - A list of values to be considered empty
    * `repo`         - The repository applying the changeset (only set after a Repo function is called)
    * `repo_opts`    - A keyword list of options given to the underlying repository operation

  The following fields are private and must not be accessed directly.

    * `validations`
    * `constraints`
    * `filters`
    * `prepare`

  ### Redacting fields in inspect

  To hide a field's value from the inspect protocol of `Ecto.Changeset`, mark
  the field as `redact: true` in the schema, and it will display with the
  value `**redacted**`.

  """

  # Types

  @type action() :: ...
  @type constraint() :: ...
  @type data() :: ...
  @type error() :: ...
  @type t() :: ...
  @type t(type1) :: ...
  @type types() :: ...

  # Functions

  def __info__(p0) do
    # body not decompiled
  end

  def __struct__() do
    %Ecto.Changeset{action: nil, changes: %{}, constraints: [], data: nil, empty_values: [""], errors: [], filters: %{}, params: nil, prepare: [], repo: nil, repo_opts: [], required: [], types: nil, valid?: false, validations: []}
  end

  def __struct__(kv) do
    Enum.reduce(kv, %Ecto.Changeset{action: nil, changes: %{}, constraints: [], data: nil, empty_values: [""], errors: [], filters: %{}, params: nil, prepare: [], repo: nil, repo_opts: [], required: [], types: nil, valid?: false, validations: []}, fn {key, val}, map -> :maps.update(key, val, map) end)
  end

  def add_error(x0, x1, x2) do
    super(x0, x1, x2, [])
  end

  @doc ~S"""
  Adds an error to the changeset.

  An additional keyword list `keys` can be passed to provide additional
  contextual information for the error. This is useful when using
  `traverse_errors/2` and when translating errors with `Gettext`

  ## Examples

      iex> changeset = change(%Post{}, %{title: ""})
      iex> changeset = add_error(changeset, :title, "empty")
      iex> changeset.errors
      [title: {"empty", []}]
      iex> changeset.valid?
      false

      iex> changeset = change(%Post{}, %{title: ""})
      iex> changeset = add_error(changeset, :title, "empty", additional: "info")
      iex> changeset.errors
      [title: {"empty", [additional: "info"]}]
      iex> changeset.valid?
      false

      iex> changeset = change(%Post{}, %{tags: ["ecto", "elixir", "x"]})
      iex> changeset = add_error(changeset, :tags, "tag '%{val}' is too short", val: "x")
      iex> changeset.errors
      [tags: {"tag '%{val}' is too short", [val: "x"]}]
      iex> changeset.valid?
      false

  """
  def add_error(%Ecto.Changeset{errors: errors} = changeset, key, message, keys) when is_binary(message) do
    %{changeset | errors: [{key, {message, keys}} | errors], valid?: false}
  end

  @doc ~S"""
  Applies the changeset action only if the changes are valid.

  If the changes are valid, all changes are applied to the changeset data.
  If the changes are invalid, no changes are applied, and an error tuple
  is returned with the changeset containing the action that was attempted
  to be applied.

  The action may be any atom.

  ## Examples

      iex> {:ok, data} = apply_action(changeset, :update)

      iex> {:error, changeset} = apply_action(changeset, :update)
      %Ecto.Changeset{action: :update}

  """
  def apply_action(%Ecto.Changeset{} = changeset, action) when is_atom(action) do
    if(changeset.valid?()) do
      {:error, %Ecto.Changeset{changeset | action: action}}
    else
      {:ok, apply_changes(changeset)}
    end
  end

  def apply_action(%Ecto.Changeset{}, action) do
    raise(ArgumentError, <<"expected action to be an atom, got: "::binary(), Kernel.inspect(action)::binary()>>)
  end

  @doc ~S"""
  Applies the changeset action if the changes are valid or raises an error.

  ## Examples

      iex> changeset = change(%Post{author: "bar"}, %{title: "foo"})
      iex> apply_action!(changeset, :update)
      %Post{author: "bar", title: "foo"}

      iex> changeset = change(%Post{author: "bar"}, %{title: :bad})
      iex> apply_action!(changeset, :update)
      ** (Ecto.InvalidChangesetError) could not perform update because changeset is invalid.

  See `apply_action/2` for more information.

  """
  def apply_action!(%Ecto.Changeset{} = changeset, action) do
    case(apply_action(changeset, action)) do
      {:ok, data} ->
        data
      {:error, changeset} ->
        raise(Ecto.InvalidChangesetError, action: action, changeset: changeset)
    end
  end

  @doc ~S"""
  Applies the changeset changes to the changeset data.

  This operation will return the underlying data with changes
  regardless if the changeset is valid or not.

  ## Examples

      iex> changeset = change(%Post{author: "bar"}, %{title: "foo"})
      iex> apply_changes(changeset)
      %Post{author: "bar", title: "foo"}


  """
  def apply_changes(%Ecto.Changeset{changes: changes, data: data}) when changes == %{} do
    data
  end

  def apply_changes(%Ecto.Changeset{changes: changes, data: data, types: types}) do
    Enum.reduce(changes, data, fn {key, value}, acc -> case(:maps.find(key, types)) do
      {:ok, {tag, relation}} when tag === :embed or tag === :assoc ->
        apply_relation_changes(acc, key, relation, value)
      {:ok, _} ->
        :maps.put(key, value, acc)
      :error ->
        acc
    end end)
  end

  def assoc_constraint(changeset, assoc) do
    super(changeset, assoc, [])
  end

  @doc ~S"""
  Checks the associated field exists.

  This is similar to `foreign_key_constraint/3` except that the
  field is inferred from the association definition. This is useful
  to guarantee that a child will only be created if the parent exists
  in the database too. Therefore, it only applies to `belongs_to`
  associations.

  As the name says, a constraint is required in the database for
  this function to work. Such constraint is often added as a
  reference to the child table:

      create table(:comments) do
        add :post_id, references(:posts)
      end

  Now, when inserting a comment, it is possible to forbid any
  comment to be added if the associated post does not exist:

      comment
      |> Ecto.Changeset.cast(params, [:post_id])
      |> Ecto.Changeset.assoc_constraint(:post)
      |> Repo.insert

  ## Options

    * `:message` - the message in case the constraint check fails,
      defaults to "does not exist"
    * `:name` - the constraint name. By default, the constraint
      name is inferred from the table + association field.
      May be required explicitly for complex cases

  """
  def assoc_constraint(changeset, assoc, opts) do
    (
      constraint = opts[:name] || x
      message = message(opts, "does not exist")
      add_constraint(changeset, :foreign_key, String.Chars.to_string(constraint), :exact, assoc, message, :assoc)
    )
  end

  def cast(x0, x1, x2) do
    super(x0, x1, x2, [])
  end

  @doc ~S"""
  Applies the given `params` as changes for the given `data` according to
  the given set of `permitted` keys. Returns a changeset.

  The given `data` may be either a changeset, a schema struct or a `{data, types}`
  tuple. The second argument is a map of `params` that are cast according
  to the type information from `data`. `params` is a map with string keys
  or a map with atom keys, containing potentially invalid data. Mixed keys
  are not allowed.

  During casting, all `permitted` parameters whose values match the specified
  type information will have their key name converted to an atom and stored
  together with the value as a change in the `:changes` field of the changeset.
  All parameters that are not explicitly permitted are ignored.

  If casting of all fields is successful, the changeset is returned as valid.

  Note that `cast/4` validates the types in the `params`, but not in the given
  `data`.

  ## Options

    * `:empty_values` - a list of values to be considered as empty when casting.
      Empty values are always replaced by the default value of the respective key. Defaults to `[""]`

  ## Examples

      iex> changeset = cast(post, params, [:title])
      iex> if changeset.valid? do
      ...>   Repo.update!(changeset)
      ...> end

  Passing a changeset as the first argument:

      iex> changeset = cast(post, %{title: "Hello"}, [:title])
      iex> new_changeset = cast(changeset, %{title: "Foo", body: "World"}, [:body])
      iex> new_changeset.params
      %{"title" => "Hello", "body" => "World"}

  Or creating a changeset from a simple map with types:

      iex> data = %{title: "hello"}
      iex> types = %{title: :string}
      iex> changeset = cast({data, types}, %{title: "world"}, [:title])
      iex> apply_changes(changeset)
      %{title: "world"}

  ## Composing casts

  `cast/4` also accepts a changeset as its first argument. In such cases, all
  the effects caused by the call to `cast/4` (additional errors and changes)
  are simply added to the ones already present in the argument changeset.
  Parameters are merged (**not deep-merged**) and the ones passed to `cast/4`
  take precedence over the ones already in the changeset.

  """
  def cast(_data, %{__struct__: _} = params, _permitted, _opts) do
    raise(Ecto.CastError, type: :map, value: params, message: <<"expected params to be a :map, got: `"::binary(), Kernel.inspect(params)::binary(), "`"::binary()>>)
  end

  def cast({data, types}, params, permitted, opts) when is_map(data) do
    cast(data, types, %{}, params, permitted, opts)
  end

  def cast(%Ecto.Changeset{types: nil}, _params, _permitted, _opts) do
    raise(ArgumentError, "changeset does not have types information")
  end

  def cast(%Ecto.Changeset{changes: changes, data: data, types: types, empty_values: empty_values} = changeset, params, permitted, opts) do
    (
      opts = Keyword.put_new(opts, :empty_values, empty_values)
      new_changeset = cast(data, types, changes, params, permitted, opts)
      cast_merge(changeset, new_changeset)
    )
  end

  def cast(%{__struct__: module} = data, params, permitted, opts) do
    cast(data, module.__changeset__(), %{}, params, permitted, opts)
  end

  def cast_assoc(changeset, name) do
    super(changeset, name, [])
  end

  @doc ~S"""
  Casts the given association with the changeset parameters.

  This function should be used when working with the entire association at
  once (and not a single element of a many-style association) and receiving
  data external to the application.

  `cast_assoc/3` works matching the records extracted from the database
  and compares it with the parameters received from an external source.
  Therefore, it is expected that the data in the changeset has explicitly
  preloaded the association being cast and that all of the IDs exist and
  are unique.

  For example, imagine a user has many addresses relationship where
  post data is sent as follows

      %{"name" => "john doe", "addresses" => [
        %{"street" => "somewhere", "country" => "brazil", "id" => 1},
        %{"street" => "elsewhere", "country" => "poland"},
      ]}

  and then

      User
      |> Repo.get!(id)
      |> Repo.preload(:addresses) # Only required when updating data
      |> Ecto.Changeset.cast(params, [])
      |> Ecto.Changeset.cast_assoc(:addresses, with: &MyApp.Address.changeset/2)

  The parameters for the given association will be retrieved
  from `changeset.params`. Those parameters are expected to be
  a map with attributes, similar to the ones passed to `cast/4`.
  Once parameters are retrieved, `cast_assoc/3` will match those
  parameters with the associations already in the changeset record.

  Once `cast_assoc/3` is called, Ecto will compare each parameter
  with the user's already preloaded addresses and act as follows:

    * If the parameter does not contain an ID, the parameter data
      will be passed to `MyApp.Address.changeset/2` with a new struct
      and become an insert operation
    * If the parameter contains an ID and there is no associated child
      with such ID, the parameter data will be passed to
      `MyApp.Address.changeset/2` with a new struct and become an insert
      operation
    * If the parameter contains an ID and there is an associated child
      with such ID, the parameter data will be passed to
      `MyApp.Address.changeset/2` with the existing struct and become an
      update operation
    * If there is an associated child with an ID and its ID is not given
      as parameter, the `:on_replace` callback for that association will
      be invoked (see the "On replace" section on the module documentation)

  Every time the `MyApp.Address.changeset/2` function is invoked, it must
  return a changeset. Once the parent changeset is given to an `Ecto.Repo`
  function, all entries will be inserted/updated/deleted within the same
  transaction.

  Note developers are allowed to explicitly set the `:action` field of a
  changeset to instruct Ecto how to act in certain situations. Let's suppose
  that, if one of the associations has only empty fields, you want to ignore
  the entry altogether instead of showing an error. The changeset function could
  be written like this:

      def changeset(struct, params) do
        struct
        |> cast(params, [:title, :body])
        |> validate_required([:title, :body])
        |> case do
          %{valid?: false, changes: changes} = changeset when changes == %{} ->
            # If the changeset is invalid and has no changes, it is
            # because all required fields are missing, so we ignore it.
            %{changeset | action: :ignore}
          changeset ->
            changeset
        end
      end

  ## Partial changes for many-style associations

  By preloading an association using a custom query you can confine the behavior
  of `cast_assoc/3`. This opens up the possibility to work on a subset of the data,
  instead of all associations in the database.

  Taking the initial example of users having addresses imagine those addresses
  are set up to belong to a country. If you want to allow users to bulk edit all
  addresses that belong to a single country, you can do so by changing the preload
  query:

      query = from MyApp.Address, where: [country: ^edit_country]

      User
      |> Repo.get!(id)
      |> Repo.preload(addresses: query)
      |> Ecto.Changeset.cast(params, [])
      |> Ecto.Changeset.cast_assoc(:addresses)

  This will allow you to cast and update only the association for the given country.
  The important point for partial changes is that any addresses, which were not
  preloaded won't be changed.

  ## Options

    * `:required` - if the association is a required field
    * `:required_message` - the message on failure, defaults to "can't be blank"
    * `:invalid_message` - the message on failure, defaults to "is invalid"
    * `:force_update_on_change` - force the parent record to be updated in the
      repository if there is a change, defaults to `true`
    * `:with` - the function to build the changeset from params. Defaults to the
      `changeset/2` function of the associated module. It can be changed by passing
      an anonymous function or an MFA tuple.  If using an MFA, the default changeset
      and parameters arguments will be prepended to the given args. For example,
      using `with: {Author, :special_changeset, ["hello"]}` will be invoked as
      `Author.special_changeset(changeset, params, "hello")`


  """
  def cast_assoc(changeset, name, opts) when is_atom(name) do
    cast_relation(:assoc, changeset, name, opts)
  end

  def cast_embed(changeset, name) do
    super(changeset, name, [])
  end

  @doc ~S"""
  Casts the given embed with the changeset parameters.

  The parameters for the given embed will be retrieved
  from `changeset.params`. Those parameters are expected to be
  a map with attributes, similar to the ones passed to `cast/4`.
  Once parameters are retrieved, `cast_embed/3` will match those
  parameters with the embeds already in the changeset record.
  See `cast_assoc/3` for an example of working with casts and
  associations which would also apply for embeds.

  The changeset must have been previously `cast` using
  `cast/4` before this function is invoked.

  ## Options

    * `:required` - if the embed is a required field
    * `:required_message` - the message on failure, defaults to "can't be blank"
    * `:invalid_message` - the message on failure, defaults to "is invalid"
    * `:force_update_on_change` - force the parent record to be updated in the
      repository if there is a change, defaults to `true`
    * `:with` - the function to build the changeset from params. Defaults to the
      `changeset/2` function of the embedded module. It can be changed by passing
      an anonymous function or an MFA tuple.  If using an MFA, the default changeset
      and parameters arguments will be prepended to the given args. For example,
      using `with: {Author, :special_changeset, ["hello"]}` will be invoked as
      `Author.special_changeset(changeset, params, "hello")`

  """
  def cast_embed(changeset, name, opts) when is_atom(name) do
    cast_relation(:embed, changeset, name, opts)
  end

  def change(x0) do
    super(x0, %{})
  end

  @doc ~S"""
  Wraps the given data in a changeset or adds changes to a changeset.

  `changes` is a map or keyword where the key is an atom representing a
  field, association or embed and the value is a term. Note the `value` is
  directly stored in the changeset with no validation whatsoever. For this
  reason, this function is meant for working with data internal to the
  application.

  When changing embeds and associations, see `put_assoc/4` for a complete
  reference on the accepted values.

  This function is useful for:

    * wrapping a struct inside a changeset
    * directly changing a struct without performing castings nor validations
    * directly bulk-adding changes to a changeset

  Changed attributes will only be added if the change does not have the
  same value as the field in the data.

  When a changeset is passed as the first argument, the changes passed as the
  second argument are merged over the changes already in the changeset if they
  differ from the values in the struct.

  When a `{data, types}` is passed as the first argument, a changeset is
  created with the given data and types and marked as valid.

  See `cast/4` if you'd prefer to cast and validate external parameters.

  ## Examples

      iex> changeset = change(%Post{})
      %Ecto.Changeset{...}
      iex> changeset.valid?
      true
      iex> changeset.changes
      %{}

      iex> changeset = change(%Post{author: "bar"}, title: "title")
      iex> changeset.changes
      %{title: "title"}

      iex> changeset = change(%Post{title: "title"}, title: "title")
      iex> changeset.changes
      %{}

      iex> changeset = change(changeset, %{title: "new title", body: "body"})
      iex> changeset.changes.title
      "new title"
      iex> changeset.changes.body
      "body"


  """
  def change({data, types}, changes) when is_map(data) do
    change(%Ecto.Changeset{action: nil, changes: %{}, constraints: [], empty_values: [""], errors: [], filters: %{}, params: nil, prepare: [], repo: nil, repo_opts: [], required: [], validations: [], data: data, types: Enum.into(types, %{}), valid?: true}, changes)
  end

  def change(%Ecto.Changeset{types: nil}, _changes) do
    raise(ArgumentError, "changeset does not have types information")
  end

  def change(%Ecto.Changeset{changes: changes, types: types} = changeset, new_changes) when is_map(new_changes) or is_list(new_changes) do
    (
      {changes, errors, valid?} = get_changed(changeset.data(), types, changes, new_changes, changeset.errors(), changeset.valid?())
      %{changeset | changes: changes, errors: errors, valid?: valid?}
    )
  end

  def change(%{__struct__: struct} = data, changes) when is_map(changes) or is_list(changes) do
    (
      types = struct.__changeset__()
      {changes, errors, valid?} = get_changed(data, types, %{}, changes, [], true)
      %Ecto.Changeset{action: nil, constraints: [], empty_values: [""], filters: %{}, params: nil, prepare: [], repo: nil, repo_opts: [], required: [], validations: [], valid?: valid?, data: data, changes: changes, errors: errors, types: types}
    )
  end

  def check_constraint(changeset, field) do
    super(changeset, field, [])
  end

  @doc ~S"""
  Checks for a check constraint in the given field.

  The check constraint works by relying on the database to check
  if the check constraint has been violated or not and, if so,
  Ecto converts it into a changeset error.

  In order to use the check constraint, the first step is
  to define the check constraint in a migration:

      create constraint("users", :age_must_be_positive, check: "age > 0")

  Now that a constraint exists, when modifying users, we could
  annotate the changeset with a check constraint so Ecto knows
  how to convert it into an error message:

      cast(user, params, [:age])
      |> check_constraint(:age, name: :age_must_be_positive)

  Now, when invoking `c:Ecto.Repo.insert/2` or `c:Ecto.Repo.update/2`, if the
  age is not positive, it will be converted into an error and
  `{:error, changeset}` returned by the repository. Note that the error
  will occur only after hitting the database so it will not be visible
  until all other validations pass.

  ## Options

    * `:message` - the message in case the constraint check fails.
      Defaults to "is invalid"
    * `:name` - the name of the constraint. Required.
    * `:match` - how the changeset constraint name is matched against the
      repo constraint, may be `:exact`, `:suffix` or `:prefix`. Defaults to `:exact`.
      `:suffix` matches any repo constraint which `ends_with?` `:name`
       to this changeset constraint.
      `:prefix` matches any repo constraint which `starts_with?` `:name`
       to this changeset constraint.


  """
  def check_constraint(changeset, field, opts) do
    (
      constraint = opts[:name] || x
      message = message(opts, "is invalid")
      match_type = Keyword.get(opts, :match, :exact)
      add_constraint(changeset, :check, String.Chars.to_string(constraint), match_type, field, message)
    )
  end

  @doc ~S"""
  Returns all constraints in a changeset.

  A constraint is a map with the following fields:

    * `:type` - the type of the constraint that will be checked in the database,
      such as `:check`, `:unique`, etc
    * `:constraint` - the database constraint name as a string
    * `:match` - the type of match Ecto will perform on a violated constraint
      against the `:constraint` value. It is `:exact`, `:suffix` or `:prefix`
    * `:field` - the field a violated constraint will apply the error to
    * `:error_message` - the error message in case of violated constraints
    * `:error_type` - the type of error that identifies the error message


  """
  def constraints(%Ecto.Changeset{constraints: constraints}) do
    constraints
  end

  @doc ~S"""
  Deletes a change with the given key.

  ## Examples

      iex> changeset = change(%Post{}, %{title: "foo"})
      iex> changeset = delete_change(changeset, :title)
      iex> get_change(changeset, :title)
      nil


  """
  def delete_change(%Ecto.Changeset{} = changeset, key) when is_atom(key) do
    Map.update!(changeset, :changes, fn x1 -> :maps.remove(key, x1) end)
  end

  def exclusion_constraint(changeset, field) do
    super(changeset, field, [])
  end

  @doc ~S"""
  Checks for an exclusion constraint in the given field.

  The exclusion constraint works by relying on the database to check
  if the exclusion constraint has been violated or not and, if so,
  Ecto converts it into a changeset error.

  ## Options

    * `:message` - the message in case the constraint check fails,
      defaults to "violates an exclusion constraint"
    * `:name` - the constraint name. By default, the constraint
      name is inferred from the table + field. May be required
      explicitly for complex cases
    * `:match` - how the changeset constraint name is matched against the
      repo constraint, may be `:exact` or `:suffix`. Defaults to `:exact`.
      `:suffix` matches any repo constraint which `ends_with?` `:name`
       to this changeset constraint.


  """
  def exclusion_constraint(changeset, field, opts) do
    (
      constraint = opts[:name] || x
      message = message(opts, "violates an exclusion constraint")
      match_type = Keyword.get(opts, :match, :exact)
      add_constraint(changeset, :exclusion, String.Chars.to_string(constraint), match_type, field, message, :exclusion)
    )
  end

  @doc ~S"""
  Fetches a change from the given changeset.

  This function only looks at the `:changes` field of the given `changeset` and
  returns `{:ok, value}` if the change is present or `:error` if it's not.

  ## Examples

      iex> changeset = change(%Post{body: "foo"}, %{title: "bar"})
      iex> fetch_change(changeset, :title)
      {:ok, "bar"}
      iex> fetch_change(changeset, :body)
      :error


  """
  def fetch_change(%Ecto.Changeset{changes: changes} = _changeset, key) when is_atom(key) do
    :maps.find(key, changes)
  end

  @doc ~S"""
  Same as `fetch_change/2` but returns the value or raises if the given key was not found.

  ## Examples

      iex> changeset = change(%Post{body: "foo"}, %{title: "bar"})
      iex> fetch_change!(changeset, :title)
      "bar"
      iex> fetch_change!(changeset, :body)
      ** (KeyError) key :body not found in: %{title: "bar"}

  """
  def fetch_change!(changeset, key) do
    case(fetch_change(changeset, key)) do
      {:ok, value} ->
        value
      :error ->
        raise(KeyError, key: key, term: changeset.changes())
    end
  end

  @doc ~S"""
  Fetches the given field from changes or from the data.

  While `fetch_change/2` only looks at the current `changes`
  to retrieve a value, this function looks at the changes and
  then falls back on the data, finally returning `:error` if
  no value is available.

  For relations, these functions will return the changeset
  original data with changes applied. To retrieve raw changesets,
  please use `fetch_change/2`.

  ## Examples

      iex> post = %Post{title: "Foo", body: "Bar baz bong"}
      iex> changeset = change(post, %{title: "New title"})
      iex> fetch_field(changeset, :title)
      {:changes, "New title"}
      iex> fetch_field(changeset, :body)
      {:data, "Bar baz bong"}
      iex> fetch_field(changeset, :not_a_field)
      :error


  """
  def fetch_field(%Ecto.Changeset{changes: changes, data: data, types: types}, key) when is_atom(key) do
    case(:maps.find(key, changes)) do
      {:ok, value} ->
        {:changes, change_as_field(types, key, value)}
      :error ->
        case(:maps.find(key, data)) do
          {:ok, value} ->
            {:data, data_as_field(data, types, key, value)}
          :error ->
            :error
        end
    end
  end

  @doc ~S"""
  Same as `fetch_field/2` but returns the value or raises if the given key was not found.

  ## Examples

      iex> post = %Post{title: "Foo", body: "Bar baz bong"}
      iex> changeset = change(post, %{title: "New title"})
      iex> fetch_field!(changeset, :title)
      "New title"
      iex> fetch_field!(changeset, :other)
      ** (KeyError) key :other not found in: %Post{...}

  """
  def fetch_field!(changeset, key) do
    case(fetch_field(changeset, key)) do
      {_, value} ->
        value
      :error ->
        raise(KeyError, key: key, term: changeset.data())
    end
  end

  @doc ~S"""
  Forces a change on the given `key` with `value`.

  If the change is already present, it is overridden with
  the new value.

  ## Examples

      iex> changeset = change(%Post{author: "bar"}, %{title: "foo"})
      iex> changeset = force_change(changeset, :title, "bar")
      iex> changeset.changes
      %{title: "bar"}

      iex> changeset = force_change(changeset, :author, "bar")
      iex> changeset.changes
      %{title: "bar", author: "bar"}


  """
  def force_change(%Ecto.Changeset{types: nil}, _key, _value) do
    raise(ArgumentError, "changeset does not have types information")
  end

  def force_change(%Ecto.Changeset{types: types} = changeset, key, value) do
    case(Map.get(types, key)) do
      {tag, _} when tag === :embed or tag === :assoc ->
        raise(RuntimeError, <<"changing "::binary(), String.Chars.to_string(tag)::binary(), "s with force_change/3 is not supported, "::binary(), "please use put_"::binary(), String.Chars.to_string(tag)::binary(), "/4 instead"::binary()>>)
      nil ->
        raise(ArgumentError, <<"unknown field `"::binary(), Kernel.inspect(key)::binary(), "` in "::binary(), Kernel.inspect(changeset.data())::binary()>>)
      _ ->
        elem(Map.get_and_update!(changeset, :changes, fn x -> Access.get_and_update(x, key, fn _ -> {nil, value} end) end), 1)
    end
  end

  def foreign_key_constraint(changeset, field) do
    super(changeset, field, [])
  end

  @doc ~S"""
  Checks for foreign key constraint in the given field.

  The foreign key constraint works by relying on the database to
  check if the associated data exists or not. This is useful to
  guarantee that a child will only be created if the parent exists
  in the database too.

  In order to use the foreign key constraint the first step is
  to define the foreign key in a migration. This is often done
  with references. For example, imagine you are creating a
  comments table that belongs to posts. One would have:

      create table(:comments) do
        add :post_id, references(:posts)
      end

  By default, Ecto will generate a foreign key constraint with
  name "comments_post_id_fkey" (the name is configurable).

  Now that a constraint exists, when creating comments, we could
  annotate the changeset with foreign key constraint so Ecto knows
  how to convert it into an error message:

      cast(comment, params, [:post_id])
      |> foreign_key_constraint(:post_id)

  Now, when invoking `c:Ecto.Repo.insert/2` or `c:Ecto.Repo.update/2`, if the
  associated post does not exist, it will be converted into an
  error and `{:error, changeset}` returned by the repository.

  ## Options

    * `:message` - the message in case the constraint check fails,
      defaults to "does not exist"
    * `:name` - the constraint name. By default, the constraint
      name is inferred from the table + field. May be required
      explicitly for complex cases


  """
  def foreign_key_constraint(changeset, field, opts) do
    (
      constraint = opts[:name] || x
      message = message(opts, "does not exist")
      add_constraint(changeset, :foreign_key, String.Chars.to_string(constraint), :exact, field, message, :foreign)
    )
  end

  def get_change(x0, x1) do
    super(x0, x1, nil)
  end

  @doc ~S"""
  Gets a change or returns a default value.

  ## Examples

      iex> changeset = change(%Post{body: "foo"}, %{title: "bar"})
      iex> get_change(changeset, :title)
      "bar"
      iex> get_change(changeset, :body)
      nil


  """
  def get_change(%Ecto.Changeset{changes: changes} = _changeset, key, default) when is_atom(key) do
    Map.get(changes, key, default)
  end

  def get_field(x0, x1) do
    super(x0, x1, nil)
  end

  @doc ~S"""
  Gets a field from changes or from the data.

  While `get_change/3` only looks at the current `changes`
  to retrieve a value, this function looks at the changes and
  then falls back on the data, finally returning `default` if
  no value is available.

  For relations, these functions will return the changeset data
  with changes applied. To retrieve raw changesets, please use `get_change/3`.

      iex> post = %Post{title: "A title", body: "My body is a cage"}
      iex> changeset = change(post, %{title: "A new title"})
      iex> get_field(changeset, :title)
      "A new title"
      iex> get_field(changeset, :not_a_field, "Told you, not a field!")
      "Told you, not a field!"


  """
  def get_field(%Ecto.Changeset{changes: changes, data: data, types: types}, key, default) do
    case(:maps.find(key, changes)) do
      {:ok, value} ->
        change_as_field(types, key, value)
      :error ->
        case(:maps.find(key, data)) do
          {:ok, value} ->
            data_as_field(data, types, key, value)
          :error ->
            default
        end
    end
  end

  @doc ~S"""
  Merges two changesets.

  This function merges two changesets provided they have been applied to the
  same data (their `:data` field is equal); if the data differs, an
  `ArgumentError` exception is raised. If one of the changesets has a `:repo`
  field which is not `nil`, then the value of that field is used as the `:repo`
  field of the resulting changeset; if both changesets have a non-`nil` and
  different `:repo` field, an `ArgumentError` exception is raised.

  The other fields are merged with the following criteria:

    * `params` - params are merged (not deep-merged) giving precedence to the
      params of `changeset2` in case of a conflict. If both changesets have their
      `:params` fields set to `nil`, the resulting changeset will have its params
      set to `nil` too.
    * `changes` - changes are merged giving precedence to the `changeset2`
      changes.
    * `errors` and `validations` - they are simply concatenated.
    * `required` - required fields are merged; all the fields that appear
      in the required list of both changesets are moved to the required
      list of the resulting changeset.

  ## Examples

      iex> changeset1 = cast(%Post{}, %{title: "Title"}, [:title])
      iex> changeset2 = cast(%Post{}, %{title: "New title", body: "Body"}, [:title, :body])
      iex> changeset = merge(changeset1, changeset2)
      iex> changeset.changes
      %{body: "Body", title: "New title"}

      iex> changeset1 = cast(%Post{body: "Body"}, %{title: "Title"}, [:title])
      iex> changeset2 = cast(%Post{}, %{title: "New title"}, [:title])
      iex> merge(changeset1, changeset2)
      ** (ArgumentError) different :data when merging changesets


  """
  def merge(%Ecto.Changeset{data: data} = cs1, %Ecto.Changeset{data: data} = cs2) do
    (
      new_repo = merge_identical(cs1.repo(), cs2.repo(), "repos")
      new_repo_opts = Keyword.merge(cs1.repo_opts(), cs2.repo_opts())
      new_action = merge_identical(cs1.action(), cs2.action(), "actions")
      new_filters = Map.merge(cs1.filters(), cs2.filters())
      new_validations = cs1.validations() ++ cs2.validations()
      new_constraints = cs1.constraints() ++ cs2.constraints()
      cast_merge(%{cs1 | repo: new_repo, repo_opts: new_repo_opts, filters: new_filters, action: new_action, validations: new_validations, constraints: new_constraints}, cs2)
    )
  end

  def merge(%Ecto.Changeset{}, %Ecto.Changeset{}) do
    raise(ArgumentError, message: "different :data when merging changesets")
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(p0) do
    # body not decompiled
  end

  def no_assoc_constraint(changeset, assoc) do
    super(changeset, assoc, [])
  end

  @doc ~S"""
  Checks the associated field does not exist.

  This is similar to `foreign_key_constraint/3` except that the
  field is inferred from the association definition. This is useful
  to guarantee that parent can only be deleted (or have its primary
  key changed) if no child exists in the database. Therefore, it only
  applies to `has_*` associations.

  As the name says, a constraint is required in the database for
  this function to work. Such constraint is often added as a
  reference to the child table:

      create table(:comments) do
        add :post_id, references(:posts)
      end

  Now, when deleting the post, it is possible to forbid any post to
  be deleted if they still have comments attached to it:

      post
      |> Ecto.Changeset.change
      |> Ecto.Changeset.no_assoc_constraint(:comments)
      |> Repo.delete

  ## Options

    * `:message` - the message in case the constraint check fails,
      defaults to "is still associated with this entry" (for `has_one`)
      and "are still associated with this entry" (for `has_many`)
    * `:name` - the constraint name. By default, the constraint
      name is inferred from the association table + association
      field. May be required explicitly for complex cases


  """
  def no_assoc_constraint(changeset, assoc, opts) do
    (
      {constraint, message} = case(get_assoc(changeset, assoc)) do
        %Ecto.Association.Has{cardinality: cardinality, related_key: related_key, related: related} ->
          {opts[:name] || x, message(opts, no_assoc_message(cardinality))}
        other ->
          raise(ArgumentError, <<"no_assoc_constraint can only be added to has one/many associations, got: "::binary(), Kernel.inspect(other)::binary()>>)
      end
      add_constraint(changeset, :foreign_key, String.Chars.to_string(constraint), :exact, assoc, message, :no_assoc)
    )
  end

  def optimistic_lock(data_or_changeset, field) do
    super(data_or_changeset, field, &increment_with_rollover/1)
  end

  @doc ~S"""
  Applies optimistic locking to the changeset.

  [Optimistic
  locking](http://en.wikipedia.org/wiki/Optimistic_concurrency_control) (or
  *optimistic concurrency control*) is a technique that allows concurrent edits
  on a single record. While pessimistic locking works by locking a resource for
  an entire transaction, optimistic locking only checks if the resource changed
  before updating it.

  This is done by regularly fetching the record from the database, then checking
  whether another user has made changes to the record *only when updating the
  record*. This behaviour is ideal in situations where the chances of concurrent
  updates to the same record are low; if they're not, pessimistic locking or
  other concurrency patterns may be more suited.

  ## Usage

  Optimistic locking works by keeping a "version" counter for each record; this
  counter gets incremented each time a modification is made to a record. Hence,
  in order to use optimistic locking, a field must exist in your schema for
  versioning purpose. Such field is usually an integer but other types are
  supported.

  ## Examples

  Assuming we have a `Post` schema (stored in the `posts` table), the first step
  is to add a version column to the `posts` table:

      alter table(:posts) do
        add :lock_version, :integer, default: 1
      end

  The column name is arbitrary and doesn't need to be `:lock_version`. Now add
  a field to the schema too:

      defmodule Post do
        use Ecto.Schema

        schema "posts" do
          field :title, :string
          field :lock_version, :integer, default: 1
        end

        def changeset(:update, struct, params \\ %{}) do
          struct
          |> Ecto.Changeset.cast(params, [:title])
          |> Ecto.Changeset.optimistic_lock(:lock_version)
        end
      end

  Now let's take optimistic locking for a spin:

      iex> post = Repo.insert!(%Post{title: "foo"})
      %Post{id: 1, title: "foo", lock_version: 1}
      iex> valid_change = Post.changeset(:update, post, %{title: "bar"})
      iex> stale_change = Post.changeset(:update, post, %{title: "baz"})
      iex> Repo.update!(valid_change)
      %Post{id: 1, title: "bar", lock_version: 2}
      iex> Repo.update!(stale_change)
      ** (Ecto.StaleEntryError) attempted to update a stale entry:

      %Post{id: 1, title: "baz", lock_version: 1}

  When a conflict happens (a record which has been previously fetched is
  being updated, but that same record has been modified since it was
  fetched), an `Ecto.StaleEntryError` exception is raised.

  Optimistic locking also works with delete operations. Just call the
  `optimistic_lock/3` function with the data before delete:

      iex> changeset = Ecto.Changeset.optimistic_lock(post, :lock_version)
      iex> Repo.delete(changeset)

  `optimistic_lock/3` by default assumes the field
  being used as a lock is an integer. If you want to use another type,
  you need to pass the third argument customizing how the next value
  is generated:

      iex> Ecto.Changeset.optimistic_lock(post, :lock_uuid, fn _ -> Ecto.UUID.generate end)


  """
  def optimistic_lock(data_or_changeset, field, incrementer) do
    (
      changeset = change(data_or_changeset, %{})
      current = get_field(changeset, field)
      changeset = prepare_changes(changeset, fn changeset -> elem(Map.get_and_update!(changeset, :changes, fn x -> Access.get_and_update(x, field, fn _ -> {nil, incrementer.(current)} end) end), 1) end)
      changeset = elem(Map.get_and_update!(changeset, :filters, fn x -> Access.get_and_update(x, field, fn _ -> {nil, current} end) end), 1)
      changeset
    )
  end

  @doc ~S"""
  Provides a function executed by the repository on insert/update/delete.

  If the changeset given to the repository is valid, the function given to
  `prepare_changes/2` will be called with the changeset and must return a
  changeset, allowing developers to do final adjustments to the changeset or
  to issue data consistency commands. The repository itself can be accessed
  inside the function under the `repo` field in the changeset. If the
  changeset given to the repository is invalid, the function will not be
  invoked.

  The given function is guaranteed to run inside the same transaction
  as the changeset operation for databases that do support transactions.

  ## Example

  A common use case is updating a counter cache, in this case updating a post's
  comment count when a comment is created:

      def create_comment(comment, params) do
        comment
        |> cast(params, [:body, :post_id])
        |> prepare_changes(fn changeset ->
             if post_id = get_change(changeset, :post_id) do
               query = from Post, where: [id: ^post_id]
               changeset.repo.update_all(query, inc: [comment_count: 1])
             end
             changeset
           end)
      end

  We retrieve the repo from the comment changeset itself and use
  update_all to update the counter cache in one query. Finally, the original
  changeset must be returned.

  """
  def prepare_changes(%Ecto.Changeset{prepare: prepare} = changeset, function) when :erlang.is_function(function, 1) do
    %{changeset | prepare: [function | prepare]}
  end

  def put_assoc(x0, x1, x2) do
    super(x0, x1, x2, [])
  end

  @doc ~S"""
  Puts the given association entry or entries as a change in the changeset.

  This function is used to work with associations as a whole. For example,
  if a Post has many Comments, it allows you to add, remove or change all
  comments at once. If your goal is to simply add a new comment to a post,
  then it is preferred to do so manually, as we will describe later in the
  "Example: Adding a comment to a post" section.

  This function requires the associated data to have been preloaded, except
  when the parent changeset has been newly built and not yet persisted.
  Missing data will invoke the `:on_replace` behaviour defined on the
  association.

  For associations with cardinality one, `nil` can be used to remove the existing
  entry. For associations with many entries, an empty list may be given instead.

  If the association has no changes, it will be skipped. If the association is
  invalid, the changeset will be marked as invalid. If the given value is not any
  of values below, it will raise.

  The associated data may be given in different formats:

    * a map or a keyword list representing changes to be applied to the
      associated data. A map or keyword list can be given to update the
      associated data as long as they have matching primary keys.
      For example, `put_assoc(changeset, :comments, [%{id: 1, title: "changed"}])`
      will locate the comment with `:id` of 1 and update its title.
      If no comment with such id exists, one is created on the fly.
      Since only a single comment was given, any other associated comment
      will be replaced. On all cases, it is expected the keys to be atoms.
      Opposite to `cast_assoc` and `embed_assoc`, the given map (or struct)
      is not validated in any way and will be inserted as is.
      This API is mostly used in scripts and tests, to make it straight-
      forward to create schemas with associations at once, such as:

          Ecto.Changeset.change(
            %Post{},
            title: "foo",
            comments: [
              %{body: "first"},
              %{body: "second"}
            ]
          )

    * changesets or structs - when a changeset or struct is given, they
      are treated as the canonical data and the associated data currently
      stored in the association is ignored. For instance, the operation
      `put_assoc(changeset, :comments, [%Comment{id: 1, title: "changed"}])`
      will send the `Comment` as is to the database, ignoring any comment
      currently associated, even if a matching ID is found. If the comment
      is already persisted to the database, then `put_assoc/4` only takes
      care of guaranteeing that the comments and the parent data are associated.
      This extremely useful when associating existing data, as we will see
      in the "Example: Adding tags to a post" section.

  Once the parent changeset is given to an `Ecto.Repo` function, all entries
  will be inserted/updated/deleted within the same transaction.

  ## Example: Adding a comment to a post

  Imagine a relationship where Post has many comments and you want to add a
  new comment to an existing post. While it is possible to use `put_assoc/4`
  for this, it would be unnecessarily complex. Let's see an example.

  First, let's fetch the post with all existing comments:

      post = Post |> Repo.get!(1) |> Repo.preload(:comments)

  The following approach is **wrong**:

      post
      |> Ecto.Changeset.change()
      |> Ecto.Changeset.put_assoc(:comments, [%Comment{body: "bad example!"}])
      |> Repo.update!()

  The reason why the example above is wrong is because `put_assoc/4` always
  works with the **full data**. So the example above will effectively **erase
  all previous comments** and only keep the comment you are currently adding.
  Instead, you could try:

      post
      |> Ecto.Changeset.change()
      |> Ecto.Changeset.put_assoc(:comments, [%Comment{body: "so-so example!"} | post.comments])
      |> Repo.update!()

  In this example, we prepend the new comment to the list of existing comments.
  Ecto will diff the list of comments currently in `post` with the list of comments
  given, and correctly insert the new comment to the database. Note, however,
  Ecto is doing a lot of work just to figure out something we knew since the
  beginning, which is that there is only one new comment.

  In cases like above, when you want to work only on a single entry, it is
  much easier to simply work on the associated directly. For example, we
  could instead set the `post` association in the comment:

      %Comment{body: "better example"}
      |> Ecto.Changeset.change()
      |> Ecto.Changeset.put_assoc(:post, post)
      |> Repo.insert!()

  Alternatively, we can make sure that when we create a comment, it is already
  associated to the post:

      Ecto.build_assoc(post, :comments)
      |> Ecto.Changeset.change(body: "great example!")
      |> Repo.insert!()

  Or we can simply set the post_id in the comment itself:

      %Comment{body: "better example", post_id: post.id}
      |> Repo.insert!()

  In other words, when you find yourself wanting to work only with a subset
  of the data, then using `put_assoc/4` is most likely unnecessary. Instead,
  you want to work on the other side of the association.

  Let's see an example where using `put_assoc/4` is a good fit.

  ## Example: Adding tags to a post

  Imagine you are receiving a set of tags you want to associate to a post.
  Let's imagine that those tags exist upfront and are all persisted to the
  database. Imagine we get the data in this format:

      params = %{"title" => "new post", "tags" => ["learner"]}

  Now, since the tags already exist, we will bring all of them from the
  database and put them directly in the post:

      tags = Repo.all(from t in Tag, where: t.name in ^params["tags"])

      post
      |> Repo.preload(:tags)
      |> Ecto.Changeset.cast(params, [:title]) # No need to allow :tags as we put them directly
      |> Ecto.Changeset.put_assoc(:tags, tags) # Explicitly set the tags

  Since in this case we always require the user to pass all tags
  directly, using `put_assoc/4` is a great fit. It will automatically
  remove any tag not given and properly associate all of the given
  tags with the post.

  Furthermore, since the tag information is given as structs read directly
  from the database, Ecto will treat the data as correct and only do the
  minimum necessary to guarantee that posts and tags are associated,
  without trying to update or diff any of the fields in the tag struct.

  Although it accepts an `opts` argument, there are no options currently
  supported by `put_assoc/4`.

  """
  def put_assoc(%Ecto.Changeset{} = changeset, name, value, opts) do
    put_relation(:assoc, changeset, name, value, opts)
  end

  @doc ~S"""
  Puts a change on the given `key` with `value`.

  `key` is an atom that represents any field, embed or
  association in the changeset. Note the `value` is directly
  stored in the changeset with no validation whatsoever.
  For this reason, this function is meant for working with
  data internal to the application.

  If the change is already present, it is overridden with
  the new value. If the change has the same value as in the
  changeset data, it is not added to the list of changes.

  When changing embeds and associations, see `put_assoc/4`
  for a complete reference on the accepted values.

  ## Examples

      iex> changeset = change(%Post{author: "bar"}, %{title: "foo"})
      iex> changeset = put_change(changeset, :title, "bar")
      iex> changeset.changes
      %{title: "bar"}

      iex> changeset = put_change(changeset, :author, "bar")
      iex> changeset.changes
      %{title: "bar"}


  """
  def put_change(%Ecto.Changeset{types: nil}, _key, _value) do
    raise(ArgumentError, "changeset does not have types information")
  end

  def put_change(%Ecto.Changeset{data: data, types: types} = changeset, key, value) do
    (
      type = Map.get(types, key)
      {changes, errors, valid?} = put_change(data, changeset.changes(), changeset.errors(), changeset.valid?(), key, value, type)
      %{changeset | changes: changes, errors: errors, valid?: valid?}
    )
  end

  def put_embed(x0, x1, x2) do
    super(x0, x1, x2, [])
  end

  @doc ~S"""
  Puts the given embed entry or entries as a change in the changeset.

  This function is used to work with embeds as a whole. For embeds with
  cardinality one, `nil` can be used to remove the existing entry. For
  embeds with many entries, an empty list may be given instead.

  If the embed has no changes, it will be skipped. If the embed is
  invalid, the changeset will be marked as invalid.

  The list of supported values and their behaviour is described in
  `put_assoc/4`. If the given value is not any of values listed there,
  it will raise.

  Although this function accepts an `opts` argument, there are no options
  currently supported by `put_embed/4`.

  """
  def put_embed(%Ecto.Changeset{} = changeset, name, value, opts) do
    put_relation(:embed, changeset, name, value, opts)
  end

  @doc ~S"""
  Traverses changeset errors and applies the given function to error messages.

  This function is particularly useful when associations and embeds
  are cast in the changeset as it will traverse all associations and
  embeds and place all errors in a series of nested maps.

  A changeset is supplied along with a function to apply to each
  error message as the changeset is traversed. The error message
  function receives an error tuple `{msg, opts}`, for example:

      {"should be at least %{count} characters", [count: 3, validation: :length, min: 3]}

  ## Examples

      iex> traverse_errors(changeset, fn {msg, opts} ->
      ...>   Regex.replace(~r"%{(\w+)}", msg, fn _, key ->
      ...>     opts |> Keyword.get(String.to_existing_atom(key), key) |> to_string()
      ...>   end)
      ...> end)
      %{title: ["should be at least 3 characters"]}

  Optionally function can accept three arguments: `changeset`, `field`
  and error tuple `{msg, opts}`. It is useful whenever you want to extract
  validations rules from `changeset.validations` to build detailed error
  description.

  """
  def traverse_errors(%Ecto.Changeset{errors: errors, changes: changes, types: types} = changeset, msg_func) when :erlang.is_function(msg_func, 1) or :erlang.is_function(msg_func, 3) do
    merge_related_keys(merge_keyword_keys(Enum.reverse(errors), msg_func, changeset), changes, types, msg_func, &traverse_errors/2)
  end

  @doc ~S"""
  Traverses changeset validations and applies the given function to validations.

  This behaves the same as `traverse_errors/2`, but operates on changeset
  validations instead of errors.

  ## Examples

      iex> traverse_validations(changeset, &(&1))
      %{title: [format: ~r/pattern/, length: [min: 1, max: 20]]}

      iex> traverse_validations(changeset, fn
      ...>   {:length, opts} -> {:length, "#{Keyword.get(opts, :min, 0)}-#{Keyword.get(opts, :max, 32)}"}
      ...>   {:format, %Regex{source: source}} -> {:format, "/#{source}/"}
      ...>   {other, opts} -> {other, inspect(opts)}
      ...> end)
      %{title: [format: "/pattern/", length: "1-20"]}

  """
  def traverse_validations(%Ecto.Changeset{validations: validations, changes: changes, types: types} = changeset, msg_func) when :erlang.is_function(msg_func, 1) or :erlang.is_function(msg_func, 3) do
    merge_related_keys(merge_keyword_keys(Enum.reverse(validations), msg_func, changeset), changes, types, msg_func, &traverse_validations/2)
  end

  def unique_constraint(changeset, field) do
    super(changeset, field, [])
  end

  @doc ~S"""
  Checks for a unique constraint in the given field or list of fields.

  The unique constraint works by relying on the database to check
  if the unique constraint has been violated or not and, if so,
  Ecto converts it into a changeset error.

  In order to use the uniqueness constraint, the first step is
  to define the unique index in a migration:

      create unique_index(:users, [:email])

  Now that a constraint exists, when modifying users, we could
  annotate the changeset with a unique constraint so Ecto knows
  how to convert it into an error message:

      cast(user, params, [:email])
      |> unique_constraint(:email)

  Now, when invoking `c:Ecto.Repo.insert/2` or `c:Ecto.Repo.update/2`, if the
  email already exists, it will be converted into an error and
  `{:error, changeset}` returned by the repository. Note that the error
  will occur only after hitting the database so it will not be visible
  until all other validations pass.

  ## Options

    * `:message` - the message in case the constraint check fails,
      defaults to "has already been taken"

    * `:name` - the constraint name. By default, the constraint
      name is inferred from the table + field(s). May be required
      explicitly for complex cases

    * `:match` - how the changeset constraint name is matched against the
      repo constraint, may be `:exact` or `:suffix`. Defaults to `:exact`.
      `:suffix` matches any repo constraint which `ends_with?` `:name`
       to this changeset constraint.

  ## Complex constraints

  Because the constraint logic is in the database, we can leverage
  all the database functionality when defining them. For example,
  let's suppose the e-mails are scoped by company id:

      # In migration
      create unique_index(:users, [:email, :company_id])

      # In the changeset function
      cast(user, params, [:email])
      |> unique_constraint([:email, :company_id])

  The first field name, `:email` in this case, will be used as the error
  key to the changeset errors keyword list. For example, the above
  `unique_constraint/3` would generate something like:

      Repo.insert!(%User{email: "john@elixir.org", company_id: 1})
      changeset = User.changeset(%User{}, %{email: "john@elixir.org", company_id: 1})
      {:error, changeset} = Repo.insert(changeset)
      changeset.errors #=> [email: {"has already been taken", []}]

  In complex cases, instead of relying on name inference, it may be best
  to set the constraint name explicitly:

      # In the migration
      create unique_index(:users, [:email, :company_id], name: :users_email_company_id_index)

      # In the changeset function
      cast(user, params, [:email])
      |> unique_constraint(:email, name: :users_email_company_id_index)

  ### Partitioning

  If your table is partitioned, then your unique index might look different
  per partition, e.g. Postgres adds p<number> to the middle of your key, like:

      users_p0_email_key
      users_p1_email_key
      ...
      users_p99_email_key

  In this case you can use the name and suffix options together to match on
  these dynamic indexes, like:

      cast(user, params, [:email])
      |> unique_constraint(:email, name: :email_key, match: :suffix)

  ## Case sensitivity

  Unfortunately, different databases provide different guarantees
  when it comes to case-sensitiveness. For example, in MySQL, comparisons
  are case-insensitive by default. In Postgres, users can define case
  insensitive column by using the `:citext` type/extension. In your migration:

      execute "CREATE EXTENSION IF NOT EXISTS citext"
      create table(:users) do
        ...
        add :email, :citext
        ...
      end

  If for some reason your database does not support case insensitive columns,
  you can explicitly downcase values before inserting/updating them:

      cast(data, params, [:email])
      |> update_change(:email, &String.downcase/1)
      |> unique_constraint(:email)


  """
  def unique_constraint(changeset, field, opts) when is_atom(field) do
    unique_constraint(changeset, [field], opts)
  end

  def unique_constraint(changeset, [first_field | _] = fields, opts) do
    (
      constraint = opts[:name] || x
      message = message(opts, "has already been taken")
      match_type = Keyword.get(opts, :match, :exact)
      add_constraint(changeset, :unique, String.Chars.to_string(constraint), match_type, first_field, message)
    )
  end

  def unsafe_validate_unique(changeset, fields, repo) do
    super(changeset, fields, repo, [])
  end

  @doc ~S"""
  Validates that no existing record with a different primary key
  has the same values for these fields.

  This function exists to provide quick feedback to users of your
  application. It should not be relied on for any data guarantee as it
  has race conditions and is inherently unsafe. For example, if this
  check happens twice in the same time interval (because the user
  submitted a form twice), both checks may pass and you may end-up with
  duplicate entries in the database. Therefore, a `unique_constraint/3`
  should also be used to ensure your data won't get corrupted.

  However, because constraints are only checked if all validations
  succeed, this function can be used as an early check to provide
  early feedback to users, since most conflicting data will have been
  inserted prior to the current validation phase.

  ## Options

    * `:message` - the message in case the constraint check fails,
      defaults to "has already been taken".

    * `:match` - how the changeset constraint name is matched against the
      repo constraint, may be `:exact` or `:suffix`. Defaults to `:exact`.
      `:suffix` matches any repo constraint which `ends_with?` `:name`
       to this changeset constraint.

    * `:error_key` - the key to which changeset error will be added when
      check fails, defaults to the first field name of the given list of
      fields.

    * `:prefix` - the prefix to run the query on (such as the schema path
      in Postgres or the database in MySQL). See `Ecto.Repo` documentation
      for more information.

    * `:repo_opts` - the options to pass to the `Ecto.Repo` call.

    * `:query` - the base query to use for the check. Defaults to the schema of
      the changeset. If the primary key is set, a clause will be added to exclude
      the changeset row itself from the check.

  ## Examples

      unsafe_validate_unique(changeset, :city_name, repo)
      unsafe_validate_unique(changeset, [:city_name, :state_name], repo)
      unsafe_validate_unique(changeset, [:city_name, :state_name], repo, message: "city must be unique within state")
      unsafe_validate_unique(changeset, [:city_name, :state_name], repo, prefix: "public")
      unsafe_validate_unique(changeset, [:city_name, :state_name], repo, query: from(c in City, where: is_nil(c.deleted_at)))


  """
  def unsafe_validate_unique(changeset, fields, repo, opts) when is_list(opts) do
    (
      fields = List.wrap(fields)
      {repo_opts, opts} = Keyword.pop(opts, :repo_opts, [])
      {validations, schema} = case(changeset) do
        %Ecto.Changeset{validations: validations, data: %schema{}} ->
          {validations, schema}
        %Ecto.Changeset{} ->
          raise(ArgumentError, "unsafe_validate_unique/4 does not work with schemaless changesets")
      end
      changeset = %{changeset | validations: [{:unsafe_unique, fields} | validations]}
      where_clause = for(field <- fields) do
        {field, get_field(changeset, field)}
      end
      any_prior_errors_for_fields? = Enum.any?(changeset.errors(), fn x1 -> Enum.member?(fields, elem(x1, 0)) end)
      unrelated_changes? = Enum.all?(fields, fn x1 -> not(Map.has_key?(changeset.changes(), x1)) end)
      any_nil_values_for_fields? = Enum.any?(where_clause, fn x1 -> is_nil(elem(x1, 1)) end)
      if(unrelated_changes? || x || x) do
        query = (
          query = (
            query = Ecto.Query.Builder.Filter.filter!(:where, :and, maybe_exclude_itself(Keyword.get(opts, :query, schema), schema, changeset), where_clause, 0, "/Users/cliftonmcintosh/code/gringotts/deps/ecto/lib/ecto/changeset.ex", 1891)
            Ecto.Query.Builder.Select.apply(query, %Ecto.Query.SelectExpr{fields: nil, expr: true, params: [], file: "/Users/cliftonmcintosh/code/gringotts/deps/ecto/lib/ecto/changeset.ex", line: 1892, take: %{}})
          )
          Ecto.Query.Builder.LimitOffset.apply(query, :limit, %Ecto.Query.QueryExpr{expr: 1, params: [], file: "/Users/cliftonmcintosh/code/gringotts/deps/ecto/lib/ecto/changeset.ex", line: 1893})
        )
        query = if(prefix = opts[:prefix]) do
          query
        else
          Ecto.Query.put_query_prefix(query, prefix)
        end
        if(repo.one(query, repo_opts)) do
          changeset
        else
          error_key = Keyword.get(opts, :error_key, :erlang.hd(fields))
          add_error(changeset, error_key, message(opts, "has already been taken"), validation: :unsafe_unique, fields: fields)
        end
      else
        changeset
      end
    )
  end

  @doc ~S"""
  Updates a change.

  The given `function` is invoked with the change value only if there
  is a change for the given `key`. Note that the value of the change
  can still be `nil` (unless the field was marked as required on `validate_required/3`).

  ## Examples

      iex> changeset = change(%Post{}, %{impressions: 1})
      iex> changeset = update_change(changeset, :impressions, &(&1 + 1))
      iex> changeset.changes.impressions
      2


  """
  def update_change(%Ecto.Changeset{changes: changes} = changeset, key, function) when is_atom(key) do
    case(:maps.find(key, changes)) do
      {:ok, value} ->
        put_change(changeset, key, function.(value))
      :error ->
        changeset
    end
  end

  def validate_acceptance(x0, x1) do
    super(x0, x1, [])
  end

  @doc ~S"""
  Validates the given parameter is true.

  Note this validation only checks the parameter itself is true, never
  the field in the schema. That's because acceptance parameters do not need
  to be persisted, as by definition they would always be stored as `true`.

  ## Options

    * `:message` - the message on failure, defaults to "must be accepted"

  ## Examples

      validate_acceptance(changeset, :terms_of_service)
      validate_acceptance(changeset, :rules, message: "please accept rules")


  """
  def validate_acceptance(%{params: params} = changeset, field, opts) do
    (
      errors = validate_acceptance_errors(params, field, opts)
      %{changeset | validations: [{field, {:acceptance, opts}} | changeset.validations()], errors: errors ++ changeset.errors(), valid?: changeset.valid?() and errors == []}
    )
  end

  @doc ~S"""
  Validates the given `field` change.

  It invokes the `validator` function to perform the validation
  only if a change for the given `field` exists and the change
  value is not `nil`. The function must return a list of errors
  (with an empty list meaning no errors).

  In case there's at least one error, the list of errors will be appended to the
  `:errors` field of the changeset and the `:valid?` flag will be set to
  `false`.

  ## Examples

      iex> changeset = change(%Post{}, %{title: "foo"})
      iex> changeset = validate_change changeset, :title, fn :title, title  ->
      ...>   # Value must not be "foo"!
      ...>   if title == "foo" do
      ...>     [title: "cannot be foo"]
      ...>   else
      ...>     []
      ...>   end
      ...> end
      iex> changeset.errors
      [title: {"cannot be foo", []}]


  """
  def validate_change(%Ecto.Changeset{} = changeset, field, validator) when is_atom(field) do
    (
      %{changes: changes, errors: errors} = changeset
      ensure_field_exists!(changeset, field)
      value = Map.get(changes, field)
      new = case(is_nil(value)) do
        false ->
          validator.(field, value)
        true ->
          []
      end
      new = Enum.map(new, fn
       {key, val} when is_atom(key) and is_binary(val) ->
          {key, {val, []}}
        {key, {val, opts}} when is_atom(key) and is_binary(val) and is_list(opts) ->
          {key, {val, opts}}
      end)
      case(new) do
        [] ->
          changeset
        [_ | _] ->
          %{changeset | errors: new ++ errors, valid?: false}
      end
    )
  end

  @doc ~S"""
  Stores the validation `metadata` and validates the given `field` change.

  Similar to `validate_change/3` but stores the validation metadata
  into the changeset validators. The validator metadata is often used
  as a reflection mechanism, to automatically generate code based on
  the available validations.

  ## Examples

      iex> changeset = change(%Post{}, %{title: "foo"})
      iex> changeset = validate_change changeset, :title, :useless_validator, fn
      ...>   _, _ -> []
      ...> end
      iex> changeset.validations
      [title: :useless_validator]


  """
  def validate_change(%Ecto.Changeset{validations: validations} = changeset, field, metadata, validator) do
    (
      changeset = %{changeset | validations: [{field, metadata} | validations]}
      validate_change(changeset, field, validator)
    )
  end

  def validate_confirmation(x0, x1) do
    super(x0, x1, [])
  end

  @doc ~S"""
  Validates that the given parameter matches its confirmation.

  By calling `validate_confirmation(changeset, :email)`, this
  validation will check if both "email" and "email_confirmation"
  in the parameter map matches. Note this validation only looks
  at the parameters themselves, never the fields in the schema.
  As such as, the "email_confirmation" field does not need to be
  added as a virtual field in your schema.

  Note that if the confirmation field is nil or missing, this does
  not add a validation error. You can specify that the confirmation
  parameter is required in the options (see below).

  ## Options

    * `:message` - the message on failure, defaults to "does not match confirmation"
    * `:required` - boolean, sets whether existence of confirmation parameter
      is required for addition of error. Defaults to false

  ## Examples

      validate_confirmation(changeset, :email)
      validate_confirmation(changeset, :password, message: "does not match password")

      cast(data, params, [:password])
      |> validate_confirmation(:password, message: "does not match password")


  """
  def validate_confirmation(%{params: params} = changeset, field, opts) when is_map(params) do
    (
      param = Atom.to_string(field)
      error_param = <<String.Chars.to_string(param)::binary(), "_confirmation"::binary()>>
      error_field = String.to_atom(error_param)
      value = Map.get(params, param)
      errors = case(:maps.find(error_param, params)) do
        {:ok, ^value} ->
          []
        {:ok, _} ->
          [{error_field, {message(opts, "does not match confirmation"), [validation: :confirmation]}}]
        :error ->
          confirmation_missing(opts, error_field)
      end
      %{changeset | validations: [{field, {:confirmation, opts}} | changeset.validations()], errors: errors ++ changeset.errors(), valid?: changeset.valid?() and errors == []}
    )
  end

  def validate_confirmation(%{params: nil} = changeset, _, _) do
    changeset
  end

  def validate_exclusion(changeset, field, data) do
    super(changeset, field, data, [])
  end

  @doc ~S"""
  Validates a change is not included in the given enumerable.

  ## Options

    * `:message` - the message on failure, defaults to "is reserved"

  ## Examples

      validate_exclusion(changeset, :name, ~w(admin superadmin))


  """
  def validate_exclusion(changeset, field, data, opts) do
    validate_change(changeset, field, {:exclusion, data}, fn _, value ->
      type = :maps.get(field, changeset.types())
      if(Ecto.Type.include?(type, value, data)) do
        []
      else
        [{field, {message(opts, "is reserved"), [validation: :exclusion, enum: data]}}]
      end
    end)
  end

  def validate_format(changeset, field, format) do
    super(changeset, field, format, [])
  end

  @doc ~S"""
  Validates a change has the given format.

  The format has to be expressed as a regular expression.

  ## Options

    * `:message` - the message on failure, defaults to "has invalid format"

  ## Examples

      validate_format(changeset, :email, ~r/@/)


  """
  def validate_format(changeset, field, format, opts) do
    validate_change(changeset, field, {:format, format}, fn _, value -> if(Kernel.=~(value, format)) do
      [{field, {message(opts, "has invalid format"), [validation: :format]}}]
    else
      []
    end end)
  end

  def validate_inclusion(changeset, field, data) do
    super(changeset, field, data, [])
  end

  @doc ~S"""
  Validates a change is included in the given enumerable.

  ## Options

    * `:message` - the message on failure, defaults to "is invalid"

  ## Examples

      validate_inclusion(changeset, :cardinal_direction, ["north", "east", "south", "west"])
      validate_inclusion(changeset, :age, 0..99)


  """
  def validate_inclusion(changeset, field, data, opts) do
    validate_change(changeset, field, {:inclusion, data}, fn _, value ->
      type = :maps.get(field, changeset.types())
      if(Ecto.Type.include?(type, value, data)) do
        [{field, {message(opts, "is invalid"), [validation: :inclusion, enum: data]}}]
      else
        []
      end
    end)
  end

  @doc ~S"""
  Validates a change is a string or list of the given length.

  Note that the length of a string is counted in graphemes by default. If using
  this validation to match a character limit of a database backend,
  it's likely that the limit ignores graphemes and limits the number
  of unicode characters. Then consider using the `:count` option to
  limit the number of codepoints (`:codepoints`), or limit the number of bytes (`:bytes`).

  ## Options

    * `:is` - the length must be exactly this value
    * `:min` - the length must be greater than or equal to this value
    * `:max` - the length must be less than or equal to this value
    * `:count` - what length to count for string, `:graphemes` (default), `:codepoints` or `:bytes`
    * `:message` - the message on failure, depending on the validation, is one of:
      * for strings:
        * "should be %{count} character(s)"
        * "should be at least %{count} character(s)"
        * "should be at most %{count} character(s)"
      * for binary:
        * "should be %{count} byte(s)"
        * "should be at least %{count} byte(s)"
        * "should be at most %{count} byte(s)"
      * for lists:
        * "should have %{count} item(s)"
        * "should have at least %{count} item(s)"
        * "should have at most %{count} item(s)"

  ## Examples

      validate_length(changeset, :title, min: 3)
      validate_length(changeset, :title, max: 100)
      validate_length(changeset, :title, min: 3, max: 100)
      validate_length(changeset, :code, is: 9)
      validate_length(changeset, :topics, is: 2)
      validate_length(changeset, :icon, count: :bytes, max: 1024 * 16)


  """
  def validate_length(changeset, field, opts) when is_list(opts) do
    validate_change(changeset, field, {:length, opts}, fn _, value ->
      count_type = opts[:count] || x
      {type, length} = case({value, count_type}) do
        {value, :codepoints} when is_binary(value) ->
          {:string, codepoints_length(value, 0)}
        {value, :graphemes} when is_binary(value) ->
          {:string, String.length(value)}
        {value, :bytes} when is_binary(value) ->
          {:binary, byte_size(value)}
        {value, _} when is_list(value) ->
          {:list, list_length(changeset, field, value)}
      end
      error = (is = opts[:is]) && wrong_length(type, length, is, opts) || x || x
      if(error) do
        []
      else
        [{field, error}]
      end
    end)
  end

  @doc ~S"""
  Validates the properties of a number.

  ## Options

    * `:less_than`
    * `:greater_than`
    * `:less_than_or_equal_to`
    * `:greater_than_or_equal_to`
    * `:equal_to`
    * `:not_equal_to`
    * `:message` - the message on failure, defaults to one of:
      * "must be less than %{number}"
      * "must be greater than %{number}"
      * "must be less than or equal to %{number}"
      * "must be greater than or equal to %{number}"
      * "must be equal to %{number}"
      * "must be not equal to %{number}"

  ## Examples

      validate_number(changeset, :count, less_than: 3)
      validate_number(changeset, :pi, greater_than: 3, less_than: 4)
      validate_number(changeset, :the_answer_to_life_the_universe_and_everything, equal_to: 42)


  """
  def validate_number(changeset, field, opts) do
    validate_change(changeset, field, {:number, opts}, fn field, value ->
      {message, opts} = Keyword.pop(opts, :message)
      Enum.find_value(opts, [], fn {spec_key, target_value} -> case(:maps.find(spec_key, %{equal_to: {&:erlang.==/2, "must be equal to %{number}"}, greater_than: {&:erlang.>/2, "must be greater than %{number}"}, greater_than_or_equal_to: {&:erlang.>=/2, "must be greater than or equal to %{number}"}, less_than: {&:erlang.</2, "must be less than %{number}"}, less_than_or_equal_to: {&:erlang."=<"/2, "must be less than or equal to %{number}"}, not_equal_to: {&:erlang."/="/2, "must be not equal to %{number}"}})) do
        {:ok, {spec_function, default_message}} ->
          validate_number(field, value, message || x, spec_key, spec_function, target_value)
        :error ->
          supported_options = Enum.map_join(:maps.keys(%{equal_to: {&:erlang.==/2, "must be equal to %{number}"}, greater_than: {&:erlang.>/2, "must be greater than %{number}"}, greater_than_or_equal_to: {&:erlang.>=/2, "must be greater than or equal to %{number}"}, less_than: {&:erlang.</2, "must be less than %{number}"}, less_than_or_equal_to: {&:erlang."=<"/2, "must be less than or equal to %{number}"}, not_equal_to: {&:erlang."/="/2, "must be not equal to %{number}"}}), "\n", fn x1 -> <<"  * "::binary(), Kernel.inspect(x1)::binary()>> end)
          raise(ArgumentError, <<"unknown option "::binary(), Kernel.inspect(spec_key)::binary(), " given to validate_number/3\n\nThe supported options are:\n\n"::binary(), String.Chars.to_string(supported_options)::binary(), "\n"::binary()>>)
      end end)
    end)
  end

  def validate_required(x0, x1) do
    super(x0, x1, [])
  end

  @doc ~S"""
  Validates that one or more fields are present in the changeset.

  You can pass a single field name or a list of field names that
  are required.

  If the value of a field is `nil` or a string made only of whitespace,
  the changeset is marked as invalid, the field is removed from the
  changeset's changes, and an error is added. An error won't be added if
  the field already has an error.

  If a field is given to `validate_required/3` but it has not been passed
  as parameter during `cast/3` (i.e. it has not been changed), then
  `validate_required/3` will check for its current value in the data.
  If the data contains an non-empty value for the field, then no error is
  added. This allows developers to use `validate_required/3` to perform
  partial updates. For example, on `insert` all fields would be required,
  because their default values on the data are all `nil`, but on `update`,
  if you don't want to change a field that has been previously set,
  you are not required to pass it as a parameter, since `validate_required/3`
  won't add an error for missing changes as long as the value in the
  data given to the `changeset` is not empty.

  Do not use this function to validate associations that are required,
  instead pass the `:required` option to `cast_assoc/3`.

  Opposite to other validations, calling this function does not store
  the validation under the `changeset.validations` key. Instead, it
  stores all required fields under `changeset.required`.

  ## Options

    * `:message` - the message on failure, defaults to "can't be blank"
    * `:trim` - a boolean that sets whether whitespaces are removed before
      running the validation on binaries/strings, defaults to true

  ## Examples

      validate_required(changeset, :title)
      validate_required(changeset, [:title, :body])


  """
  def validate_required(%Ecto.Changeset{} = changeset, fields, opts) when not(is_nil(fields)) do
    (
      %{required: required, errors: errors, changes: changes} = changeset
      trim = Keyword.get(opts, :trim, true)
      fields = List.wrap(fields)
      fields_with_errors = for(field <- fields, missing?(changeset, field, trim), ensure_field_exists!(changeset, field), is_nil(errors[field])) do
        field
      end
      case(fields_with_errors) do
        [] ->
          %{changeset | required: fields ++ required}
        _ ->
          message = message(opts, "can't be blank")
          new_errors = Enum.map(fields_with_errors, fn x1 -> {x1, {message, [validation: :required]}} end)
          changes = Map.drop(changes, fields_with_errors)
          %{changeset | changes: changes, required: fields ++ required, errors: new_errors ++ errors, valid?: false}
      end
    )
  end

  def validate_subset(changeset, field, data) do
    super(changeset, field, data, [])
  end

  @doc ~S"""
  Validates a change, of type enum, is a subset of the given enumerable.

  This validates if a list of values belongs to the given enumerable.
  If you need to validate if a single value is inside the given enumerable,
  you should use `validate_inclusion/4` instead.

  ## Options

    * `:message` - the message on failure, defaults to "has an invalid entry"

  ## Examples

      validate_subset(changeset, :pets, ["cat", "dog", "parrot"])
      validate_subset(changeset, :lottery_numbers, 0..99)


  """
  def validate_subset(changeset, field, data, opts) do
    validate_change(changeset, field, {:subset, data}, fn _, value ->
      {:array, element_type} = :maps.get(field, changeset.types())
      case(Enum.any?(value, fn element -> not(Ecto.Type.include?(element_type, element, data)) end)) do
        true ->
          [{field, {message(opts, "has an invalid entry"), [validation: :subset, enum: data]}}]
        false ->
          []
      end
    end)
  end

  @doc ~S"""
  Returns a keyword list of the validations for this changeset.

  The keys in the list are the names of fields, and the values are a
  validation associated with the field. A field may occur multiple
  times in the list.

  ## Example

      %Post{}
      |> change()
      |> validate_format(:title, ~r/^\w+:\s/, message: "must start with a topic")
      |> validate_length(:title, max: 100)
      |> validations()
      #=> [
        title: {:length, [ max: 100 ]},
        title: {:format, ~r/^\w+:\s/}
      ]

  The following validations may be included in the result. The list is
  not necessarily exhaustive. For example, custom validations written
  by the developer will also appear in our return value.

  This first group contains validations that hold a keyword list of validators.
  This list may also include a `:message` key.

    * `{:length, [option]}`

      * `min: n`
      * `max: n`
      * `is: n`
      * `count: :graphemes | :codepoints`

    * `{:number,  [option]}`

      * `equal_to: n`
      * `greater_than: n`
      * `greater_than_or_equal_to: n`
      * `less_than: n`
      * `less_than_or_equal_to: n`

  The other validators simply take a value:

    * `{:exclusion, Enum.t}`
    * `{:format, ~r/pattern/}`
    * `{:inclusion, Enum.t}`
    * `{:subset, Enum.t}`

  Note that calling `validate_required/3` does not store the validation under the
  `changeset.validations` key (and so won't be included in the result of this
  function). The required fields are stored under the `changeset.required` key.

  """
  def validations(%Ecto.Changeset{validations: validations}) do
    validations
  end

  # Private Functions

  defp unquote(:"-__struct__/1-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-apply_changes/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-cast/6-fun-1-")(p0, p1, p2, p3, p4, p5, p6) do
    # body not decompiled
  end

  defp unquote(:"-cast_relation/4-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-cast_relation/4-fun-1-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-cast_relation/4-fun-2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-cast_relation/4-fun-3-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-cast_relation/4-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-convert_params/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-delete_change/2-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-force_change/3-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-force_change/3-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-force_update/2-fun-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-force_update/2-fun-1-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-fun.cast_key/1-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-fun.increment_with_rollover/1-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-fun.traverse_errors/2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-fun.traverse_validations/2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-get_changed/6-fun-0-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-maybe_exclude_itself/3-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-maybe_exclude_itself/3-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-maybe_exclude_itself/3-fun-2-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-maybe_exclude_itself/3-fun-3-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-maybe_exclude_itself/3-fun-4-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-merge_keyword_keys/3-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-merge_keyword_keys/3-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-merge_keyword_keys/3-fun-2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-merge_keyword_keys/3-fun-3-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-merge_related_keys/5-fun-0-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-merge_related_keys/5-fun-1-")(p0, p1, p2, p3, p4) do
    # body not decompiled
  end

  defp unquote(:"-on_cast_default/2-fun-0-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-optimistic_lock/3-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-optimistic_lock/3-fun-1-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-optimistic_lock/3-fun-2-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-optimistic_lock/3-fun-3-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-optimistic_lock/3-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-raise_invalid_assoc/2-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-unique_index_name/2-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-unsafe_validate_unique/4-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-unsafe_validate_unique/4-fun-1-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-unsafe_validate_unique/4-fun-2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-unsafe_validate_unique/4-fun-3-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-validate_change/3-fun-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-validate_exclusion/4-fun-0-")(p0, p1, p2, p3, p4, p5) do
    # body not decompiled
  end

  defp unquote(:"-validate_format/4-fun-0-")(p0, p1, p2, p3, p4) do
    # body not decompiled
  end

  defp unquote(:"-validate_inclusion/4-fun-0-")(p0, p1, p2, p3, p4, p5) do
    # body not decompiled
  end

  defp unquote(:"-validate_length/3-fun-0-")(p0, p1, p2, p3, p4) do
    # body not decompiled
  end

  defp unquote(:"-validate_number/3-fun-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-validate_number/3-fun-1-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-validate_number/3-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-validate_required/3-fun-0-")(p0, p1, p2, p3, p4) do
    # body not decompiled
  end

  defp unquote(:"-validate_required/3-fun-1-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-validate_subset/4-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-validate_subset/4-fun-1-")(p0, p1, p2, p3, p4, p5) do
    # body not decompiled
  end

  defp add_constraint(changeset, type, constraint, match, field, message) do
    add_constraint(changeset, type, constraint, match, field, message, type)
  end

  defp add_constraint(%Ecto.Changeset{constraints: constraints} = changeset, type, constraint, match, field, error_message, error_type) when is_binary(constraint) and is_atom(field) and is_binary(error_message) do
    (
      case(match === :exact or match === :suffix or match === :prefix) do
        false ->
          raise(ArgumentError, <<"invalid match type: "::binary(), Kernel.inspect(match)::binary(), ". Allowed match types: "::binary(), Kernel.inspect([:exact, :suffix, :prefix])::binary()>>)
        true ->
          nil
      end
      constraint = %{constraint: constraint, error_message: error_message, error_type: error_type, field: field, match: match, type: type}
      %{changeset | constraints: [constraint | constraints]}
    )
  end

  defp apply_relation_changes(acc, key, relation, value) do
    (
      relation_changed = Ecto.Changeset.Relation.apply_changes(relation, value)
      acc = :maps.put(key, relation_changed, acc)
      with(%Ecto.Association.BelongsTo{related_key: related_key} <- relation, %{^related_key => id} <- relation_changed) do
        :maps.put(relation.owner_key(), id, acc)
      else
        _ ->
          acc
      end
    )
  end

  defp cast(%{} = data, %{} = types, %{} = changes, :invalid, permitted, _opts) when is_list(permitted) do
    (
      _ = Enum.each(permitted, &cast_key/1)
      %Ecto.Changeset{action: nil, constraints: [], empty_values: [""], filters: %{}, prepare: [], repo: nil, repo_opts: [], required: [], validations: [], params: nil, data: data, valid?: false, errors: [], changes: changes, types: types}
    )
  end

  defp cast(%{} = data, %{} = types, %{} = changes, %{} = params, permitted, opts) when is_list(permitted) do
    (
      empty_values = Keyword.get(opts, :empty_values, [""])
      params = convert_params(params)
      defaults = case(data) do
        %{__struct__: struct} ->
          struct.__struct__()
        %{} ->
          %{}
      end
      {changes, errors, valid?} = Enum.reduce(permitted, {changes, [], true}, fn x1, x2 -> process_param(x1, params, types, data, empty_values, defaults, x2) end)
      %Ecto.Changeset{action: nil, constraints: [], empty_values: [""], filters: %{}, prepare: [], repo: nil, repo_opts: [], required: [], validations: [], params: params, data: data, valid?: valid?, errors: Enum.reverse(errors), changes: changes, types: types}
    )
  end

  defp cast(%{}, %{}, %{}, params, permitted, _opts) when is_list(permitted) do
    raise(Ecto.CastError, type: :map, value: params, message: <<"expected params to be a :map, got: `"::binary(), Kernel.inspect(params)::binary(), "`"::binary()>>)
  end

  defp cast_field(key, param_key, type, params, current, empty_values, defaults, valid?) do
    case(params) do
      %{^param_key => value} ->
        value = if(Enum.member?(empty_values, value)) do
          value
        else
          Map.get(defaults, key)
        end
        case(Ecto.Type.cast(type, value)) do
          {:ok, value} ->
            if(Ecto.Type.equal?(type, current, value)) do
              {:ok, value, valid?}
            else
              :missing
            end
          :error ->
            {:invalid, []}
          {:error, custom_errors} when is_list(custom_errors) ->
            {:invalid, custom_errors}
        end
      _ ->
        :missing
    end
  end

  defp cast_key(key) when is_atom(key) do
    {key, Atom.to_string(key)}
  end

  defp cast_key(key) do
    raise(ArgumentError, <<"cast/3 expects a list of atom keys, got: `"::binary(), Kernel.inspect(key)::binary(), "`"::binary()>>)
  end

  defp cast_merge(cs1, cs2) do
    (
      new_params = cs1.params() || x && Map.merge(cs1.params() || x, cs2.params() || x)
      new_changes = Map.merge(cs1.changes(), cs2.changes())
      new_errors = Enum.uniq(cs1.errors() ++ cs2.errors())
      new_required = Enum.uniq(cs1.required() ++ cs2.required())
      new_types = cs1.types() || x
      new_valid? = cs1.valid?() and cs2.valid?()
      %{cs1 | params: new_params, valid?: new_valid?, errors: new_errors, types: new_types, changes: new_changes, required: new_required}
    )
  end

  defp cast_relation(type, %Ecto.Changeset{data: data, types: types}, _name, _opts) when is_nil(data) or is_nil(types) do
    raise(ArgumentError, <<"cast_"::binary(), String.Chars.to_string(type)::binary(), "/3 expects the changeset to be cast. "::binary(), "Please call cast/4 before calling cast_"::binary(), String.Chars.to_string(type)::binary(), "/3"::binary()>>)
  end

  defp cast_relation(type, %Ecto.Changeset{} = changeset, key, opts) do
    (
      {key, param_key} = cast_key(key)
      %{data: data, types: types, params: params, changes: changes} = changeset
      %{related: related} = relation = relation!(:cast, type, key, Map.get(types, key))
      params = params || x
      {changeset, required?} = if(opts[:required]) do
        {changeset, false}
      else
        {Map.update!(changeset, :required, fn x1 -> [key | x1] end), true}
      end
      on_cast = Keyword.get_lazy(opts, :with, fn -> on_cast_default(type, related) end)
      original = Map.get(data, key)
      changeset = case(:maps.find(param_key, params)) do
        {:ok, value} ->
          current = Ecto.Changeset.Relation.load!(data, original)
          case(Ecto.Changeset.Relation.cast(relation, data, value, current, on_cast)) do
            {:ok, change, relation_valid?} when change != original ->
              valid? = changeset.valid?() and relation_valid?
              changes = :maps.put(key, change, changes)
              changeset = %{force_update(changeset, opts) | changes: changes, valid?: valid?}
              missing_relation(changeset, key, current, required?, relation, opts)
            {:error, {message, meta}} ->
              meta = [validation: type] ++ meta
              error = {key, {message(opts, :invalid_message, message), meta}}
              %{changeset | errors: [error | changeset.errors()], valid?: false}
            _ ->
              missing_relation(changeset, key, current, required?, relation, opts)
          end
        :error ->
          missing_relation(changeset, key, original, required?, relation, opts)
      end
      elem(Map.get_and_update!(changeset, :types, fn x -> Access.get_and_update(x, key, fn x -> {nil, (fn {type, relation} -> {type, %{relation | on_cast: on_cast}} end).(x)} end) end), 1)
    )
  end

  defp cast_type!(types, key) do
    case(types) do
      %{^key => {tag, _}} when tag === :embed or tag === :assoc ->
        raise(RuntimeError, <<"casting "::binary(), String.Chars.to_string(tag)::binary(), "s with cast/4 for "::binary(), Kernel.inspect(key)::binary(), " field is not supported, use cast_"::binary(), String.Chars.to_string(tag)::binary(), "/3 instead"::binary()>>)
      %{^key => type} ->
        type
      _ ->
        known_fields = Enum.map_join(:maps.keys(types), ", ", &Kernel.inspect/1)
        raise(ArgumentError, <<"unknown field `"::binary(), Kernel.inspect(key)::binary(), "` given to cast. Either the field does not exist or it is a "::binary(), ":through association (which are read-only). The known fields are: "::binary(), String.Chars.to_string(known_fields)::binary()>>)
    end
  end

  defp change_as_field(types, key, value) do
    case(Map.get(types, key)) do
      {tag, relation} when tag === :embed or tag === :assoc ->
        Ecto.Changeset.Relation.apply_changes(relation, value)
      _other ->
        value
    end
  end

  defp codepoints_length(<<_::utf8(), rest::binary()>>, acc) do
    codepoints_length(rest, acc + 1)
  end

  defp codepoints_length(<<_::integer(), rest::binary()>>, acc) do
    codepoints_length(rest, acc + 1)
  end

  defp codepoints_length(<<>>, acc) do
    acc
  end

  defp confirmation_missing(opts, error_field) do
    (
      required = Keyword.get(opts, :required, false)
      if(required) do
        []
      else
        [{error_field, {message(opts, "can't be blank"), [validation: :required]}}]
      end
    )
  end

  defp convert_params(params) do
    case(:maps.next(:maps.iterator(params))) do
      {key, _, _} when is_atom(key) ->
        for({key, value} <- params, do: case(is_atom(key)) do
          false ->
            raise(Ecto.CastError, type: :map, value: params, message: <<"expected params to be a map with atoms or string keys, "::binary(), "got a map with mixed keys: "::binary(), Kernel.inspect(params)::binary()>>)
          true ->
            {Atom.to_string(key), value}
        end, into: %{})
      _ ->
        params
    end
  end

  defp data_as_field(data, types, key, value) do
    case(Map.get(types, key)) do
      {tag, _relation} when tag === :embed or tag === :assoc ->
        Ecto.Changeset.Relation.load!(data, value)
      _other ->
        value
    end
  end

  defp decimal_compare(:lt, spec) do
    spec === :less_than or spec === :less_than_or_equal_to or spec === :not_equal_to
  end

  defp decimal_compare(:gt, spec) do
    spec === :greater_than or spec === :greater_than_or_equal_to or spec === :not_equal_to
  end

  defp decimal_compare(:eq, spec) do
    spec === :equal_to or spec === :less_than_or_equal_to or spec === :greater_than_or_equal_to
  end

  defp decimal_new(term) when :erlang.is_float(term) do
    Decimal.from_float(term)
  end

  defp decimal_new(term) do
    Decimal.new(term)
  end

  defp ensure_field_exists!(%Ecto.Changeset{types: types, data: data}, field) do
    (
      if(Map.has_key?(types, field)) do
        raise(ArgumentError, <<"unknown field "::binary(), Kernel.inspect(field)::binary(), " in "::binary(), Kernel.inspect(data)::binary()>>)
      else
        nil
      end
      true
    )
  end

  defp force_update(changeset, opts) do
    if(Keyword.get(opts, :force_update_on_change, true)) do
      changeset
    else
      elem(Map.get_and_update!(changeset, :repo_opts, fn x -> Access.get_and_update(x, :force, fn _ -> {nil, true} end) end), 1)
    end
  end

  defp get_assoc(%{types: types}, assoc) do
    case(:maps.find(assoc, types)) do
      {:ok, {:assoc, association}} ->
        association
      _ ->
        raise_invalid_assoc(types, assoc)
    end
  end

  defp get_changed(data, types, old_changes, new_changes, errors, valid?) do
    Enum.reduce(new_changes, {old_changes, errors, valid?}, fn {key, value}, {changes, errors, valid?} -> put_change(data, changes, errors, valid?, key, value, Map.get(types, key)) end)
  end

  defp get_field_source(%{data: %{__struct__: schema}}, field) when is_atom(schema) do
    schema.__schema__(:field_source, field) || x
  end

  defp get_field_source(%{}, field) do
    field
  end

  defp get_source(%{data: %{__meta__: %{source: source}}}) when is_binary(source) do
    source
  end

  defp get_source(%{data: data}) do
    raise(ArgumentError, <<"cannot add constraint to changeset because it does not have a source, got: "::binary(), Kernel.inspect(data)::binary()>>)
  end

  defp get_source(item) do
    raise(ArgumentError, <<"cannot add constraint because a changeset was not supplied, got: "::binary(), Kernel.inspect(item)::binary()>>)
  end

  defp increment_with_rollover(val) when val >= 2147483647 do
    1
  end

  defp increment_with_rollover(val) when is_integer(val) do
    val + 1
  end

  defp list_length(%{types: types}, field, value) do
    case(:maps.find(field, types)) do
      {:ok, {tag, _association}} when tag === :embed or tag === :assoc ->
        length(Ecto.Changeset.Relation.filter_empty(value))
      _ ->
        length(value)
    end
  end

  defp maybe_exclude_itself(base_query, schema, changeset) do
    case(Enum.map(schema.__schema__(:primary_key), fn x1 -> {x1, get_field(changeset, x1)} end)) do
      [{_pk_field, nil} | _remaining_pks] ->
        base_query
      [{pk_field, value} | remaining_pks] ->
        first_expr = %Ecto.Query.DynamicExpr{fun: fn query ->
          _ = query
          {{:==, [], [{{:., [], [{:&, [], [0]}, Ecto.Query.Builder.atom!(pk_field, "field/2")]}, [], []}, {:^, [], [0]}]}, [{Ecto.Query.Builder.not_nil!(value), {0, pk_field}}], []}
        end, binding: [{:q, [line: 1923], nil}], file: "/Users/cliftonmcintosh/code/gringotts/deps/ecto/lib/ecto/changeset.ex", line: 1923}
        case(Enum.reduce_while(remaining_pks, first_expr, fn
         {_pk_field, nil}, _expr ->
            {:halt, nil}
          {pk_field, value}, expr ->
            {:cont, %Ecto.Query.DynamicExpr{fun: fn query ->
              _ = query
              {{:and, [], [{:^, [], [0]}, {:==, [], [{{:., [], [{:&, [], [0]}, Ecto.Query.Builder.atom!(pk_field, "field/2")]}, [], []}, {:^, [], [1]}]}]}, [{expr, :boolean}, {Ecto.Query.Builder.not_nil!(value), {0, pk_field}}], []}
            end, binding: [{:q, [line: 1930], nil}], file: "/Users/cliftonmcintosh/code/gringotts/deps/ecto/lib/ecto/changeset.ex", line: 1930}}
        end)) do
          nil ->
            base_query
          matches_pk ->
            Ecto.Query.Builder.Filter.filter!(:where, :and, base_query, %Ecto.Query.DynamicExpr{fun: fn query ->
              _ = query
              {{:not, [], [{:^, [], [0]}]}, [{matches_pk, :boolean}], []}
            end, binding: [], file: "/Users/cliftonmcintosh/code/gringotts/deps/ecto/lib/ecto/changeset.ex", line: 1937}, 0, "/Users/cliftonmcintosh/code/gringotts/deps/ecto/lib/ecto/changeset.ex", 1937)
        end
      [] ->
        base_query
    end
  end

  defp merge_identical(object, nil, _thing) do
    object
  end

  defp merge_identical(nil, object, _thing) do
    object
  end

  defp merge_identical(object, object, _thing) do
    object
  end

  defp merge_identical(lhs, rhs, thing) do
    raise(ArgumentError, <<"different "::binary(), String.Chars.to_string(thing)::binary(), " (`"::binary(), Kernel.inspect(lhs)::binary(), "` and "::binary(), "`"::binary(), Kernel.inspect(rhs)::binary(), "`) when merging changesets"::binary()>>)
  end

  defp merge_keyword_keys(keyword_list, msg_func, _) when :erlang.is_function(msg_func, 1) do
    Enum.reduce(keyword_list, %{}, fn {key, val}, acc ->
      val = msg_func.(val)
      Map.update(acc, key, [val], fn x1 -> [val | x1] end)
    end)
  end

  defp merge_keyword_keys(keyword_list, msg_func, changeset) when :erlang.is_function(msg_func, 3) do
    Enum.reduce(keyword_list, %{}, fn {key, val}, acc ->
      val = msg_func.(changeset, key, val)
      Map.update(acc, key, [val], fn x1 -> [val | x1] end)
    end)
  end

  defp merge_related_keys(_, _, nil, _, _) do
    raise(ArgumentError, "changeset does not have types information")
  end

  defp merge_related_keys(map, changes, types, msg_func, traverse_function) do
    Enum.reduce(types, map, fn
     ({field, {tag, %{cardinality: :many}}}, acc) when tag === :embed or tag === :assoc ->
        if(changesets = Map.get(changes, field)) do
          acc
        else
          {child, all_empty?} = Enum.map_reduce(changesets, true, fn changeset, all_empty? ->
            child = traverse_function.(changeset, msg_func)
            {child, all_empty? and child == %{}}
          end)
          case(all_empty?) do
            true ->
              acc
            false ->
              :maps.put(field, child, acc)
          end
        end
      ({field, {tag, %{cardinality: :one}}}, acc) when tag === :embed or tag === :assoc ->
        if(changeset = Map.get(changes, field)) do
          acc
        else
          case(traverse_function.(changeset, msg_func)) do
            child when child == %{} ->
              acc
            child ->
              :maps.put(field, child, acc)
          end
        end
      {_, _}, acc ->
        acc
    end)
  end

  defp message(opts, default) do
    super(opts, :message, default)
  end

  defp message(opts, key, default) do
    Keyword.get(opts, key, default)
  end

  defp missing?(changeset, field, trim) when is_atom(field) do
    case(get_field(changeset, field)) do
      %Ecto.Association.NotLoaded{} ->
        raise(ArgumentError, <<"attempting to validate association `"::binary(), String.Chars.to_string(field)::binary(), "` "::binary(), "that was not loaded. Please preload your associations "::binary(), "before calling validate_required/3 or pass the :required "::binary(), "option to Ecto.Changeset.cast_assoc/3"::binary()>>)
      value when is_binary(value) and trim ->
        String.trim_leading(value) == ""
      value when is_binary(value) ->
        value == ""
      nil ->
        true
      _ ->
        false
    end
  end

  defp missing?(_changeset, field, _trim) do
    raise(ArgumentError, <<"validate_required/3 expects field names to be atoms, got: `"::binary(), Kernel.inspect(field)::binary(), "`"::binary()>>)
  end

  defp missing_relation(%{changes: changes, errors: errors} = changeset, name, current, required?, relation, opts) do
    (
      current_changes = Map.get(changes, name, current)
      if(required? and Ecto.Changeset.Relation.empty?(relation, current_changes)) do
        changeset
      else
        errors = [{name, {message(opts, :required_message, "can't be blank"), [validation: :required]}} | errors]
        %{changeset | errors: errors, valid?: false}
      end
    )
  end

  defp no_assoc_message(:one) do
    "is still associated with this entry"
  end

  defp no_assoc_message(:many) do
    "are still associated with this entry"
  end

  defp normalize_compare(result) do
    case(result) do
      %Decimal{coef: 1, sign: -1} ->
        :lt
      %Decimal{coef: 0} ->
        :eq
      %Decimal{coef: 1, sign: 1} ->
        :gt
      _ ->
        result
    end
  end

  defp on_cast_default(type, module) do
    fn struct, params -> try() do
      module.changeset(struct, params)
    rescue
      e in [UndefinedFunctionError] ->
        case(__STACKTRACE__) do
          [{^module, :changeset, args_or_arity, _}] when args_or_arity == 2 when length(args_or_arity) == 2 ->
            raise(ArgumentError, <<"the module "::binary(), Kernel.inspect(module)::binary(), " does not define a changeset/2 function,\nwhich is used by cast_"::binary(), String.Chars.to_string(type)::binary(), "/3. You need to either:\n\n  1. implement the "::binary(), String.Chars.to_string(type)::binary(), ".changeset/2 function\n  2. pass the :with option to cast_"::binary(), String.Chars.to_string(type)::binary(), "/3 with an anonymous\n     function that expects 2 args or an MFA tuple\n\nWhen using an inline embed, the :with option must be given\n"::binary()>>)
          stacktrace ->
            :erlang.error(:erlang.raise(:error, Kernel.Utils.raise(e), stacktrace))
        end
    end end
  end

  defp process_param(key, params, types, data, empty_values, defaults, {changes, errors, valid?}) do
    (
      {key, param_key} = cast_key(key)
      type = cast_type!(types, key)
      current = case(changes) do
        %{^key => value} ->
          value
        _ ->
          Map.get(data, key)
      end
      case(cast_field(key, param_key, type, params, current, empty_values, defaults, valid?)) do
        {:ok, value, valid?} ->
          {:maps.put(key, value, changes), errors, valid?}
        :missing ->
          {changes, errors, valid?}
        {:invalid, custom_errors} ->
          {message, new_errors} = Keyword.pop(Keyword.put(Keyword.put_new(custom_errors, :validation, :cast), :type, type), :message, "is invalid")
          {changes, [{key, {message, new_errors}} | errors], false}
      end
    )
  end

  defp put_change(data, changes, errors, valid?, key, value, {tag, relation}) when tag === :embed or tag === :assoc do
    (
      original = Map.get(data, key)
      current = Ecto.Changeset.Relation.load!(data, original)
      case(Ecto.Changeset.Relation.change(relation, value, current)) do
        {:ok, change, relation_valid?} when change != original ->
          {:maps.put(key, change, changes), errors, valid? and relation_valid?}
        {:error, error} ->
          {changes, [{key, error} | errors], false}
        _ ->
          {:maps.remove(key, changes), errors, valid?}
      end
    )
  end

  defp put_change(data, _changes, _errors, _valid?, key, _value, nil) when is_atom(key) do
    raise(ArgumentError, <<"unknown field `"::binary(), Kernel.inspect(key)::binary(), "` in "::binary(), Kernel.inspect(data)::binary()>>)
  end

  defp put_change(_data, _changes, _errors, _valid?, key, _value, nil) when not(is_atom(key)) do
    raise(ArgumentError, <<"field names given to change/put_change must be atoms, got: `"::binary(), Kernel.inspect(key)::binary(), "`"::binary()>>)
  end

  defp put_change(data, changes, errors, valid?, key, value, type) do
    case(not(Ecto.Type.equal?(type, Map.get(data, key), value))) do
      false ->
        {:maps.remove(key, changes), errors, valid?}
      true ->
        {:maps.put(key, value, changes), errors, valid?}
    end
  end

  defp put_relation(_tag, %{types: nil}, _name, _value, _opts) do
    raise(ArgumentError, "changeset does not have types information")
  end

  defp put_relation(tag, changeset, name, value, _opts) do
    (
      %{data: data, types: types, changes: changes, errors: errors, valid?: valid?} = changeset
      relation = relation!(:put, tag, name, Map.get(types, name))
      {changes, errors, valid?} = put_change(data, changes, errors, valid?, name, value, {tag, relation})
      %{changeset | changes: changes, errors: errors, valid?: valid?}
    )
  end

  defp raise_invalid_assoc(types, assoc) do
    (
      associations = for({_key, {:assoc, %{field: field}}} <- types) do
        field
      end
      raise(ArgumentError, <<"cannot add constraint to changeset because association `"::binary(), String.Chars.to_string(assoc)::binary(), "` does not exist. "::binary(), "Did you mean one of `"::binary(), Enum.join(associations, "`, `")::binary(), "`?"::binary()>>)
    )
  end

  defp relation!(_op, type, _name, {type, relation}) do
    relation
  end

  defp relation!(op, :assoc, name, nil) do
    raise(ArgumentError, <<"cannot "::binary(), String.Chars.to_string(op)::binary(), " assoc `"::binary(), String.Chars.to_string(name)::binary(), "`, assoc `"::binary(), String.Chars.to_string(name)::binary(), "` not found. Make sure it is spelled correctly and that the association type is not read-only"::binary()>>)
  end

  defp relation!(op, type, name, nil) do
    raise(ArgumentError, <<"cannot "::binary(), String.Chars.to_string(op)::binary(), " "::binary(), String.Chars.to_string(type)::binary(), " `"::binary(), String.Chars.to_string(name)::binary(), "`, "::binary(), String.Chars.to_string(type)::binary(), " `"::binary(), String.Chars.to_string(name)::binary(), "` not found. Make sure that it exists and is spelled correctly"::binary()>>)
  end

  defp relation!(op, type, name, {other, _}) when other === :embed or other === :assoc do
    raise(ArgumentError, <<"expected `"::binary(), String.Chars.to_string(name)::binary(), "` to be an "::binary(), String.Chars.to_string(type)::binary(), " in `"::binary(), String.Chars.to_string(op)::binary(), "_"::binary(), String.Chars.to_string(type)::binary(), "`, got: `"::binary(), String.Chars.to_string(other)::binary(), "`"::binary()>>)
  end

  defp relation!(op, type, name, schema_type) do
    raise(ArgumentError, <<"expected `"::binary(), String.Chars.to_string(name)::binary(), "` to be an "::binary(), String.Chars.to_string(type)::binary(), " in `"::binary(), String.Chars.to_string(op)::binary(), "_"::binary(), String.Chars.to_string(type)::binary(), "`, got: `"::binary(), Kernel.inspect(schema_type)::binary(), "`"::binary()>>)
  end

  defp too_long(_type, length, value, _opts) when length <= value do
    nil
  end

  defp too_long(:string, _length, value, opts) do
    {message(opts, "should be at most %{count} character(s)"), [count: value, validation: :length, kind: :max, type: :string]}
  end

  defp too_long(:binary, _length, value, opts) do
    {message(opts, "should be at most %{count} byte(s)"), [count: value, validation: :length, kind: :max, type: :binary]}
  end

  defp too_long(:list, _length, value, opts) do
    {message(opts, "should have at most %{count} item(s)"), [count: value, validation: :length, kind: :max, type: :list]}
  end

  defp too_short(_type, length, value, _opts) when length >= value do
    nil
  end

  defp too_short(:string, _length, value, opts) do
    {message(opts, "should be at least %{count} character(s)"), [count: value, validation: :length, kind: :min, type: :string]}
  end

  defp too_short(:binary, _length, value, opts) do
    {message(opts, "should be at least %{count} byte(s)"), [count: value, validation: :length, kind: :min, type: :binary]}
  end

  defp too_short(:list, _length, value, opts) do
    {message(opts, "should have at least %{count} item(s)"), [count: value, validation: :length, kind: :min, type: :list]}
  end

  defp unique_index_name(changeset, fields) do
    (
      field_names = Enum.map(fields, fn x1 -> get_field_source(changeset, x1) end)
      Enum.join([get_source(changeset)] ++ field_names ++ ["index"], "_")
    )
  end

  defp validate_acceptance_errors(nil, _field, _opts) do
    []
  end

  defp validate_acceptance_errors(params, field, opts) do
    (
      param = Atom.to_string(field)
      value = Map.get(params, param)
      case(Ecto.Type.cast(:boolean, value)) do
        {:ok, true} ->
          []
        _ ->
          [{field, {message(opts, "must be accepted"), [validation: :acceptance]}}]
      end
    )
  end

  defp validate_number(field, %Decimal{} = value, message, spec_key, _spec_function, target_value) do
    (
      result = normalize_compare(Decimal.compare(value, decimal_new(target_value)))
      case(decimal_compare(result, spec_key)) do
        true ->
          nil
        false ->
          [{field, {message, [validation: :number, kind: spec_key, number: target_value]}}]
      end
    )
  end

  defp validate_number(field, value, message, spec_key, spec_function, target_value) when :erlang.is_number(value) do
    case(apply(spec_function, [value, target_value])) do
      true ->
        nil
      false ->
        [{field, {message, [validation: :number, kind: spec_key, number: target_value]}}]
    end
  end

  defp validate_number(_field, value, _message, _spec_key, _spec_function, _target_value) do
    raise(ArgumentError, <<"expected value to be of type Decimal, Integer or Float, got: "::binary(), Kernel.inspect(value)::binary()>>)
  end

  defp wrong_length(_type, value, value, _opts) do
    nil
  end

  defp wrong_length(:string, _length, value, opts) do
    {message(opts, "should be %{count} character(s)"), [count: value, validation: :length, kind: :is, type: :string]}
  end

  defp wrong_length(:binary, _length, value, opts) do
    {message(opts, "should be %{count} byte(s)"), [count: value, validation: :length, kind: :is, type: :binary]}
  end

  defp wrong_length(:list, _length, value, opts) do
    {message(opts, "should have %{count} item(s)"), [count: value, validation: :length, kind: :is, type: :list]}
  end
end
