# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :erl_syntax do

  # Types

  @type forms :: (syntaxTree() | [syntaxTree()])

  @type padding :: (:none | integer())

  @type syntaxTree :: (tree() | wrapper() | erl_parse())

  @type syntaxTreeAttributes :: attr()

  # Private Types

  @typep encoding :: (:utf8 | :unicode | :latin1)

  @typep erl_parse :: (:erl_parse.abstract_clause() | :erl_parse.abstract_expr() | :erl_parse.abstract_form() | :erl_parse.abstract_type() | :erl_parse.form_info() | {:bin_element, any(), any(), any(), any()})

  @typep guard :: (:none | syntaxTree() | [syntaxTree()] | [[syntaxTree()]])

  # Functions

  @spec abstract(term()) :: syntaxTree()
  def abstract([h | t] = l) when is_integer(h) do
    case is_printable(l) do
      true ->
        string(l)
      false ->
        abstract_tail(h, t)
    end
  end

  def abstract([h | t]), do: abstract_tail(h, t)

  def abstract(t) when is_atom(t), do: atom(t)

  def abstract(t) when is_integer(t), do: integer(t)

  def abstract(t) when is_float(t), do: make_float(t)

  def abstract([]), do: apply(__MODULE__, nil, [])

  def abstract(t) when is_tuple(t), do: tuple(abstract_list(tuple_to_list(t)))

  def abstract(t) when is_map(t) do
    map_expr((for {key, value} <- :maps.to_list(t) do
      map_field_assoc(abstract(key), abstract(value))
    end))
  end

  def abstract(t) when is_binary(t) do
    binary((for b <- binary_to_list(t) do
      binary_field(integer(b))
    end))
  end

  def abstract(t), do: :erlang.error({:badarg, t})

  @spec add_ann(term(), syntaxTree()) :: syntaxTree()
  def add_ann(a, node) do
    case node do
      tree(attr: attr) ->
        tree(node, attr: attr(attr, ann: [a | attr(attr, :ann)]))
      wrapper(attr: attr) ->
        wrapper(node, attr: attr(attr, ann: [a | attr(attr, :ann)]))
      _ ->
        add_ann(a, wrap(node))
    end
  end

  @spec add_postcomments([syntaxTree()], syntaxTree()) :: syntaxTree()
  def add_postcomments(cs, node) do
    case node do
      tree(attr: attr) ->
        tree(node, attr: add_postcomments_1(cs, attr))
      wrapper(attr: attr) ->
        wrapper(node, attr: add_postcomments_1(cs, attr))
      _ ->
        add_postcomments(cs, wrap(node))
    end
  end

  @spec add_precomments([syntaxTree()], syntaxTree()) :: syntaxTree()
  def add_precomments(cs, node) do
    case node do
      tree(attr: attr) ->
        tree(node, attr: add_precomments_1(cs, attr))
      wrapper(attr: attr) ->
        wrapper(node, attr: add_precomments_1(cs, attr))
      _ ->
        add_precomments(cs, wrap(node))
    end
  end

  @spec annotated_type(syntaxTree(), syntaxTree()) :: syntaxTree()
  def annotated_type(name, type), do: tree(:annotated_type, annotated_type(name: name, body: type))

  @spec annotated_type_body(syntaxTree()) :: syntaxTree()
  def annotated_type_body(node) do
    case unwrap(node) do
      {:ann_type, _, [_, type]} ->
        type
      node1 ->
        annotated_type(data(node1), :body)
    end
  end

  @spec annotated_type_name(syntaxTree()) :: syntaxTree()
  def annotated_type_name(node) do
    case unwrap(node) do
      {:ann_type, _, [name, _]} ->
        name
      node1 ->
        annotated_type(data(node1), :name)
    end
  end

  @spec application(syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def application(operator, arguments), do: tree(:application, application(operator: operator, arguments: arguments))

  @spec application((:none | syntaxTree()), syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def application(:none, name, arguments), do: application(name, arguments)

  def application(module, name, arguments), do: application(module_qualifier(module, name), arguments)

  @spec application_arguments(syntaxTree()) :: [syntaxTree()]
  def application_arguments(node) do
    case unwrap(node) do
      {:call, _, _, arguments} ->
        arguments
      node1 ->
        application(data(node1), :arguments)
    end
  end

  @spec application_operator(syntaxTree()) :: syntaxTree()
  def application_operator(node) do
    case unwrap(node) do
      {:call, _, operator, _} ->
        operator
      node1 ->
        application(data(node1), :operator)
    end
  end

  @spec arity_qualifier(syntaxTree(), syntaxTree()) :: syntaxTree()
  def arity_qualifier(body, arity), do: tree(:arity_qualifier, arity_qualifier(body: body, arity: arity))

  @spec arity_qualifier_argument(syntaxTree()) :: syntaxTree()
  def arity_qualifier_argument(node), do: arity_qualifier(data(node), :arity)

  @spec arity_qualifier_body(syntaxTree()) :: syntaxTree()
  def arity_qualifier_body(node), do: arity_qualifier(data(node), :body)

  @spec atom((atom() | charlist())) :: syntaxTree()
  def atom(name) when is_atom(name), do: tree(:atom, name)

  def atom(name), do: tree(:atom, list_to_atom(name))

  @spec atom_literal(syntaxTree()) :: charlist()
  def atom_literal(node), do: atom_literal(node, :latin1)

  def atom_literal(node, :utf8), do: :io_lib.write_atom(atom_value(node))

  def atom_literal(node, :unicode), do: :io_lib.write_atom(atom_value(node))

  def atom_literal(node, :latin1), do: :io_lib.write_atom_as_latin1(atom_value(node))

  @spec atom_name(syntaxTree()) :: charlist()
  def atom_name(node), do: atom_to_list(atom_value(node))

  @spec atom_value(syntaxTree()) :: atom()
  def atom_value(node) do
    case unwrap(node) do
      {:atom, _, name} ->
        name
      node1 ->
        data(node1)
    end
  end

  @spec attribute(syntaxTree()) :: syntaxTree()
  def attribute(name), do: attribute(name, :none)

  @spec attribute(syntaxTree(), (:none | [syntaxTree()])) :: syntaxTree()
  def attribute(name, args), do: tree(:attribute, attribute(name: name, args: args))

  @spec attribute_arguments(syntaxTree()) :: (:none | [syntaxTree()])
  def attribute_arguments(node) do
    case unwrap(node) do
      {:attribute, pos, name, data} ->
        case name do
          :module ->
            {m1, vs} = case data do
              {m0, vs0} ->
                {m0, unfold_variable_names(vs0, pos)}
              m0 ->
                {m0, :none}
            end
            m2 = atom(m1)
            m = set_pos(m2, pos)
            cond do
              vs == :none ->
                [m]
              true ->
                [m, set_pos(list(vs), pos)]
            end
          :export ->
            [set_pos(list(unfold_function_names(data, pos)), pos)]
          :import ->
            {module, imports} = data
            [set_pos(atom(module), pos), set_pos(list(unfold_function_names(imports, pos)), pos)]
          :file ->
            {file, line} = data
            [set_pos(string(file), pos), set_pos(integer(line), pos)]
          :record ->
            {type, entries} = data
            [set_pos(atom(type), pos), set_pos(tuple(unfold_record_fields(entries)), pos)]
          _ ->
            [set_pos(abstract(data), pos)]
        end
      node1 ->
        attribute(data(node1), :args)
    end
  end

  @spec attribute_name(syntaxTree()) :: syntaxTree()
  def attribute_name(node) do
    case unwrap(node) do
      {:attribute, pos, name, _} ->
        set_pos(atom(name), pos)
      node1 ->
        attribute(data(node1), :name)
    end
  end

  @spec binary([syntaxTree()]) :: syntaxTree()
  def binary(list), do: tree(:binary, list)

  @spec binary_comp(syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def binary_comp(template, body), do: tree(:binary_comp, binary_comp(template: template, body: body))

  @spec binary_comp_body(syntaxTree()) :: [syntaxTree()]
  def binary_comp_body(node) do
    case unwrap(node) do
      {:bc, _, _, body} ->
        body
      node1 ->
        binary_comp(data(node1), :body)
    end
  end

  @spec binary_comp_template(syntaxTree()) :: syntaxTree()
  def binary_comp_template(node) do
    case unwrap(node) do
      {:bc, _, template, _} ->
        template
      node1 ->
        binary_comp(data(node1), :template)
    end
  end

  @spec binary_field(syntaxTree()) :: syntaxTree()
  def binary_field(body), do: binary_field(body, [])

  @spec binary_field(syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def binary_field(body, types), do: tree(:binary_field, binary_field(body: body, types: types))

  @spec binary_field(syntaxTree(), (:none | syntaxTree()), [syntaxTree()]) :: syntaxTree()
  def binary_field(body, :none, types), do: binary_field(body, types)

  def binary_field(body, size, types), do: binary_field(size_qualifier(body, size), types)

  @spec binary_field_body(syntaxTree()) :: syntaxTree()
  def binary_field_body(node) do
    case unwrap(node) do
      {:bin_element, _, body, size, _} ->
        cond do
          size === :default ->
            body
          true ->
            size_qualifier(body, size)
        end
      node1 ->
        binary_field(data(node1), :body)
    end
  end

  @spec binary_field_size(syntaxTree()) :: (:none | syntaxTree())
  def binary_field_size(node) do
    case unwrap(node) do
      {:bin_element, _, _, size, _} ->
        cond do
          size === :default ->
            :none
          true ->
            size
        end
      node1 ->
        body = binary_field(data(node1), :body)
        case type(body) do
          :size_qualifier ->
            size_qualifier_argument(body)
          _ ->
            :none
        end
    end
  end

  @spec binary_field_types(syntaxTree()) :: [syntaxTree()]
  def binary_field_types(node) do
    case unwrap(node) do
      {:bin_element, pos, _, _, types} ->
        cond do
          types === :default ->
            []
          true ->
            unfold_binary_field_types(types, pos)
        end
      node1 ->
        binary_field(data(node1), :types)
    end
  end

  @spec binary_fields(syntaxTree()) :: [syntaxTree()]
  def binary_fields(node) do
    case unwrap(node) do
      {:bin, _, list} ->
        list
      node1 ->
        data(node1)
    end
  end

  @spec binary_generator(syntaxTree(), syntaxTree()) :: syntaxTree()
  def binary_generator(pattern, body), do: tree(:binary_generator, binary_generator(pattern: pattern, body: body))

  @spec binary_generator_body(syntaxTree()) :: syntaxTree()
  def binary_generator_body(node) do
    case unwrap(node) do
      {:b_generate, _, _, body} ->
        body
      node1 ->
        binary_generator(data(node1), :body)
    end
  end

  @spec binary_generator_pattern(syntaxTree()) :: syntaxTree()
  def binary_generator_pattern(node) do
    case unwrap(node) do
      {:b_generate, _, pattern, _} ->
        pattern
      node1 ->
        binary_generator(data(node1), :pattern)
    end
  end

  @spec bitstring_type(syntaxTree(), syntaxTree()) :: syntaxTree()
  def bitstring_type(m, n), do: tree(:bitstring_type, bitstring_type(m: m, n: n))

  @spec bitstring_type_m(syntaxTree()) :: syntaxTree()
  def bitstring_type_m(node) do
    case unwrap(node) do
      {:type, _, :binary, [m, _]} ->
        m
      node1 ->
        bitstring_type(data(node1), :m)
    end
  end

  @spec bitstring_type_n(syntaxTree()) :: syntaxTree()
  def bitstring_type_n(node) do
    case unwrap(node) do
      {:type, _, :binary, [_, n]} ->
        n
      node1 ->
        bitstring_type(data(node1), :n)
    end
  end

  @spec block_expr([syntaxTree()]) :: syntaxTree()
  def block_expr(body), do: tree(:block_expr, body)

  @spec block_expr_body(syntaxTree()) :: [syntaxTree()]
  def block_expr_body(node) do
    case unwrap(node) do
      {:block, _, body} ->
        body
      node1 ->
        data(node1)
    end
  end

  @spec case_expr(syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def case_expr(argument, clauses), do: tree(:case_expr, case_expr(argument: argument, clauses: clauses))

  @spec case_expr_argument(syntaxTree()) :: syntaxTree()
  def case_expr_argument(node) do
    case unwrap(node) do
      {:case, _, argument, _} ->
        argument
      node1 ->
        case_expr(data(node1), :argument)
    end
  end

  @spec case_expr_clauses(syntaxTree()) :: [syntaxTree()]
  def case_expr_clauses(node) do
    case unwrap(node) do
      {:case, _, _, clauses} ->
        clauses
      node1 ->
        case_expr(data(node1), :clauses)
    end
  end

  @spec catch_expr(syntaxTree()) :: syntaxTree()
  def catch_expr(expr), do: tree(:catch_expr, expr)

  @spec catch_expr_body(syntaxTree()) :: syntaxTree()
  def catch_expr_body(node) do
    case unwrap(node) do
      {:catch, _, expr} ->
        expr
      node1 ->
        data(node1)
    end
  end

  @spec char(char()) :: syntaxTree()
  def char(char), do: tree(:char, char)

  @spec char_literal(syntaxTree()) :: [char(), ...]
  def char_literal(node), do: char_literal(node, :latin1)

  @spec char_literal(syntaxTree(), encoding()) :: [char(), ...]
  def char_literal(node, :unicode), do: :io_lib.write_char(char_value(node))

  def char_literal(node, :utf8), do: :io_lib.write_char(char_value(node))

  def char_literal(node, :latin1), do: :io_lib.write_char_as_latin1(char_value(node))

  @spec char_value(syntaxTree()) :: char()
  def char_value(node) do
    case unwrap(node) do
      {:char, _, char} ->
        char
      node1 ->
        data(node1)
    end
  end

  @spec class_qualifier(syntaxTree(), syntaxTree()) :: syntaxTree()
  def class_qualifier(class, body), do: tree(:class_qualifier, class_qualifier(class: class, body: body))

  @spec class_qualifier_argument(syntaxTree()) :: syntaxTree()
  def class_qualifier_argument(node), do: class_qualifier(data(node), :class)

  @spec class_qualifier_body(syntaxTree()) :: syntaxTree()
  def class_qualifier_body(node), do: class_qualifier(data(node), :body)

  @spec clause(guard(), [syntaxTree()]) :: syntaxTree()
  def clause(guard, body), do: clause([], guard, body)

  @spec clause([syntaxTree()], guard(), [syntaxTree()]) :: syntaxTree()
  def clause(patterns, guard, body) do
    guard1 = case guard do
      [] ->
        :none
      [x | _] when is_list(x) ->
        disjunction(conjunction_list(guard))
      [_ | _] ->
        conjunction(guard)
      _ ->
        guard
    end
    tree(:clause, clause(patterns: patterns, guard: guard1, body: body))
  end

  @spec clause_body(syntaxTree()) :: [syntaxTree()]
  def clause_body(node) do
    case unwrap(node) do
      {:clause, _, _, _, body} ->
        body
      node1 ->
        clause(data(node1), :body)
    end
  end

  @spec clause_guard(syntaxTree()) :: (:none | syntaxTree())
  def clause_guard(node) do
    case unwrap(node) do
      {:clause, _, _, guard, _} ->
        case guard do
          [] ->
            :none
          [l | _] when is_list(l) ->
            disjunction(conjunction_list(guard))
          [_ | _] ->
            conjunction(guard)
        end
      node1 ->
        clause(data(node1), :guard)
    end
  end

  @spec clause_patterns(syntaxTree()) :: [syntaxTree()]
  def clause_patterns(node) do
    case unwrap(node) do
      {:clause, _, patterns, _, _} ->
        patterns
      node1 ->
        clause(data(node1), :patterns)
    end
  end

  @spec comment([charlist()]) :: syntaxTree()
  def comment(strings), do: comment(:none, strings)

  @spec comment(padding(), [charlist()]) :: syntaxTree()
  def comment(pad, strings), do: tree(:comment, comment(pad: pad, text: strings))

  @spec comment_padding(syntaxTree()) :: padding()
  def comment_padding(node), do: comment(data(node), :pad)

  @spec comment_text(syntaxTree()) :: [charlist()]
  def comment_text(node), do: comment(data(node), :text)

  @spec compact_list(syntaxTree()) :: syntaxTree()
  def compact_list(node) do
    case type(node) do
      :list ->
        case list_suffix(node) do
          :none ->
            node
          tail ->
            case type(tail) do
              :list ->
                tail1 = compact_list(tail)
                node1 = list(list_prefix(node) ++ list_prefix(tail1), list_suffix(tail1))
                join_comments(tail1, copy_attrs(node, node1))
              nil ->
                node1 = list(list_prefix(node))
                join_comments(tail, copy_attrs(node, node1))
              _ ->
                node
            end
        end
      _ ->
        node
    end
  end

  @spec concrete(syntaxTree()) :: term()
  def concrete(node) do
    case type(node) do
      :atom ->
        atom_value(node)
      :integer ->
        integer_value(node)
      :float ->
        float_value(node)
      :char ->
        char_value(node)
      :string ->
        string_value(node)
      nil ->
        []
      :list ->
        [concrete(list_head(node)) | concrete(list_tail(node))]
      :tuple ->
        list_to_tuple(concrete_list(tuple_elements(node)))
      :map_expr ->
        as = for f <- map_expr_fields(node) do
          tuple([map_field_assoc_name(f), map_field_assoc_value(f)])
        end
        m0 = :maps.from_list(concrete_list(as))
        case map_expr_argument(node) do
          :none ->
            m0
          node0 ->
            :maps.merge(concrete(node0), m0)
        end
      :binary ->
        fs = for f <- binary_fields(node) do
          revert_binary_field(binary_field(binary_field_body(f), (case binary_field_size(f) do
          :none ->
            :none
          s ->
            revert(s)
        end), binary_field_types(f)))
        end
        {:value, b, _} = :eval_bits.expr_grp(fs, [], fn f, _ ->
            {:value, concrete(f), []}
        end, [], true)
        b
      :arity_qualifier ->
        a = :erl_syntax.arity_qualifier_argument(node)
        case :erl_syntax.type(a) do
          :integer ->
            f = :erl_syntax.arity_qualifier_body(node)
            case :erl_syntax.type(f) do
              :atom ->
                {f, a}
              _ ->
                :erlang.error({:badarg, node})
            end
          _ ->
            :erlang.error({:badarg, node})
        end
      _ ->
        :erlang.error({:badarg, node})
    end
  end

  @spec cond_expr([syntaxTree()]) :: syntaxTree()
  def cond_expr(clauses), do: tree(:cond_expr, clauses)

  @spec cond_expr_clauses(syntaxTree()) :: [syntaxTree()]
  def cond_expr_clauses(node) do
    case unwrap(node) do
      {:cond, _, clauses} ->
        clauses
      node1 ->
        data(node1)
    end
  end

  @spec conjunction([syntaxTree()]) :: syntaxTree()
  def conjunction(tests), do: tree(:conjunction, tests)

  @spec conjunction_body(syntaxTree()) :: [syntaxTree()]
  def conjunction_body(node), do: data(node)

  @spec cons(syntaxTree(), syntaxTree()) :: syntaxTree()
  def cons(head, tail) do
    case type(tail) do
      :list ->
        copy_comments(tail, list([head | list_prefix(tail)], list_suffix(tail)))
      nil ->
        copy_comments(tail, list([head]))
      _ ->
        list([head], tail)
    end
  end

  @spec constrained_function_type(syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def constrained_function_type(functionType, functionConstraint) do
    conj = conjunction(functionConstraint)
    tree(:constrained_function_type, constrained_function_type(body: functionType, argument: conj))
  end

  @spec constrained_function_type_argument(syntaxTree()) :: syntaxTree()
  def constrained_function_type_argument(node) do
    case unwrap(node) do
      {:type, _, :bounded_fun, [_, functionConstraint]} ->
        conjunction(functionConstraint)
      node1 ->
        constrained_function_type(data(node1), :argument)
    end
  end

  @spec constrained_function_type_body(syntaxTree()) :: syntaxTree()
  def constrained_function_type_body(node) do
    case unwrap(node) do
      {:type, _, :bounded_fun, [functionType, _]} ->
        functionType
      node1 ->
        constrained_function_type(data(node1), :body)
    end
  end

  @spec constraint(syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def constraint(name, types), do: tree(:constraint, constraint(name: name, types: types))

  @spec constraint_argument(syntaxTree()) :: syntaxTree()
  def constraint_argument(node) do
    case unwrap(node) do
      {:type, _, :constraint, [name, _]} ->
        name
      node1 ->
        constraint(data(node1), :name)
    end
  end

  @spec constraint_body(syntaxTree()) :: [syntaxTree()]
  def constraint_body(node) do
    case unwrap(node) do
      {:type, _, :constraint, [_, types]} ->
        types
      node1 ->
        constraint(data(node1), :types)
    end
  end

  @spec copy_ann(syntaxTree(), syntaxTree()) :: syntaxTree()
  def copy_ann(source, target), do: set_ann(target, get_ann(source))

  @spec copy_attrs(syntaxTree(), syntaxTree()) :: syntaxTree()
  def copy_attrs(s, t), do: set_attrs(t, get_attrs(s))

  @spec copy_comments(syntaxTree(), syntaxTree()) :: syntaxTree()
  def copy_comments(source, target), do: set_com(target, get_com(source))

  @spec copy_pos(syntaxTree(), syntaxTree()) :: syntaxTree()
  def copy_pos(source, target), do: set_pos(target, get_pos(source))

  @spec data(syntaxTree()) :: term()
  def data(tree(data: d)), do: d

  def data(t), do: :erlang.error({:badarg, t})

  @spec disjunction([syntaxTree()]) :: syntaxTree()
  def disjunction(tests), do: tree(:disjunction, tests)

  @spec disjunction_body(syntaxTree()) :: [syntaxTree()]
  def disjunction_body(node), do: data(node)

  @spec eof_marker() :: syntaxTree()
  def eof_marker(), do: tree(:eof_marker)

  @spec error_marker(term()) :: syntaxTree()
  def error_marker(error), do: tree(:error_marker, error)

  @spec error_marker_info(syntaxTree()) :: term()
  def error_marker_info(node) do
    case unwrap(node) do
      {:error, error} ->
        error
      t ->
        data(t)
    end
  end

  @spec flatten_form_list(syntaxTree()) :: syntaxTree()
  def flatten_form_list(node) do
    fs = form_list_elements(node)
    fs1 = :lists.reverse(flatten_form_list_1(fs, []))
    copy_attrs(node, form_list(fs1))
  end

  @spec float(float()) :: syntaxTree()
  def float(value), do: make_float(value)

  @spec float_literal(syntaxTree()) :: charlist()
  def float_literal(node), do: float_to_list(float_value(node))

  @spec float_value(syntaxTree()) :: float()
  def float_value(node) do
    case unwrap(node) do
      {:float, _, value} ->
        value
      node1 ->
        data(node1)
    end
  end

  @spec form_list([syntaxTree()]) :: syntaxTree()
  def form_list(forms), do: tree(:form_list, forms)

  @spec form_list_elements(syntaxTree()) :: [syntaxTree()]
  def form_list_elements(node), do: data(node)

  @spec fun_expr([syntaxTree()]) :: syntaxTree()
  def fun_expr(clauses), do: tree(:fun_expr, clauses)

  @spec fun_expr_arity(syntaxTree()) :: arity()
  def fun_expr_arity(node), do: length(clause_patterns(hd(fun_expr_clauses(node))))

  @spec fun_expr_clauses(syntaxTree()) :: [syntaxTree()]
  def fun_expr_clauses(node) do
    case unwrap(node) do
      {:fun, _, {:clauses, clauses}} ->
        clauses
      node1 ->
        data(node1)
    end
  end

  @spec fun_type() :: syntaxTree()
  def fun_type(), do: tree(:fun_type)

  @spec function(syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def function(name, clauses), do: tree(:function, func(name: name, clauses: clauses))

  @spec function_arity(syntaxTree()) :: arity()
  def function_arity(node), do: length(clause_patterns(hd(function_clauses(node))))

  @spec function_clauses(syntaxTree()) :: [syntaxTree()]
  def function_clauses(node) do
    case unwrap(node) do
      {:function, _, _, _, clauses} ->
        clauses
      node1 ->
        func(data(node1), :clauses)
    end
  end

  @spec function_name(syntaxTree()) :: syntaxTree()
  def function_name(node) do
    case unwrap(node) do
      {:function, pos, name, _, _} ->
        set_pos(atom(name), pos)
      node1 ->
        func(data(node1), :name)
    end
  end

  def function_type(type), do: function_type(:any_arity, type)

  @spec function_type((:any_arity | syntaxTree()), syntaxTree()) :: syntaxTree()
  def function_type(arguments, return), do: tree(:function_type, function_type(arguments: arguments, return: return))

  @spec function_type_arguments(syntaxTree()) :: (:any_arity | [syntaxTree()])
  def function_type_arguments(node) do
    case unwrap(node) do
      {:type, _, :fun, [{:type, _, :any}, _]} ->
        :any_arity
      {:type, _, :fun, [{:type, _, :product, arguments}, _]} ->
        arguments
      node1 ->
        function_type(data(node1), :arguments)
    end
  end

  @spec function_type_return(syntaxTree()) :: syntaxTree()
  def function_type_return(node) do
    case unwrap(node) do
      {:type, _, :fun, [_, type]} ->
        type
      node1 ->
        function_type(data(node1), :return)
    end
  end

  @spec generator(syntaxTree(), syntaxTree()) :: syntaxTree()
  def generator(pattern, body), do: tree(:generator, generator(pattern: pattern, body: body))

  @spec generator_body(syntaxTree()) :: syntaxTree()
  def generator_body(node) do
    case unwrap(node) do
      {:generate, _, _, body} ->
        body
      node1 ->
        generator(data(node1), :body)
    end
  end

  @spec generator_pattern(syntaxTree()) :: syntaxTree()
  def generator_pattern(node) do
    case unwrap(node) do
      {:generate, _, pattern, _} ->
        pattern
      node1 ->
        generator(data(node1), :pattern)
    end
  end

  @spec get_ann(syntaxTree()) :: [term()]
  def get_ann(tree(attr: attr)), do: attr(attr, :ann)

  def get_ann(wrapper(attr: attr)), do: attr(attr, :ann)

  def get_ann(_), do: []

  @spec get_attrs(syntaxTree()) :: syntaxTreeAttributes()
  def get_attrs(tree(attr: attr)), do: attr

  def get_attrs(wrapper(attr: attr)), do: attr

  def get_attrs(node), do: attr(pos: get_pos(node), ann: get_ann(node), com: get_com(node))

  @spec get_pos(syntaxTree()) :: term()
  def get_pos(tree(attr: attr)), do: attr(attr, :pos)

  def get_pos(wrapper(attr: attr)), do: attr(attr, :pos)

  def get_pos({:error, {pos, _, _}}), do: pos

  def get_pos({:warning, {pos, _, _}}), do: pos

  def get_pos(node), do: element(2, node)

  @spec get_postcomments(syntaxTree()) :: [syntaxTree()]
  def get_postcomments(tree(attr: attr)), do: get_postcomments_1(attr)

  def get_postcomments(wrapper(attr: attr)), do: get_postcomments_1(attr)

  def get_postcomments(_), do: []

  @spec get_precomments(syntaxTree()) :: [syntaxTree()]
  def get_precomments(tree(attr: attr)), do: get_precomments_1(attr)

  def get_precomments(wrapper(attr: attr)), do: get_precomments_1(attr)

  def get_precomments(_), do: []

  @spec has_comments(syntaxTree()) :: boolean()
  def has_comments(tree(attr: attr)) do
    case attr(attr, :com) do
      :none ->
        false
      com(pre: [], post: []) ->
        false
      _ ->
        true
    end
  end

  def has_comments(wrapper(attr: attr)) do
    case attr(attr, :com) do
      :none ->
        false
      com(pre: [], post: []) ->
        false
      _ ->
        true
    end
  end

  def has_comments(_), do: false

  @spec if_expr([syntaxTree()]) :: syntaxTree()
  def if_expr(clauses), do: tree(:if_expr, clauses)

  @spec if_expr_clauses(syntaxTree()) :: [syntaxTree()]
  def if_expr_clauses(node) do
    case unwrap(node) do
      {:if, _, clauses} ->
        clauses
      node1 ->
        data(node1)
    end
  end

  @spec implicit_fun(syntaxTree()) :: syntaxTree()
  def implicit_fun(name), do: tree(:implicit_fun, name)

  @spec implicit_fun(syntaxTree(), (:none | syntaxTree())) :: syntaxTree()
  def implicit_fun(name, :none), do: implicit_fun(name)

  def implicit_fun(name, arity), do: implicit_fun(arity_qualifier(name, arity))

  @spec implicit_fun((:none | syntaxTree()), syntaxTree(), syntaxTree()) :: syntaxTree()
  def implicit_fun(:none, name, arity), do: implicit_fun(name, arity)

  def implicit_fun(module, name, arity), do: implicit_fun(module_qualifier(module, arity_qualifier(name, arity)))

  @spec implicit_fun_name(syntaxTree()) :: syntaxTree()
  def implicit_fun_name(node) do
    case unwrap(node) do
      {:fun, pos, {:function, atom, arity}} ->
        arity_qualifier(set_pos(atom(atom), pos), set_pos(integer(arity), pos))
      {:fun, pos, {:function, module, atom, arity}} when is_atom(module) and is_atom(atom) and is_integer(arity) ->
        module_qualifier(set_pos(atom(module), pos), arity_qualifier(set_pos(atom(atom), pos), set_pos(integer(arity), pos)))
      {:fun, _Pos, {:function, module, atom, arity}} ->
        module_qualifier(module, arity_qualifier(atom, arity))
      node1 ->
        data(node1)
    end
  end

  @spec infix_expr(syntaxTree(), syntaxTree(), syntaxTree()) :: syntaxTree()
  def infix_expr(left, operator, right), do: tree(:infix_expr, infix_expr(operator: operator, left: left, right: right))

  @spec infix_expr_left(syntaxTree()) :: syntaxTree()
  def infix_expr_left(node) do
    case unwrap(node) do
      {:op, _, _, left, _} ->
        left
      node1 ->
        infix_expr(data(node1), :left)
    end
  end

  @spec infix_expr_operator(syntaxTree()) :: syntaxTree()
  def infix_expr_operator(node) do
    case unwrap(node) do
      {:op, pos, operator, _, _} ->
        set_pos(operator(operator), pos)
      node1 ->
        infix_expr(data(node1), :operator)
    end
  end

  @spec infix_expr_right(syntaxTree()) :: syntaxTree()
  def infix_expr_right(node) do
    case unwrap(node) do
      {:op, _, _, _, right} ->
        right
      node1 ->
        infix_expr(data(node1), :right)
    end
  end

  @spec integer(integer()) :: syntaxTree()
  def integer(value), do: tree(:integer, value)

  @spec integer_literal(syntaxTree()) :: charlist()
  def integer_literal(node), do: integer_to_list(integer_value(node))

  @spec integer_range_type(syntaxTree(), syntaxTree()) :: syntaxTree()
  def integer_range_type(low, high), do: tree(:integer_range_type, integer_range_type(low: low, high: high))

  @spec integer_range_type_high(syntaxTree()) :: syntaxTree()
  def integer_range_type_high(node) do
    case unwrap(node) do
      {:type, _, :range, [_, high]} ->
        high
      node1 ->
        integer_range_type(data(node1), :high)
    end
  end

  @spec integer_range_type_low(syntaxTree()) :: syntaxTree()
  def integer_range_type_low(node) do
    case unwrap(node) do
      {:type, _, :range, [low, _]} ->
        low
      node1 ->
        integer_range_type(data(node1), :low)
    end
  end

  @spec integer_value(syntaxTree()) :: integer()
  def integer_value(node) do
    case unwrap(node) do
      {:integer, _, value} ->
        value
      node1 ->
        data(node1)
    end
  end

  @spec is_atom(syntaxTree(), atom()) :: boolean()
  def is_atom(node, value) do
    case unwrap(node) do
      {:atom, _, value} ->
        true
      tree(type: :atom, data: value) ->
        true
      _ ->
        false
    end
  end

  @spec is_char(syntaxTree(), char()) :: boolean()
  def is_char(node, value) do
    case unwrap(node) do
      {:char, _, value} ->
        true
      tree(type: :char, data: value) ->
        true
      _ ->
        false
    end
  end

  @spec is_form(syntaxTree()) :: boolean()
  def is_form(node) do
    case type(node) do
      :attribute ->
        true
      :comment ->
        true
      :function ->
        true
      :eof_marker ->
        true
      :error_marker ->
        true
      :form_list ->
        true
      :warning_marker ->
        true
      :text ->
        true
      _ ->
        false
    end
  end

  @spec is_integer(syntaxTree(), integer()) :: boolean()
  def is_integer(node, value) do
    case unwrap(node) do
      {:integer, _, value} ->
        true
      tree(type: :integer, data: value) ->
        true
      _ ->
        false
    end
  end

  @spec is_leaf(syntaxTree()) :: boolean()
  def is_leaf(node) do
    case type(node) do
      :atom ->
        true
      :char ->
        true
      :comment ->
        true
      :eof_marker ->
        true
      :error_marker ->
        true
      :float ->
        true
      :fun_type ->
        true
      :integer ->
        true
      nil ->
        true
      :operator ->
        true
      :string ->
        true
      :text ->
        true
      :map_expr ->
        map_expr_fields(node) === [] and map_expr_argument(node) === :none
      :map_type ->
        map_type_fields(node) === :any_size
      :tuple ->
        tuple_elements(node) === []
      :tuple_type ->
        tuple_type_elements(node) === :any_size
      :underscore ->
        true
      :variable ->
        true
      :warning_marker ->
        true
      _ ->
        false
    end
  end

  @spec is_list_skeleton(syntaxTree()) :: boolean()
  def is_list_skeleton(node) do
    case type(node) do
      :list ->
        true
      nil ->
        true
      _ ->
        false
    end
  end

  @spec is_literal(syntaxTree()) :: boolean()
  def is_literal(t) do
    case type(t) do
      :atom ->
        true
      :integer ->
        true
      :float ->
        true
      :char ->
        true
      :string ->
        true
      nil ->
        true
      :list ->
        is_literal(list_head(t)) and is_literal(list_tail(t))
      :tuple ->
        :lists.all(&is_literal/1, tuple_elements(t))
      :map_expr ->
        (case map_expr_argument(t) do
          :none ->
            true
          arg ->
            is_literal(arg)
        end) and :lists.all(&is_literal_map_field/1, map_expr_fields(t))
      :binary ->
        :lists.all(&is_literal_binary_field/1, binary_fields(t))
      _ ->
        false
    end
  end

  @spec is_proper_list(syntaxTree()) :: boolean()
  def is_proper_list(node) do
    case type(node) do
      :list ->
        case list_suffix(node) do
          :none ->
            true
          tail ->
            is_proper_list(tail)
        end
      nil ->
        true
      _ ->
        false
    end
  end

  @spec is_string(syntaxTree(), charlist()) :: boolean()
  def is_string(node, value) do
    case unwrap(node) do
      {:string, _, value} ->
        true
      tree(type: :string, data: value) ->
        true
      _ ->
        false
    end
  end

  @spec is_tree(syntaxTree()) :: boolean()
  def is_tree(tree()), do: true

  def is_tree(_), do: false

  @spec join_comments(syntaxTree(), syntaxTree()) :: syntaxTree()
  def join_comments(source, target), do: add_postcomments(get_postcomments(source), add_precomments(get_precomments(source), target))

  @spec list([syntaxTree()]) :: syntaxTree()
  def list(list), do: list(list, :none)

  @spec list([syntaxTree()], (:none | syntaxTree())) :: syntaxTree()
  def list([], :none), do: apply(__MODULE__, nil, [])

  def list(elements, tail) when elements !== [], do: tree(:list, list(prefix: elements, suffix: tail))

  @spec list_comp(syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def list_comp(template, body), do: tree(:list_comp, list_comp(template: template, body: body))

  @spec list_comp_body(syntaxTree()) :: [syntaxTree()]
  def list_comp_body(node) do
    case unwrap(node) do
      {:lc, _, _, body} ->
        body
      node1 ->
        list_comp(data(node1), :body)
    end
  end

  @spec list_comp_template(syntaxTree()) :: syntaxTree()
  def list_comp_template(node) do
    case unwrap(node) do
      {:lc, _, template, _} ->
        template
      node1 ->
        list_comp(data(node1), :template)
    end
  end

  @spec list_elements(syntaxTree()) :: [syntaxTree()]
  def list_elements(node), do: :lists.reverse(list_elements(node, []))

  @spec list_head(syntaxTree()) :: syntaxTree()
  def list_head(node), do: hd(list_prefix(node))

  @spec list_length(syntaxTree()) :: non_neg_integer()
  def list_length(node), do: list_length(node, 0)

  @spec list_prefix(syntaxTree()) :: [syntaxTree()]
  def list_prefix(node) do
    case unwrap(node) do
      {:cons, _, head, tail} ->
        [head | cons_prefix(tail)]
      node1 ->
        list(data(node1), :prefix)
    end
  end

  @spec list_suffix(syntaxTree()) :: (:none | syntaxTree())
  def list_suffix(node) do
    case unwrap(node) do
      {:cons, _, _, tail} ->
        case cons_suffix(tail) do
          {nil, _} ->
            :none
          tail1 ->
            tail1
        end
      node1 ->
        list(data(node1), :suffix)
    end
  end

  @spec list_tail(syntaxTree()) :: syntaxTree()
  def list_tail(node) do
    tail = list_suffix(node)
    case tl(list_prefix(node)) do
      [] ->
        cond do
          tail === :none ->
            apply(__MODULE__, nil, [])
          true ->
            tail
        end
      es ->
        list(es, tail)
    end
  end

  @spec macro(syntaxTree()) :: syntaxTree()
  def macro(name), do: macro(name, :none)

  @spec macro(syntaxTree(), (:none | [syntaxTree()])) :: syntaxTree()
  def macro(name, arguments), do: tree(:macro, macro(name: name, arguments: arguments))

  @spec macro_arguments(syntaxTree()) :: (:none | [syntaxTree()])
  def macro_arguments(node), do: macro(data(node), :arguments)

  @spec macro_name(syntaxTree()) :: syntaxTree()
  def macro_name(node), do: macro(data(node), :name)

  @spec make_tree(atom(), [[syntaxTree()]]) :: syntaxTree()
  def make_tree(:annotated_type, [[n], [t]]), do: annotated_type(n, t)

  def make_tree(:application, [[f], a]), do: application(f, a)

  def make_tree(:arity_qualifier, [[n], [a]]), do: arity_qualifier(n, a)

  def make_tree(:attribute, [[n]]), do: attribute(n)

  def make_tree(:attribute, [[n], a]), do: attribute(n, a)

  def make_tree(:binary, [fs]), do: binary(fs)

  def make_tree(:binary_comp, [[t], b]), do: binary_comp(t, b)

  def make_tree(:binary_field, [[b]]), do: binary_field(b)

  def make_tree(:binary_field, [[b], ts]), do: binary_field(b, ts)

  def make_tree(:binary_generator, [[p], [e]]), do: binary_generator(p, e)

  def make_tree(:bitstring_type, [[m], [n]]), do: bitstring_type(m, n)

  def make_tree(:block_expr, [b]), do: block_expr(b)

  def make_tree(:case_expr, [[a], c]), do: case_expr(a, c)

  def make_tree(:catch_expr, [[b]]), do: catch_expr(b)

  def make_tree(:class_qualifier, [[a], [b]]), do: class_qualifier(a, b)

  def make_tree(:clause, [p, b]), do: clause(p, :none, b)

  def make_tree(:clause, [p, [g], b]), do: clause(p, g, b)

  def make_tree(:cond_expr, [c]), do: cond_expr(c)

  def make_tree(:conjunction, [e]), do: conjunction(e)

  def make_tree(:constrained_function_type, [[f], c]), do: constrained_function_type(f, c)

  def make_tree(:constraint, [[n], ts]), do: constraint(n, ts)

  def make_tree(:disjunction, [e]), do: disjunction(e)

  def make_tree(:form_list, [e]), do: form_list(e)

  def make_tree(:fun_expr, [c]), do: fun_expr(c)

  def make_tree(:function, [[n], c]), do: function(n, c)

  def make_tree(:function_type, [[t]]), do: function_type(t)

  def make_tree(:function_type, [a, [t]]), do: function_type(a, t)

  def make_tree(:generator, [[p], [e]]), do: generator(p, e)

  def make_tree(:if_expr, [c]), do: if_expr(c)

  def make_tree(:implicit_fun, [[n]]), do: implicit_fun(n)

  def make_tree(:infix_expr, [[l], [f], [r]]), do: infix_expr(l, f, r)

  def make_tree(:integer_range_type, [[l], [h]]), do: integer_range_type(l, h)

  def make_tree(:list, [p]), do: list(p)

  def make_tree(:list, [p, [s]]), do: list(p, s)

  def make_tree(:list_comp, [[t], b]), do: list_comp(t, b)

  def make_tree(:macro, [[n]]), do: macro(n)

  def make_tree(:macro, [[n], a]), do: macro(n, a)

  def make_tree(:map_expr, [fs]), do: map_expr(fs)

  def make_tree(:map_expr, [[e], fs]), do: map_expr(e, fs)

  def make_tree(:map_field_assoc, [[k], [v]]), do: map_field_assoc(k, v)

  def make_tree(:map_field_exact, [[k], [v]]), do: map_field_exact(k, v)

  def make_tree(:map_type, [fs]), do: map_type(fs)

  def make_tree(:map_type_assoc, [[n], [v]]), do: map_type_assoc(n, v)

  def make_tree(:map_type_exact, [[n], [v]]), do: map_type_exact(n, v)

  def make_tree(:match_expr, [[p], [e]]), do: match_expr(p, e)

  def make_tree(:named_fun_expr, [[n], c]), do: named_fun_expr(n, c)

  def make_tree(:module_qualifier, [[m], [n]]), do: module_qualifier(m, n)

  def make_tree(:parentheses, [[e]]), do: parentheses(e)

  def make_tree(:prefix_expr, [[f], [a]]), do: prefix_expr(f, a)

  def make_tree(:receive_expr, [c]), do: receive_expr(c)

  def make_tree(:receive_expr, [c, [e], a]), do: receive_expr(c, e, a)

  def make_tree(:record_access, [[e], [t], [f]]), do: record_access(e, t, f)

  def make_tree(:record_expr, [[t], f]), do: record_expr(t, f)

  def make_tree(:record_expr, [[e], [t], f]), do: record_expr(e, t, f)

  def make_tree(:record_field, [[n]]), do: record_field(n)

  def make_tree(:record_field, [[n], [e]]), do: record_field(n, e)

  def make_tree(:record_index_expr, [[t], [f]]), do: record_index_expr(t, f)

  def make_tree(:record_type, [[n], fs]), do: record_type(n, fs)

  def make_tree(:record_type_field, [[n], [t]]), do: record_type_field(n, t)

  def make_tree(:size_qualifier, [[n], [a]]), do: size_qualifier(n, a)

  def make_tree(:try_expr, [b, c, h, a]), do: try_expr(b, c, h, a)

  def make_tree(:tuple, [e]), do: tuple(e)

  def make_tree(:tuple_type, [es]), do: tuple_type(es)

  def make_tree(:type_application, [[n], ts]), do: type_application(n, ts)

  def make_tree(:type_union, [es]), do: type_union(es)

  def make_tree(:typed_record_field, [[f], [t]]), do: typed_record_field(f, t)

  def make_tree(:user_type_application, [[n], ts]), do: user_type_application(n, ts)

  @spec map_expr([syntaxTree()]) :: syntaxTree()
  def map_expr(fields), do: map_expr(:none, fields)

  @spec map_expr((:none | syntaxTree()), [syntaxTree()]) :: syntaxTree()
  def map_expr(argument, fields), do: tree(:map_expr, map_expr(argument: argument, fields: fields))

  @spec map_expr_argument(syntaxTree()) :: (:none | syntaxTree())
  def map_expr_argument(node) do
    case unwrap(node) do
      {:map, _, _} ->
        :none
      {:map, _, argument, _} ->
        argument
      node1 ->
        map_expr(data(node1), :argument)
    end
  end

  @spec map_expr_fields(syntaxTree()) :: [syntaxTree()]
  def map_expr_fields(node) do
    case unwrap(node) do
      {:map, _, fields} ->
        fields
      {:map, _, _, fields} ->
        fields
      node1 ->
        map_expr(data(node1), :fields)
    end
  end

  @spec map_field_assoc(syntaxTree(), syntaxTree()) :: syntaxTree()
  def map_field_assoc(name, value), do: tree(:map_field_assoc, map_field_assoc(name: name, value: value))

  @spec map_field_assoc_name(syntaxTree()) :: syntaxTree()
  def map_field_assoc_name(node) do
    case node do
      {:map_field_assoc, _, name, _} ->
        name
      _ ->
        map_field_assoc(data(node), :name)
    end
  end

  @spec map_field_assoc_value(syntaxTree()) :: syntaxTree()
  def map_field_assoc_value(node) do
    case node do
      {:map_field_assoc, _, _, value} ->
        value
      _ ->
        map_field_assoc(data(node), :value)
    end
  end

  @spec map_field_exact(syntaxTree(), syntaxTree()) :: syntaxTree()
  def map_field_exact(name, value), do: tree(:map_field_exact, map_field_exact(name: name, value: value))

  @spec map_field_exact_name(syntaxTree()) :: syntaxTree()
  def map_field_exact_name(node) do
    case node do
      {:map_field_exact, _, name, _} ->
        name
      _ ->
        map_field_exact(data(node), :name)
    end
  end

  @spec map_field_exact_value(syntaxTree()) :: syntaxTree()
  def map_field_exact_value(node) do
    case node do
      {:map_field_exact, _, _, value} ->
        value
      _ ->
        map_field_exact(data(node), :value)
    end
  end

  def map_type(), do: map_type(:any_size)

  @spec map_type((:any_size | [syntaxTree()])) :: syntaxTree()
  def map_type(fields), do: tree(:map_type, fields)

  @spec map_type_assoc(syntaxTree(), syntaxTree()) :: syntaxTree()
  def map_type_assoc(name, value), do: tree(:map_type_assoc, map_type_assoc(name: name, value: value))

  @spec map_type_assoc_name(syntaxTree()) :: syntaxTree()
  def map_type_assoc_name(node) do
    case node do
      {:type, _, :map_field_assoc, [name, _]} ->
        name
      _ ->
        map_type_assoc(data(node), :name)
    end
  end

  @spec map_type_assoc_value(syntaxTree()) :: syntaxTree()
  def map_type_assoc_value(node) do
    case node do
      {:type, _, :map_field_assoc, [_, value]} ->
        value
      _ ->
        map_type_assoc(data(node), :value)
    end
  end

  @spec map_type_exact(syntaxTree(), syntaxTree()) :: syntaxTree()
  def map_type_exact(name, value), do: tree(:map_type_exact, map_type_exact(name: name, value: value))

  @spec map_type_exact_name(syntaxTree()) :: syntaxTree()
  def map_type_exact_name(node) do
    case node do
      {:type, _, :map_field_exact, [name, _]} ->
        name
      _ ->
        map_type_exact(data(node), :name)
    end
  end

  @spec map_type_exact_value(syntaxTree()) :: syntaxTree()
  def map_type_exact_value(node) do
    case node do
      {:type, _, :map_field_exact, [_, value]} ->
        value
      _ ->
        map_type_exact(data(node), :value)
    end
  end

  @spec map_type_fields(syntaxTree()) :: (:any_size | [syntaxTree()])
  def map_type_fields(node) do
    case unwrap(node) do
      {:type, _, :map, fields} when is_list(fields) ->
        fields
      {:type, _, :map, :any} ->
        :any_size
      node1 ->
        data(node1)
    end
  end

  @spec match_expr(syntaxTree(), syntaxTree()) :: syntaxTree()
  def match_expr(pattern, body), do: tree(:match_expr, match_expr(pattern: pattern, body: body))

  @spec match_expr_body(syntaxTree()) :: syntaxTree()
  def match_expr_body(node) do
    case unwrap(node) do
      {:match, _, _, body} ->
        body
      node1 ->
        match_expr(data(node1), :body)
    end
  end

  @spec match_expr_pattern(syntaxTree()) :: syntaxTree()
  def match_expr_pattern(node) do
    case unwrap(node) do
      {:match, _, pattern, _} ->
        pattern
      node1 ->
        match_expr(data(node1), :pattern)
    end
  end

  @spec meta(syntaxTree()) :: syntaxTree()
  def meta(t) do
    case type(t) do
      :variable ->
        case :lists.member(:meta_var, get_ann(t)) do
          false ->
            meta_precomment(t)
          true ->
            set_ann(t, :lists.delete(:meta_var, get_ann(t)))
        end
      _ ->
        case has_comments(t) do
          true ->
            meta_precomment(t)
          false ->
            meta_1(t)
        end
    end
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(p0) do
    # body not decompiled
  end

  @spec module_qualifier(syntaxTree(), syntaxTree()) :: syntaxTree()
  def module_qualifier(module, body), do: tree(:module_qualifier, module_qualifier(module: module, body: body))

  @spec module_qualifier_argument(syntaxTree()) :: syntaxTree()
  def module_qualifier_argument(node) do
    case unwrap(node) do
      {:remote, _, module, _} ->
        module
      node1 ->
        module_qualifier(data(node1), :module)
    end
  end

  @spec module_qualifier_body(syntaxTree()) :: syntaxTree()
  def module_qualifier_body(node) do
    case unwrap(node) do
      {:remote, _, _, body} ->
        body
      node1 ->
        module_qualifier(data(node1), :body)
    end
  end

  @spec named_fun_expr(syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def named_fun_expr(name, clauses), do: tree(:named_fun_expr, named_fun_expr(name: name, clauses: clauses))

  @spec named_fun_expr_arity(syntaxTree()) :: arity()
  def named_fun_expr_arity(node), do: length(clause_patterns(hd(named_fun_expr_clauses(node))))

  @spec named_fun_expr_clauses(syntaxTree()) :: [syntaxTree()]
  def named_fun_expr_clauses(node) do
    case unwrap(node) do
      {:named_fun, _, _, clauses} ->
        clauses
      node1 ->
        named_fun_expr(data(node1), :clauses)
    end
  end

  @spec named_fun_expr_name(syntaxTree()) :: syntaxTree()
  def named_fun_expr_name(node) do
    case unwrap(node) do
      {:named_fun, pos, name, _} ->
        set_pos(variable(name), pos)
      node1 ->
        named_fun_expr(data(node1), :name)
    end
  end

  @spec unquote(:nil)() :: syntaxTree()
  def unquote(:nil)(), do: tree(nil)

  @spec normalize_list(syntaxTree()) :: syntaxTree()
  def normalize_list(node) do
    case type(node) do
      :list ->
        p = list_prefix(node)
        case list_suffix(node) do
          :none ->
            copy_attrs(node, normalize_list_1(p, apply(__MODULE__, nil, [])))
          tail ->
            tail1 = normalize_list(tail)
            copy_attrs(node, normalize_list_1(p, tail1))
        end
      _ ->
        node
    end
  end

  @spec operator((atom() | charlist())) :: syntaxTree()
  def operator(name) when is_atom(name), do: tree(:operator, name)

  def operator(name), do: tree(:operator, list_to_atom(name))

  @spec operator_literal(syntaxTree()) :: charlist()
  def operator_literal(node), do: atom_to_list(operator_name(node))

  @spec operator_name(syntaxTree()) :: atom()
  def operator_name(node), do: data(node)

  @spec parentheses(syntaxTree()) :: syntaxTree()
  def parentheses(expr), do: tree(:parentheses, expr)

  @spec parentheses_body(syntaxTree()) :: syntaxTree()
  def parentheses_body(node), do: data(node)

  @spec prefix_expr(syntaxTree(), syntaxTree()) :: syntaxTree()
  def prefix_expr(operator, argument), do: tree(:prefix_expr, prefix_expr(operator: operator, argument: argument))

  @spec prefix_expr_argument(syntaxTree()) :: syntaxTree()
  def prefix_expr_argument(node) do
    case unwrap(node) do
      {:op, _, _, argument} ->
        argument
      node1 ->
        prefix_expr(data(node1), :argument)
    end
  end

  @spec prefix_expr_operator(syntaxTree()) :: syntaxTree()
  def prefix_expr_operator(node) do
    case unwrap(node) do
      {:op, pos, operator, _} ->
        set_pos(operator(operator), pos)
      node1 ->
        prefix_expr(data(node1), :operator)
    end
  end

  @spec receive_expr([syntaxTree()]) :: syntaxTree()
  def receive_expr(clauses), do: receive_expr(clauses, :none, [])

  @spec receive_expr([syntaxTree()], (:none | syntaxTree()), [syntaxTree()]) :: syntaxTree()
  def receive_expr(clauses, timeout, action) do
    action1 = case timeout do
      :none ->
        []
      _ ->
        action
    end
    tree(:receive_expr, receive_expr(clauses: clauses, timeout: timeout, action: action1))
  end

  @spec receive_expr_action(syntaxTree()) :: [syntaxTree()]
  def receive_expr_action(node) do
    case unwrap(node) do
      {:receive, _, _} ->
        []
      {:receive, _, _, _, action} ->
        action
      node1 ->
        receive_expr(data(node1), :action)
    end
  end

  @spec receive_expr_clauses(syntaxTree()) :: [syntaxTree()]
  def receive_expr_clauses(node) do
    case unwrap(node) do
      {:receive, _, clauses} ->
        clauses
      {:receive, _, clauses, _, _} ->
        clauses
      node1 ->
        receive_expr(data(node1), :clauses)
    end
  end

  @spec receive_expr_timeout(syntaxTree()) :: (:none | syntaxTree())
  def receive_expr_timeout(node) do
    case unwrap(node) do
      {:receive, _, _} ->
        :none
      {:receive, _, _, timeout, _} ->
        timeout
      node1 ->
        receive_expr(data(node1), :timeout)
    end
  end

  @spec record_access(syntaxTree(), syntaxTree(), syntaxTree()) :: syntaxTree()
  def record_access(argument, type, field), do: tree(:record_access, record_access(argument: argument, type: type, field: field))

  @spec record_access_argument(syntaxTree()) :: syntaxTree()
  def record_access_argument(node) do
    case unwrap(node) do
      {:record_field, _, argument, _, _} ->
        argument
      node1 ->
        record_access(data(node1), :argument)
    end
  end

  @spec record_access_field(syntaxTree()) :: syntaxTree()
  def record_access_field(node) do
    case unwrap(node) do
      {:record_field, _, _, _, field} ->
        field
      node1 ->
        record_access(data(node1), :field)
    end
  end

  @spec record_access_type(syntaxTree()) :: syntaxTree()
  def record_access_type(node) do
    case unwrap(node) do
      {:record_field, pos, _, type, _} ->
        set_pos(atom(type), pos)
      node1 ->
        record_access(data(node1), :type)
    end
  end

  @spec record_expr(syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def record_expr(type, fields), do: record_expr(:none, type, fields)

  @spec record_expr((:none | syntaxTree()), syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def record_expr(argument, type, fields), do: tree(:record_expr, record_expr(argument: argument, type: type, fields: fields))

  @spec record_expr_argument(syntaxTree()) :: (:none | syntaxTree())
  def record_expr_argument(node) do
    case unwrap(node) do
      {:record, _, _, _} ->
        :none
      {:record, _, argument, _, _} ->
        argument
      node1 ->
        record_expr(data(node1), :argument)
    end
  end

  @spec record_expr_fields(syntaxTree()) :: [syntaxTree()]
  def record_expr_fields(node) do
    case unwrap(node) do
      {:record, _, _, fields} ->
        unfold_record_fields(fields)
      {:record, _, _, _, fields} ->
        unfold_record_fields(fields)
      node1 ->
        record_expr(data(node1), :fields)
    end
  end

  @spec record_expr_type(syntaxTree()) :: syntaxTree()
  def record_expr_type(node) do
    case unwrap(node) do
      {:record, pos, type, _} ->
        set_pos(atom(type), pos)
      {:record, pos, _, type, _} ->
        set_pos(atom(type), pos)
      node1 ->
        record_expr(data(node1), :type)
    end
  end

  @spec record_field(syntaxTree()) :: syntaxTree()
  def record_field(name), do: record_field(name, :none)

  @spec record_field(syntaxTree(), (:none | syntaxTree())) :: syntaxTree()
  def record_field(name, value), do: tree(:record_field, record_field(name: name, value: value))

  @spec record_field_name(syntaxTree()) :: syntaxTree()
  def record_field_name(node), do: record_field(data(node), :name)

  @spec record_field_value(syntaxTree()) :: (:none | syntaxTree())
  def record_field_value(node), do: record_field(data(node), :value)

  @spec record_index_expr(syntaxTree(), syntaxTree()) :: syntaxTree()
  def record_index_expr(type, field), do: tree(:record_index_expr, record_index_expr(type: type, field: field))

  @spec record_index_expr_field(syntaxTree()) :: syntaxTree()
  def record_index_expr_field(node) do
    case unwrap(node) do
      {:record_index, _, _, field} ->
        field
      node1 ->
        record_index_expr(data(node1), :field)
    end
  end

  @spec record_index_expr_type(syntaxTree()) :: syntaxTree()
  def record_index_expr_type(node) do
    case unwrap(node) do
      {:record_index, pos, type, _} ->
        set_pos(atom(type), pos)
      node1 ->
        record_index_expr(data(node1), :type)
    end
  end

  @spec record_type(syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def record_type(name, fields), do: tree(:record_type, record_type(name: name, fields: fields))

  @spec record_type_field(syntaxTree(), syntaxTree()) :: syntaxTree()
  def record_type_field(name, type), do: tree(:record_type_field, record_type_field(name: name, type: type))

  @spec record_type_field_name(syntaxTree()) :: syntaxTree()
  def record_type_field_name(node) do
    case unwrap(node) do
      {:type, _, :field_type, [name, _]} ->
        name
      node1 ->
        record_type_field(data(node1), :name)
    end
  end

  @spec record_type_field_type(syntaxTree()) :: syntaxTree()
  def record_type_field_type(node) do
    case unwrap(node) do
      {:type, _, :field_type, [_, type]} ->
        type
      node1 ->
        record_type_field(data(node1), :type)
    end
  end

  @spec record_type_fields(syntaxTree()) :: [syntaxTree()]
  def record_type_fields(node) do
    case unwrap(node) do
      {:type, _, :record, [_ | fields]} ->
        fields
      node1 ->
        record_type(data(node1), :fields)
    end
  end

  @spec record_type_name(syntaxTree()) :: syntaxTree()
  def record_type_name(node) do
    case unwrap(node) do
      {:type, _, :record, [name | _]} ->
        name
      node1 ->
        record_type(data(node1), :name)
    end
  end

  @spec remove_comments(syntaxTree()) :: syntaxTree()
  def remove_comments(node) do
    case node do
      tree(attr: attr) ->
        tree(node, attr: attr(attr, com: :none))
      wrapper(attr: attr) ->
        wrapper(node, attr: attr(attr, com: :none))
      _ ->
        node
    end
  end

  @spec revert(syntaxTree()) :: syntaxTree()
  def revert(node) do
    case is_tree(node) do
      false ->
        unwrap(node)
      true ->
        case is_leaf(node) do
          true ->
            revert_root(node)
          false ->
            gs = for l <- subtrees(node) do
              for x <- l do
              revert(x)
            end
            end
            node1 = update_tree(node, gs)
            revert_root(node1)
        end
    end
  end

  @spec revert_forms(forms()) :: [erl_parse()]
  def revert_forms(forms) when is_list(forms), do: revert_forms(form_list(forms))

  def revert_forms(t) do
    case type(t) do
      :form_list ->
        t1 = flatten_form_list(t)
        try do
          {:ok, revert_forms_1(form_list_elements(t1))}
        catch
          error -> error
        end
        |> case do
          {:ok, fs} ->
            fs
          {:error, _} = error ->
            :erlang.error(error)
          {:EXIT, r} ->
            exit(r)
          r ->
            throw(r)
        end
      _ ->
        :erlang.error({:badarg, t})
    end
  end

  @spec set_ann(syntaxTree(), [term()]) :: syntaxTree()
  def set_ann(node, as) do
    case node do
      tree(attr: attr) ->
        tree(node, attr: attr(attr, ann: as))
      wrapper(attr: attr) ->
        wrapper(node, attr: attr(attr, ann: as))
      _ ->
        set_ann(wrap(node), as)
    end
  end

  @spec set_attrs(syntaxTree(), syntaxTreeAttributes()) :: syntaxTree()
  def set_attrs(node, attr) do
    case node do
      tree() ->
        tree(node, attr: attr)
      wrapper() ->
        wrapper(node, attr: attr)
      _ ->
        set_attrs(wrap(node), attr)
    end
  end

  @spec set_pos(syntaxTree(), term()) :: syntaxTree()
  def set_pos(node, pos) do
    case node do
      tree(attr: attr) ->
        tree(node, attr: attr(attr, pos: pos))
      wrapper(attr: attr) ->
        wrapper(node, attr: attr(attr, pos: pos))
      _ ->
        set_pos(wrap(node), pos)
    end
  end

  @spec set_postcomments(syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def set_postcomments(node, cs) do
    case node do
      tree(attr: attr) ->
        tree(node, attr: set_postcomments_1(attr, cs))
      wrapper(attr: attr) ->
        wrapper(node, attr: set_postcomments_1(attr, cs))
      _ ->
        set_postcomments(wrap(node), cs)
    end
  end

  @spec set_precomments(syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def set_precomments(node, cs) do
    case node do
      tree(attr: attr) ->
        tree(node, attr: set_precomments_1(attr, cs))
      wrapper(attr: attr) ->
        wrapper(node, attr: set_precomments_1(attr, cs))
      _ ->
        set_precomments(wrap(node), cs)
    end
  end

  @spec size_qualifier(syntaxTree(), syntaxTree()) :: syntaxTree()
  def size_qualifier(body, size), do: tree(:size_qualifier, size_qualifier(body: body, size: size))

  @spec size_qualifier_argument(syntaxTree()) :: syntaxTree()
  def size_qualifier_argument(node), do: size_qualifier(data(node), :size)

  @spec size_qualifier_body(syntaxTree()) :: syntaxTree()
  def size_qualifier_body(node), do: size_qualifier(data(node), :body)

  @spec string(charlist()) :: syntaxTree()
  def string(string), do: tree(:string, string)

  @spec string_literal(syntaxTree()) :: [char(), ...]
  def string_literal(node), do: string_literal(node, :latin1)

  @spec string_literal(syntaxTree(), encoding()) :: [char(), ...]
  def string_literal(node, :utf8), do: :io_lib.write_string(string_value(node))

  def string_literal(node, :unicode), do: :io_lib.write_string(string_value(node))

  def string_literal(node, :latin1), do: :io_lib.write_string_as_latin1(string_value(node))

  @spec string_value(syntaxTree()) :: charlist()
  def string_value(node) do
    case unwrap(node) do
      {:string, _, list} ->
        list
      node1 ->
        data(node1)
    end
  end

  @spec subtrees(syntaxTree()) :: [[syntaxTree()]]
  def subtrees(t) do
    case is_leaf(t) do
      true ->
        []
      false ->
        case type(t) do
          :annotated_type ->
            [[annotated_type_name(t)], [annotated_type_body(t)]]
          :application ->
            [[application_operator(t)], application_arguments(t)]
          :arity_qualifier ->
            [[arity_qualifier_body(t)], [arity_qualifier_argument(t)]]
          :attribute ->
            case attribute_arguments(t) do
              :none ->
                [[attribute_name(t)]]
              as ->
                [[attribute_name(t)], as]
            end
          :binary ->
            [binary_fields(t)]
          :binary_comp ->
            [[binary_comp_template(t)], binary_comp_body(t)]
          :binary_field ->
            case binary_field_types(t) do
              [] ->
                [[binary_field_body(t)]]
              ts ->
                [[binary_field_body(t)], ts]
            end
          :binary_generator ->
            [[binary_generator_pattern(t)], [binary_generator_body(t)]]
          :bitstring_type ->
            [[bitstring_type_m(t)], [bitstring_type_n(t)]]
          :block_expr ->
            [block_expr_body(t)]
          :case_expr ->
            [[case_expr_argument(t)], case_expr_clauses(t)]
          :catch_expr ->
            [[catch_expr_body(t)]]
          :class_qualifier ->
            [[class_qualifier_argument(t)], [class_qualifier_body(t)]]
          :clause ->
            case clause_guard(t) do
              :none ->
                [clause_patterns(t), clause_body(t)]
              g ->
                [clause_patterns(t), [g], clause_body(t)]
            end
          :cond_expr ->
            [cond_expr_clauses(t)]
          :conjunction ->
            [conjunction_body(t)]
          :constrained_function_type ->
            c = constrained_function_type_argument(t)
            [[constrained_function_type_body(t)], conjunction_body(c)]
          :constraint ->
            [[constraint_argument(t)], constraint_body(t)]
          :disjunction ->
            [disjunction_body(t)]
          :form_list ->
            [form_list_elements(t)]
          :fun_expr ->
            [fun_expr_clauses(t)]
          :fun_type ->
            []
          :function ->
            [[function_name(t)], function_clauses(t)]
          :function_type ->
            case function_type_arguments(t) do
              :any_arity ->
                [[function_type_return(t)]]
              as ->
                [as, [function_type_return(t)]]
            end
          :generator ->
            [[generator_pattern(t)], [generator_body(t)]]
          :if_expr ->
            [if_expr_clauses(t)]
          :implicit_fun ->
            [[implicit_fun_name(t)]]
          :infix_expr ->
            [[infix_expr_left(t)], [infix_expr_operator(t)], [infix_expr_right(t)]]
          :integer_range_type ->
            [[integer_range_type_low(t)], [integer_range_type_high(t)]]
          :list ->
            case list_suffix(t) do
              :none ->
                [list_prefix(t)]
              s ->
                [list_prefix(t), [s]]
            end
          :list_comp ->
            [[list_comp_template(t)], list_comp_body(t)]
          :macro ->
            case macro_arguments(t) do
              :none ->
                [[macro_name(t)]]
              as ->
                [[macro_name(t)], as]
            end
          :map_expr ->
            case map_expr_argument(t) do
              :none ->
                [map_expr_fields(t)]
              v ->
                [[v], map_expr_fields(t)]
            end
          :map_field_assoc ->
            [[map_field_assoc_name(t)], [map_field_assoc_value(t)]]
          :map_field_exact ->
            [[map_field_exact_name(t)], [map_field_exact_value(t)]]
          :map_type ->
            [map_type_fields(t)]
          :map_type_assoc ->
            [[map_type_assoc_name(t)], [map_type_assoc_value(t)]]
          :map_type_exact ->
            [[map_type_exact_name(t)], [map_type_exact_value(t)]]
          :match_expr ->
            [[match_expr_pattern(t)], [match_expr_body(t)]]
          :module_qualifier ->
            [[module_qualifier_argument(t)], [module_qualifier_body(t)]]
          :named_fun_expr ->
            [[named_fun_expr_name(t)], named_fun_expr_clauses(t)]
          :parentheses ->
            [[parentheses_body(t)]]
          :prefix_expr ->
            [[prefix_expr_operator(t)], [prefix_expr_argument(t)]]
          :receive_expr ->
            case receive_expr_timeout(t) do
              :none ->
                [receive_expr_clauses(t)]
              e ->
                [receive_expr_clauses(t), [e], receive_expr_action(t)]
            end
          :record_access ->
            [[record_access_argument(t)], [record_access_type(t)], [record_access_field(t)]]
          :record_expr ->
            case record_expr_argument(t) do
              :none ->
                [[record_expr_type(t)], record_expr_fields(t)]
              v ->
                [[v], [record_expr_type(t)], record_expr_fields(t)]
            end
          :record_field ->
            case record_field_value(t) do
              :none ->
                [[record_field_name(t)]]
              v ->
                [[record_field_name(t)], [v]]
            end
          :record_index_expr ->
            [[record_index_expr_type(t)], [record_index_expr_field(t)]]
          :record_type ->
            [[record_type_name(t)], record_type_fields(t)]
          :record_type_field ->
            [[record_type_field_name(t)], [record_type_field_type(t)]]
          :size_qualifier ->
            [[size_qualifier_body(t)], [size_qualifier_argument(t)]]
          :try_expr ->
            [try_expr_body(t), try_expr_clauses(t), try_expr_handlers(t), try_expr_after(t)]
          :tuple ->
            [tuple_elements(t)]
          :tuple_type ->
            [tuple_type_elements(t)]
          :type_application ->
            [[type_application_name(t)], type_application_arguments(t)]
          :type_union ->
            [type_union_types(t)]
          :typed_record_field ->
            [[typed_record_field_body(t)], [typed_record_field_type(t)]]
          :user_type_application ->
            [[user_type_application_name(t)], user_type_application_arguments(t)]
        end
    end
  end

  @spec text(charlist()) :: syntaxTree()
  def text(string), do: tree(:text, string)

  @spec text_string(syntaxTree()) :: charlist()
  def text_string(node), do: data(node)

  @spec tree(atom()) :: tree()
  def tree(type), do: tree(type, [])

  @spec tree(atom(), term()) :: tree()
  def tree(type, data), do: tree(type: type, data: data)

  @spec try_after_expr([syntaxTree()], [syntaxTree()]) :: syntaxTree()
  def try_after_expr(body, erlangVariableAfter), do: try_expr(body, [], [], erlangVariableAfter)

  @spec try_expr([syntaxTree()], [syntaxTree()]) :: syntaxTree()
  def try_expr(body, handlers), do: try_expr(body, [], handlers)

  @spec try_expr([syntaxTree()], [syntaxTree()], [syntaxTree()]) :: syntaxTree()
  def try_expr(body, clauses, handlers), do: try_expr(body, clauses, handlers, [])

  @spec try_expr([syntaxTree()], [syntaxTree()], [syntaxTree()], [syntaxTree()]) :: syntaxTree()
  def try_expr(body, clauses, handlers, erlangVariableAfter), do: tree(:try_expr, try_expr(body: body, clauses: clauses, handlers: handlers, after: erlangVariableAfter))

  @spec try_expr_after(syntaxTree()) :: [syntaxTree()]
  def try_expr_after(node) do
    case unwrap(node) do
      {:try, _, _, _, _, erlangVariableAfter} ->
        erlangVariableAfter
      node1 ->
        try_expr(data(node1), :after)
    end
  end

  @spec try_expr_body(syntaxTree()) :: [syntaxTree()]
  def try_expr_body(node) do
    case unwrap(node) do
      {:try, _, body, _, _, _} ->
        body
      node1 ->
        try_expr(data(node1), :body)
    end
  end

  @spec try_expr_clauses(syntaxTree()) :: [syntaxTree()]
  def try_expr_clauses(node) do
    case unwrap(node) do
      {:try, _, _, clauses, _, _} ->
        clauses
      node1 ->
        try_expr(data(node1), :clauses)
    end
  end

  @spec try_expr_handlers(syntaxTree()) :: [syntaxTree()]
  def try_expr_handlers(node) do
    case unwrap(node) do
      {:try, _, _, _, handlers, _} ->
        unfold_try_clauses(handlers)
      node1 ->
        try_expr(data(node1), :handlers)
    end
  end

  @spec tuple([syntaxTree()]) :: syntaxTree()
  def tuple(list), do: tree(:tuple, list)

  @spec tuple_elements(syntaxTree()) :: [syntaxTree()]
  def tuple_elements(node) do
    case unwrap(node) do
      {:tuple, _, list} ->
        list
      node1 ->
        data(node1)
    end
  end

  @spec tuple_size(syntaxTree()) :: non_neg_integer()
  def tuple_size(node), do: length(tuple_elements(node))

  def tuple_type(), do: tuple_type(:any_size)

  @spec tuple_type((:any_size | [syntaxTree()])) :: syntaxTree()
  def tuple_type(elements), do: tree(:tuple_type, elements)

  @spec tuple_type_elements(syntaxTree()) :: (:any_size | [syntaxTree()])
  def tuple_type_elements(node) do
    case unwrap(node) do
      {:type, _, :tuple, elements} when is_list(elements) ->
        elements
      {:type, _, :tuple, :any} ->
        :any_size
      node1 ->
        data(node1)
    end
  end

  @spec type(syntaxTree()) :: atom()
  def type(tree(type: t)), do: t

  def type(wrapper(type: t)), do: t

  def type(node) do
    case node do
      {:atom, _, _} ->
        :atom
      {:char, _, _} ->
        :char
      {:float, _, _} ->
        :float
      {:integer, _, _} ->
        :integer
      {nil, _} ->
        nil
      {:string, _, _} ->
        :string
      {:var, _, name} ->
        cond do
          name === :_ ->
            :underscore
          true ->
            :variable
        end
      {:error, _} ->
        :error_marker
      {:warning, _} ->
        :warning_marker
      {:eof, _} ->
        :eof_marker
      {:case, _, _, _} ->
        :case_expr
      {:catch, _, _} ->
        :catch_expr
      {:cond, _, _} ->
        :cond_expr
      {:fun, _, {:clauses, _}} ->
        :fun_expr
      {:named_fun, _, _, _} ->
        :named_fun_expr
      {:fun, _, {:function, _, _}} ->
        :implicit_fun
      {:fun, _, {:function, _, _, _}} ->
        :implicit_fun
      {:if, _, _} ->
        :if_expr
      {:receive, _, _, _, _} ->
        :receive_expr
      {:receive, _, _} ->
        :receive_expr
      {:attribute, _, _, _} ->
        :attribute
      {:bin, _, _} ->
        :binary
      {:bin_element, _, _, _, _} ->
        :binary_field
      {:block, _, _} ->
        :block_expr
      {:call, _, _, _} ->
        :application
      {:clause, _, _, _, _} ->
        :clause
      {:cons, _, _, _} ->
        :list
      {:function, _, _, _, _} ->
        :function
      {:b_generate, _, _, _} ->
        :binary_generator
      {:generate, _, _, _} ->
        :generator
      {:lc, _, _, _} ->
        :list_comp
      {:bc, _, _, _} ->
        :binary_comp
      {:match, _, _, _} ->
        :match_expr
      {:map, _, _, _} ->
        :map_expr
      {:map, _, _} ->
        :map_expr
      {:map_field_assoc, _, _, _} ->
        :map_field_assoc
      {:map_field_exact, _, _, _} ->
        :map_field_exact
      {:op, _, _, _, _} ->
        :infix_expr
      {:op, _, _, _} ->
        :prefix_expr
      {:record, _, _, _, _} ->
        :record_expr
      {:record, _, _, _} ->
        :record_expr
      {:record_field, _, _, _, _} ->
        :record_access
      {:record_index, _, _, _} ->
        :record_index_expr
      {:remote, _, _, _} ->
        :module_qualifier
      {:try, _, _, _, _, _} ->
        :try_expr
      {:tuple, _, _} ->
        :tuple
      {:ann_type, _, _} ->
        :annotated_type
      {:remote_type, _, _} ->
        :type_application
      {:type, _, :binary, [_, _]} ->
        :bitstring_type
      {:type, _, :bounded_fun, [_, _]} ->
        :constrained_function_type
      {:type, _, :constraint, [_, _]} ->
        :constraint
      {:type, _, :fun, []} ->
        :fun_type
      {:type, _, :fun, [_, _]} ->
        :function_type
      {:type, _, :map, _} ->
        :map_type
      {:type, _, :map_field_assoc, _} ->
        :map_type_assoc
      {:type, _, :map_field_exact, _} ->
        :map_type_exact
      {:type, _, :record, _} ->
        :record_type
      {:type, _, :field_type, _} ->
        :record_type_field
      {:type, _, :range, _} ->
        :integer_range_type
      {:type, _, :tuple, _} ->
        :tuple_type
      {:type, _, :union, _} ->
        :type_union
      {:type, _, _, _} ->
        :type_application
      {:user_type, _, _, _} ->
        :user_type_application
      _ ->
        :erlang.error({:badarg, node})
    end
  end

  @spec type_application(syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def type_application(typeName, arguments), do: tree(:type_application, type_application(type_name: typeName, arguments: arguments))

  @spec type_application((:none | syntaxTree()), syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def type_application(:none, typeName, arguments), do: type_application(typeName, arguments)

  def type_application(module, typeName, arguments), do: type_application(module_qualifier(module, typeName), arguments)

  @spec type_application_arguments(syntaxTree()) :: [syntaxTree()]
  def type_application_arguments(node) do
    case unwrap(node) do
      {:remote_type, _, [_, _, arguments]} ->
        arguments
      {:type, _, _, arguments} ->
        arguments
      node1 ->
        type_application(data(node1), :arguments)
    end
  end

  @spec type_application_name(syntaxTree()) :: syntaxTree()
  def type_application_name(node) do
    case unwrap(node) do
      {:remote_type, _, [module, name, _]} ->
        module_qualifier(module, name)
      {:type, pos, name, _} ->
        set_pos(atom(name), pos)
      node1 ->
        type_application(data(node1), :type_name)
    end
  end

  @spec type_union([syntaxTree()]) :: syntaxTree()
  def type_union(types), do: tree(:type_union, types)

  @spec type_union_types(syntaxTree()) :: [syntaxTree()]
  def type_union_types(node) do
    case unwrap(node) do
      {:type, _, :union, types} when is_list(types) ->
        types
      node1 ->
        data(node1)
    end
  end

  @spec typed_record_field(syntaxTree(), syntaxTree()) :: syntaxTree()
  def typed_record_field(field, type), do: tree(:typed_record_field, typed_record_field(body: field, type: type))

  @spec typed_record_field_body(syntaxTree()) :: syntaxTree()
  def typed_record_field_body(node), do: typed_record_field(data(node), :body)

  @spec typed_record_field_type(syntaxTree()) :: syntaxTree()
  def typed_record_field_type(node), do: typed_record_field(data(node), :type)

  @spec underscore() :: syntaxTree()
  def underscore(), do: tree(:underscore, [])

  @spec update_tree(syntaxTree(), [[syntaxTree()]]) :: syntaxTree()
  def update_tree(node, groups), do: copy_attrs(node, make_tree(type(node), groups))

  @spec user_type_application(syntaxTree(), [syntaxTree()]) :: syntaxTree()
  def user_type_application(typeName, arguments), do: tree(:user_type_application, user_type_application(type_name: typeName, arguments: arguments))

  @spec user_type_application_arguments(syntaxTree()) :: [syntaxTree()]
  def user_type_application_arguments(node) do
    case unwrap(node) do
      {:user_type, _, _, arguments} ->
        arguments
      node1 ->
        user_type_application(data(node1), :arguments)
    end
  end

  @spec user_type_application_name(syntaxTree()) :: syntaxTree()
  def user_type_application_name(node) do
    case unwrap(node) do
      {:user_type, pos, name, _} ->
        set_pos(atom(name), pos)
      node1 ->
        user_type_application(data(node1), :type_name)
    end
  end

  @spec variable((atom() | charlist())) :: syntaxTree()
  def variable(name) when is_atom(name), do: tree(:variable, name)

  def variable(name), do: tree(:variable, list_to_atom(name))

  @spec variable_literal(syntaxTree()) :: charlist()
  def variable_literal(node) do
    case unwrap(node) do
      {:var, _, name} ->
        atom_to_list(name)
      node1 ->
        atom_to_list(data(node1))
    end
  end

  @spec variable_name(syntaxTree()) :: atom()
  def variable_name(node) do
    case unwrap(node) do
      {:var, _, name} ->
        name
      node1 ->
        data(node1)
    end
  end

  @spec warning_marker(term()) :: syntaxTree()
  def warning_marker(warning), do: tree(:warning_marker, warning)

  @spec warning_marker_info(syntaxTree()) :: term()
  def warning_marker_info(node) do
    case unwrap(node) do
      {:warning, error} ->
        error
      t ->
        data(t)
    end
  end

  # Private Functions

  defp unquote(:"-abstract/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-abstract/1-lc$^1/1-1-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-concrete/1-fun-2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-concrete/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-concrete/1-lc$^1/1-1-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-fold_binary_field_types/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-fold_function_names/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-fold_record_fields/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-fold_variable_names/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-is_literal/1-fun-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-is_literal/1-fun-1-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-is_literal/1-fun-2-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-meta_1/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-meta_subtrees/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-meta_subtrees/1-lc$^1/1-1-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-normalize_list_1/2-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-revert/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-revert/1-lc$^1/1-1-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-revert_case_expr/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-revert_clause_disjunction/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-revert_cond_expr/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-revert_fun_expr/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-revert_function/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-revert_if_expr/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-revert_list/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-revert_named_fun_expr/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-revert_receive_expr/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-revert_try_expr/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-revert_try_expr/1-lc$^1/1-1-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-unfold_binary_field_types/2-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-unfold_function_names/2-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-unfold_function_names/2-lc$^1/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-unfold_record_fields/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-unfold_try_clauses/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-unfold_variable_names/2-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp abstract_list([t | ts]), do: [abstract(t) | abstract_list(ts)]

  defp abstract_list([]), do: []

  defp abstract_tail(h1, [h2 | t]), do: cons(abstract(h1), abstract_tail(h2, t))

  defp abstract_tail(h, t), do: cons(abstract(h), abstract(t))

  defp add_postcomments_1(cs, attr(com: :none) = attr), do: attr(attr, com: com(post: cs))

  defp add_postcomments_1(cs, attr(com: com) = attr), do: attr(attr, com: com(com, post: com(com, :post) ++ cs))

  defp add_precomments_1(cs, attr(com: :none) = attr), do: attr(attr, com: com(pre: cs))

  defp add_precomments_1(cs, attr(com: com) = attr), do: attr(attr, com: com(com, pre: com(com, :pre) ++ cs))

  defp concrete_list([e | es]), do: [concrete(e) | concrete_list(es)]

  defp concrete_list([]), do: []

  defp conjunction_list([l | ls]), do: [conjunction(l) | conjunction_list(ls)]

  defp conjunction_list([]), do: []

  defp cons_prefix({:cons, _, head, tail}), do: [head | cons_prefix(tail)]

  defp cons_prefix(_), do: []

  defp cons_suffix({:cons, _, _, tail}), do: cons_suffix(tail)

  defp cons_suffix(tail), do: tail

  defp flatten_form_list_1([f | fs], as) do
    case type(f) do
      :form_list ->
        as1 = flatten_form_list_1(form_list_elements(f), as)
        flatten_form_list_1(fs, as1)
      _ ->
        flatten_form_list_1(fs, [f | as])
    end
  end

  defp flatten_form_list_1([], as), do: as

  defp fold_binary_field_type(node) do
    case type(node) do
      :size_qualifier ->
        {concrete(size_qualifier_body(node)), concrete(size_qualifier_argument(node))}
      _ ->
        concrete(node)
    end
  end

  defp fold_binary_field_types(ts) do
    for t <- ts do
      fold_binary_field_type(t)
    end
  end

  defp fold_function_name(n) do
    name = arity_qualifier_body(n)
    arity = arity_qualifier_argument(n)
    true = type(name) === :atom and type(arity) === :integer
    {concrete(name), concrete(arity)}
  end

  defp fold_function_names(ns) do
    for n <- ns do
      fold_function_name(n)
    end
  end

  defp fold_record_field(f) do
    case type(f) do
      :typed_record_field ->
        field = fold_record_field_1(typed_record_field_body(f))
        type = typed_record_field_type(f)
        {:typed_record_field, field, type}
      :record_field ->
        fold_record_field_1(f)
    end
  end

  defp fold_record_field_1(f) do
    pos = get_pos(f)
    name = record_field_name(f)
    case record_field_value(f) do
      :none ->
        {:record_field, pos, name}
      value ->
        {:record_field, pos, name, value}
    end
  end

  defp fold_record_fields(fs) do
    for f <- fs do
      fold_record_field(f)
    end
  end

  defp fold_try_clause({:clause, pos, [p], guard, body}) do
    p1 = case type(p) do
      :class_qualifier ->
        {:tuple, pos, [class_qualifier_argument(p), class_qualifier_body(p), {:var, pos, :_}]}
      _ ->
        {:tuple, pos, [{:atom, pos, :throw}, p, {:var, pos, :_}]}
    end
    {:clause, pos, [p1], guard, body}
  end

  defp fold_variable_names(vs) do
    for v <- vs do
      variable_name(v)
    end
  end

  defp get_com(tree(attr: attr)), do: attr(attr, :com)

  defp get_com(wrapper(attr: attr)), do: attr(attr, :com)

  defp get_com(_), do: :none

  defp get_postcomments_1(attr(com: :none)), do: []

  defp get_postcomments_1(attr(com: com(post: cs))), do: cs

  defp get_precomments_1(attr(com: :none)), do: []

  defp get_precomments_1(attr(com: com(pre: cs))), do: cs

  defp is_literal_binary_field(f) do
    case binary_field_types(f) do
      [] ->
        is_literal(binary_field_body(f))
      _ ->
        false
    end
  end

  defp is_literal_map_field(f) do
    case type(f) do
      :map_field_assoc ->
        is_literal(map_field_assoc_name(f)) and is_literal(map_field_assoc_value(f))
      :map_field_exact ->
        false
    end
  end

  defp is_printable(s), do: :io_lib.printable_list(s)

  defp list_elements(node, as) do
    case type(node) do
      :list ->
        as1 = :lists.reverse(list_prefix(node)) ++ as
        case list_suffix(node) do
          :none ->
            as1
          tail ->
            list_elements(tail, as1)
        end
      nil ->
        as
    end
  end

  defp list_length(node, a) do
    case type(node) do
      :list ->
        a1 = length(list_prefix(node)) + a
        case list_suffix(node) do
          :none ->
            a1
          tail ->
            list_length(tail, a1)
        end
      nil ->
        a
    end
  end

  defp make_float(value), do: tree(:float, value)

  defp meta_0(t), do: meta_1(remove_comments(t))

  defp meta_1(t) do
    case type(t) do
      :atom ->
        meta_call(:atom, [t])
      :char ->
        meta_call(:char, [t])
      :comment ->
        meta_call(:comment, [list((for s <- comment_text(t) do
          string(s)
        end))])
      :eof_marker ->
        meta_call(:eof_marker, [])
      :error_marker ->
        meta_call(:error_marker, [abstract(error_marker_info(t))])
      :float ->
        meta_call(:float, [t])
      :integer ->
        meta_call(:integer, [t])
      nil ->
        meta_call(nil, [])
      :operator ->
        meta_call(:operator, [atom(operator_name(t))])
      :string ->
        meta_call(:string, [t])
      :text ->
        meta_call(:text, [string(text_string(t))])
      :underscore ->
        meta_call(:underscore, [])
      :variable ->
        meta_call(:variable, [string(atom_to_list(variable_name(t)))])
      :warning_marker ->
        meta_call(:warning_marker, [abstract(warning_marker_info(t))])
      :list ->
        case list_suffix(t) do
          :none ->
            meta_call(:list, [list(meta_list(list_prefix(t)))])
          s ->
            meta_call(:list, [list(meta_list(list_prefix(t))), meta(s)])
        end
      :tuple ->
        meta_call(:tuple, [list(meta_list(tuple_elements(t)))])
      type ->
        meta_call(:make_tree, [abstract(type), meta_subtrees(subtrees(t))])
    end
  end

  defp meta_call(f, as), do: application(atom(:erl_syntax), atom(f), as)

  defp meta_list([t | ts]), do: [meta(t) | meta_list(ts)]

  defp meta_list([]), do: []

  defp meta_postcomment(t) do
    case get_postcomments(t) do
      [] ->
        meta_0(t)
      cs ->
        meta_call(:set_postcomments, [meta_0(t), list(meta_list(cs))])
    end
  end

  defp meta_precomment(t) do
    case get_precomments(t) do
      [] ->
        meta_postcomment(t)
      cs ->
        meta_call(:set_precomments, [meta_postcomment(t), list(meta_list(cs))])
    end
  end

  defp meta_subtrees(gs) do
    list((for g <- gs do
      list((for t <- g do
      meta(t)
    end))
    end))
  end

  defp normalize_list_1(es, tail) do
    :lists.foldr(fn x, a ->
        list([x], a)
    end, tail, es)
  end

  defp revert_annotated_type(node) do
    pos = get_pos(node)
    name = annotated_type_name(node)
    type = annotated_type_body(node)
    {:ann_type, pos, [name, type]}
  end

  defp revert_application(node) do
    pos = get_pos(node)
    operator = application_operator(node)
    arguments = application_arguments(node)
    {:call, pos, operator, arguments}
  end

  defp revert_atom(node) do
    pos = get_pos(node)
    {:atom, pos, atom_value(node)}
  end

  defp revert_attribute(node) do
    name = attribute_name(node)
    args = attribute_arguments(node)
    pos = get_pos(node)
    case type(name) do
      :atom ->
        revert_attribute_1(atom_value(name), args, pos, node)
      _ ->
        node
    end
  end

  defp revert_attribute_1(:module, [m], pos, node) do
    case revert_module_name(m) do
      {:ok, a} ->
        {:attribute, pos, :module, a}
      :error ->
        node
    end
  end

  defp revert_attribute_1(:module, [m, list], pos, node) do
    vs = case is_list_skeleton(list) do
      true ->
        case is_proper_list(list) do
          true ->
            fold_variable_names(list_elements(list))
          false ->
            node
        end
      false ->
        node
    end
    case revert_module_name(m) do
      {:ok, a} ->
        {:attribute, pos, :module, {a, vs}}
      :error ->
        node
    end
  end

  defp revert_attribute_1(:export, [list], pos, node) do
    case is_list_skeleton(list) do
      true ->
        case is_proper_list(list) do
          true ->
            fs = fold_function_names(list_elements(list))
            {:attribute, pos, :export, fs}
          false ->
            node
        end
      false ->
        node
    end
  end

  defp revert_attribute_1(:import, [m], pos, node) do
    case revert_module_name(m) do
      {:ok, a} ->
        {:attribute, pos, :import, a}
      :error ->
        node
    end
  end

  defp revert_attribute_1(:import, [m, list], pos, node) do
    case revert_module_name(m) do
      {:ok, a} ->
        case is_list_skeleton(list) do
          true ->
            case is_proper_list(list) do
              true ->
                fs = fold_function_names(list_elements(list))
                {:attribute, pos, :import, {a, fs}}
              false ->
                node
            end
          false ->
            node
        end
      :error ->
        node
    end
  end

  defp revert_attribute_1(:file, [a, line], pos, node) do
    case type(a) do
      :string ->
        case type(line) do
          :integer ->
            {:attribute, pos, :file, {concrete(a), concrete(line)}}
          _ ->
            node
        end
      _ ->
        node
    end
  end

  defp revert_attribute_1(:record, [a, tuple], pos, node) do
    case type(a) do
      :atom ->
        case type(tuple) do
          :tuple ->
            fs = fold_record_fields(tuple_elements(tuple))
            {:attribute, pos, :record, {concrete(a), fs}}
          _ ->
            node
        end
      _ ->
        node
    end
  end

  defp revert_attribute_1(n, [t], pos, _), do: {:attribute, pos, n, concrete(t)}

  defp revert_attribute_1(_, _, _, node), do: node

  defp revert_binary(node) do
    pos = get_pos(node)
    {:bin, pos, binary_fields(node)}
  end

  defp revert_binary_comp(node) do
    pos = get_pos(node)
    template = binary_comp_template(node)
    body = binary_comp_body(node)
    {:bc, pos, template, body}
  end

  defp revert_binary_field(node) do
    pos = get_pos(node)
    body = binary_field_body(node)
    {expr, size} = case type(body) do
      :size_qualifier ->
        {size_qualifier_body(body), size_qualifier_argument(body)}
      _ ->
        {body, :default}
    end
    types = case binary_field_types(node) do
      [] ->
        :default
      ts ->
        fold_binary_field_types(ts)
    end
    {:bin_element, pos, expr, size, types}
  end

  defp revert_binary_generator(node) do
    pos = get_pos(node)
    pattern = binary_generator_pattern(node)
    body = binary_generator_body(node)
    {:b_generate, pos, pattern, body}
  end

  defp revert_bitstring_type(node) do
    pos = get_pos(node)
    m = bitstring_type_m(node)
    n = bitstring_type_n(node)
    {:type, pos, :binary, [m, n]}
  end

  defp revert_block_expr(node) do
    pos = get_pos(node)
    body = block_expr_body(node)
    {:block, pos, body}
  end

  defp revert_case_expr(node) do
    pos = get_pos(node)
    argument = case_expr_argument(node)
    clauses = for c <- case_expr_clauses(node) do
      revert_clause(c)
    end
    {:case, pos, argument, clauses}
  end

  defp revert_catch_expr(node) do
    pos = get_pos(node)
    expr = catch_expr_body(node)
    {:catch, pos, expr}
  end

  defp revert_char(node) do
    pos = get_pos(node)
    {:char, pos, char_value(node)}
  end

  defp revert_clause(node) do
    pos = get_pos(node)
    guard = case clause_guard(node) do
      :none ->
        []
      e ->
        case type(e) do
          :disjunction ->
            revert_clause_disjunction(e)
          :conjunction ->
            [conjunction_body(e)]
          _ ->
            [[e]]
        end
    end
    {:clause, pos, clause_patterns(node), guard, clause_body(node)}
  end

  defp revert_clause_disjunction(d) do
    for e <- disjunction_body(d) do
      case type(e) do
      :conjunction ->
        conjunction_body(e)
      _ ->
        [e]
    end
    end
  end

  defp revert_cond_expr(node) do
    pos = get_pos(node)
    clauses = for c <- cond_expr_clauses(node) do
      revert_clause(c)
    end
    {:cond, pos, clauses}
  end

  defp revert_constrained_function_type(node) do
    pos = get_pos(node)
    functionType = constrained_function_type_body(node)
    functionConstraint = conjunction_body(constrained_function_type_argument(node))
    {:type, pos, :bounded_fun, [functionType, functionConstraint]}
  end

  defp revert_constraint(node) do
    pos = get_pos(node)
    name = constraint_argument(node)
    types = constraint_body(node)
    {:type, pos, :constraint, [name, types]}
  end

  defp revert_eof_marker(node) do
    pos = get_pos(node)
    {:eof, pos}
  end

  defp revert_error_marker(node), do: {:error, error_marker_info(node)}

  defp revert_float(node) do
    pos = get_pos(node)
    {:float, pos, float_value(node)}
  end

  defp revert_forms_1([t | ts]) do
    case type(t) do
      :comment ->
        revert_forms_1(ts)
      _ ->
        t1 = revert(t)
        case is_tree(t1) do
          true ->
            throw({:error, t1})
          false ->
            [t1 | revert_forms_1(ts)]
        end
    end
  end

  defp revert_forms_1([]), do: []

  defp revert_fun_expr(node) do
    clauses = for c <- fun_expr_clauses(node) do
      revert_clause(c)
    end
    pos = get_pos(node)
    {:fun, pos, {:clauses, clauses}}
  end

  defp revert_fun_type(node) do
    pos = get_pos(node)
    {:type, pos, :fun, []}
  end

  defp revert_function(node) do
    name = function_name(node)
    clauses = for c <- function_clauses(node) do
      revert_clause(c)
    end
    pos = get_pos(node)
    case type(name) do
      :atom ->
        a = function_arity(node)
        {:function, pos, concrete(name), a, clauses}
      _ ->
        node
    end
  end

  defp revert_function_type(node) do
    pos = get_pos(node)
    type = function_type_return(node)
    case function_type_arguments(node) do
      :any_arity ->
        {:type, pos, :fun, [{:type, pos, :any}, type]}
      arguments ->
        {:type, pos, :fun, [{:type, pos, :product, arguments}, type]}
    end
  end

  defp revert_generator(node) do
    pos = get_pos(node)
    pattern = generator_pattern(node)
    body = generator_body(node)
    {:generate, pos, pattern, body}
  end

  defp revert_if_expr(node) do
    pos = get_pos(node)
    clauses = for c <- if_expr_clauses(node) do
      revert_clause(c)
    end
    {:if, pos, clauses}
  end

  defp revert_implicit_fun(node) do
    pos = get_pos(node)
    name = implicit_fun_name(node)
    case type(name) do
      :arity_qualifier ->
        f = arity_qualifier_body(name)
        a = arity_qualifier_argument(name)
        case {type(f), type(a)} do
          {:atom, :integer} ->
            {:fun, pos, {:function, concrete(f), concrete(a)}}
          _ ->
            node
        end
      :module_qualifier ->
        m = module_qualifier_argument(name)
        name1 = module_qualifier_body(name)
        case type(name1) do
          :arity_qualifier ->
            f = arity_qualifier_body(name1)
            a = arity_qualifier_argument(name1)
            {:fun, pos, {:function, m, f, a}}
          _ ->
            node
        end
      _ ->
        node
    end
  end

  defp revert_infix_expr(node) do
    pos = get_pos(node)
    operator = infix_expr_operator(node)
    left = infix_expr_left(node)
    right = infix_expr_right(node)
    case type(operator) do
      :operator ->
        {:op, pos, operator_name(operator), left, right}
      _ ->
        node
    end
  end

  defp revert_integer(node) do
    pos = get_pos(node)
    {:integer, pos, integer_value(node)}
  end

  defp revert_integer_range_type(node) do
    pos = get_pos(node)
    low = integer_range_type_low(node)
    high = integer_range_type_high(node)
    {:type, pos, :range, [low, high]}
  end

  defp revert_list(node) do
    pos = get_pos(node)
    p = list_prefix(node)
    s = case list_suffix(node) do
      :none ->
        revert_nil(set_pos(apply(__MODULE__, nil, []), pos))
      s1 ->
        s1
    end
    :lists.foldr(fn x, a ->
        {:cons, pos, x, a}
    end, s, p)
  end

  defp revert_list_comp(node) do
    pos = get_pos(node)
    template = list_comp_template(node)
    body = list_comp_body(node)
    {:lc, pos, template, body}
  end

  defp revert_map_expr(node) do
    pos = get_pos(node)
    argument = map_expr_argument(node)
    fields = map_expr_fields(node)
    case argument do
      :none ->
        {:map, pos, fields}
      _ ->
        {:map, pos, argument, fields}
    end
  end

  defp revert_map_field_assoc(node) do
    pos = get_pos(node)
    name = map_field_assoc_name(node)
    value = map_field_assoc_value(node)
    {:map_field_assoc, pos, name, value}
  end

  defp revert_map_field_exact(node) do
    pos = get_pos(node)
    name = map_field_exact_name(node)
    value = map_field_exact_value(node)
    {:map_field_exact, pos, name, value}
  end

  defp revert_map_type(node) do
    pos = get_pos(node)
    {:type, pos, :map, map_type_fields(node)}
  end

  defp revert_map_type_assoc(node) do
    pos = get_pos(node)
    name = map_type_assoc_name(node)
    value = map_type_assoc_value(node)
    {:type, pos, :map_type_assoc, [name, value]}
  end

  defp revert_map_type_exact(node) do
    pos = get_pos(node)
    name = map_type_exact_name(node)
    value = map_type_exact_value(node)
    {:type, pos, :map_type_exact, [name, value]}
  end

  defp revert_match_expr(node) do
    pos = get_pos(node)
    pattern = match_expr_pattern(node)
    body = match_expr_body(node)
    {:match, pos, pattern, body}
  end

  defp revert_module_name(a) do
    case type(a) do
      :atom ->
        {:ok, concrete(a)}
      _ ->
        :error
    end
  end

  defp revert_module_qualifier(node) do
    pos = get_pos(node)
    module = module_qualifier_argument(node)
    body = module_qualifier_body(node)
    {:remote, pos, module, body}
  end

  defp revert_named_fun_expr(node) do
    pos = get_pos(node)
    name = named_fun_expr_name(node)
    clauses = for c <- named_fun_expr_clauses(node) do
      revert_clause(c)
    end
    case type(name) do
      :variable ->
        {:named_fun, pos, variable_name(name), clauses}
      _ ->
        node
    end
  end

  defp revert_nil(node) do
    pos = get_pos(node)
    {nil, pos}
  end

  defp revert_parentheses(node), do: parentheses_body(node)

  defp revert_prefix_expr(node) do
    pos = get_pos(node)
    operator = prefix_expr_operator(node)
    argument = prefix_expr_argument(node)
    case type(operator) do
      :operator ->
        {:op, pos, operator_name(operator), argument}
      _ ->
        node
    end
  end

  defp revert_receive_expr(node) do
    pos = get_pos(node)
    clauses = for c <- receive_expr_clauses(node) do
      revert_clause(c)
    end
    timeout = receive_expr_timeout(node)
    action = receive_expr_action(node)
    case timeout do
      :none ->
        {:receive, pos, clauses}
      _ ->
        {:receive, pos, clauses, timeout, action}
    end
  end

  defp revert_record_access(node) do
    pos = get_pos(node)
    argument = record_access_argument(node)
    type = record_access_type(node)
    field = record_access_field(node)
    case type(type) do
      :atom ->
        {:record_field, pos, argument, concrete(type), field}
      _ ->
        node
    end
  end

  defp revert_record_expr(node) do
    pos = get_pos(node)
    argument = record_expr_argument(node)
    type = record_expr_type(node)
    fields = record_expr_fields(node)
    case type(type) do
      :atom ->
        t = concrete(type)
        fs = fold_record_fields(fields)
        case argument do
          :none ->
            {:record, pos, t, fs}
          _ ->
            {:record, pos, argument, t, fs}
        end
      _ ->
        node
    end
  end

  defp revert_record_index_expr(node) do
    pos = get_pos(node)
    type = record_index_expr_type(node)
    field = record_index_expr_field(node)
    case type(type) do
      :atom ->
        {:record_index, pos, concrete(type), field}
      _ ->
        node
    end
  end

  defp revert_record_type(node) do
    pos = get_pos(node)
    name = record_type_name(node)
    fields = record_type_fields(node)
    {:type, pos, :record, [name | fields]}
  end

  defp revert_record_type_field(node) do
    pos = get_pos(node)
    name = record_type_field_name(node)
    type = record_type_field_type(node)
    {:type, pos, :field_type, [name, type]}
  end

  defp revert_root(node) do
    case type(node) do
      :annotated_type ->
        revert_annotated_type(node)
      :application ->
        revert_application(node)
      :atom ->
        revert_atom(node)
      :attribute ->
        revert_attribute(node)
      :binary ->
        revert_binary(node)
      :binary_comp ->
        revert_binary_comp(node)
      :binary_field ->
        revert_binary_field(node)
      :binary_generator ->
        revert_binary_generator(node)
      :bitstring_type ->
        revert_bitstring_type(node)
      :block_expr ->
        revert_block_expr(node)
      :case_expr ->
        revert_case_expr(node)
      :catch_expr ->
        revert_catch_expr(node)
      :char ->
        revert_char(node)
      :clause ->
        revert_clause(node)
      :cond_expr ->
        revert_cond_expr(node)
      :constrained_function_type ->
        revert_constrained_function_type(node)
      :constraint ->
        revert_constraint(node)
      :eof_marker ->
        revert_eof_marker(node)
      :error_marker ->
        revert_error_marker(node)
      :float ->
        revert_float(node)
      :fun_expr ->
        revert_fun_expr(node)
      :fun_type ->
        revert_fun_type(node)
      :function ->
        revert_function(node)
      :function_type ->
        revert_function_type(node)
      :generator ->
        revert_generator(node)
      :if_expr ->
        revert_if_expr(node)
      :implicit_fun ->
        revert_implicit_fun(node)
      :infix_expr ->
        revert_infix_expr(node)
      :integer ->
        revert_integer(node)
      :integer_range_type ->
        revert_integer_range_type(node)
      :list ->
        revert_list(node)
      :list_comp ->
        revert_list_comp(node)
      :map_expr ->
        revert_map_expr(node)
      :map_field_assoc ->
        revert_map_field_assoc(node)
      :map_field_exact ->
        revert_map_field_exact(node)
      :map_type ->
        revert_map_type(node)
      :map_type_assoc ->
        revert_map_type_assoc(node)
      :map_type_exact ->
        revert_map_type_exact(node)
      :match_expr ->
        revert_match_expr(node)
      :module_qualifier ->
        revert_module_qualifier(node)
      :named_fun_expr ->
        revert_named_fun_expr(node)
      nil ->
        revert_nil(node)
      :parentheses ->
        revert_parentheses(node)
      :prefix_expr ->
        revert_prefix_expr(node)
      :receive_expr ->
        revert_receive_expr(node)
      :record_access ->
        revert_record_access(node)
      :record_expr ->
        revert_record_expr(node)
      :record_index_expr ->
        revert_record_index_expr(node)
      :record_type ->
        revert_record_type(node)
      :record_type_field ->
        revert_record_type_field(node)
      :type_application ->
        revert_type_application(node)
      :type_union ->
        revert_type_union(node)
      :string ->
        revert_string(node)
      :try_expr ->
        revert_try_expr(node)
      :tuple ->
        revert_tuple(node)
      :tuple_type ->
        revert_tuple_type(node)
      :underscore ->
        revert_underscore(node)
      :user_type_application ->
        revert_user_type_application(node)
      :variable ->
        revert_variable(node)
      :warning_marker ->
        revert_warning_marker(node)
      _ ->
        node
    end
  end

  defp revert_string(node) do
    pos = get_pos(node)
    {:string, pos, string_value(node)}
  end

  defp revert_try_clause(node), do: fold_try_clause(revert_clause(node))

  defp revert_try_expr(node) do
    pos = get_pos(node)
    body = try_expr_body(node)
    clauses = for c <- try_expr_clauses(node) do
      revert_clause(c)
    end
    handlers = for c <- try_expr_handlers(node) do
      revert_try_clause(c)
    end
    erlangVariableAfter = try_expr_after(node)
    {:try, pos, body, clauses, handlers, erlangVariableAfter}
  end

  defp revert_tuple(node) do
    pos = get_pos(node)
    {:tuple, pos, tuple_elements(node)}
  end

  defp revert_tuple_type(node) do
    pos = get_pos(node)
    {:type, pos, :tuple, tuple_type_elements(node)}
  end

  defp revert_type_application(node) do
    pos = get_pos(node)
    typeName = type_application_name(node)
    arguments = type_application_arguments(node)
    case type(typeName) do
      :module_qualifier ->
        module = module_qualifier_argument(typeName)
        name = module_qualifier_body(typeName)
        {:remote_type, pos, [module, name, arguments]}
      :atom ->
        {:type, pos, atom_value(typeName), arguments}
    end
  end

  defp revert_type_union(node) do
    pos = get_pos(node)
    {:type, pos, :union, type_union_types(node)}
  end

  defp revert_underscore(node) do
    pos = get_pos(node)
    {:var, pos, :_}
  end

  defp revert_user_type_application(node) do
    pos = get_pos(node)
    typeName = user_type_application_name(node)
    arguments = user_type_application_arguments(node)
    {:user_type, pos, atom_value(typeName), arguments}
  end

  defp revert_variable(node) do
    pos = get_pos(node)
    name = variable_name(node)
    {:var, pos, name}
  end

  defp revert_warning_marker(node), do: {:warning, warning_marker_info(node)}

  defp set_com(node, com) do
    case node do
      tree(attr: attr) ->
        tree(node, attr: attr(attr, com: com))
      wrapper(attr: attr) ->
        wrapper(node, attr: attr(attr, com: com))
      _ ->
        set_com(wrap(node), com)
    end
  end

  defp set_postcomments_1(attr(com: :none) = attr, cs), do: attr(attr, com: com(post: cs))

  defp set_postcomments_1(attr(com: com) = attr, cs), do: attr(attr, com: com(com, post: cs))

  defp set_precomments_1(attr(com: :none) = attr, cs), do: attr(attr, com: com(pre: cs))

  defp set_precomments_1(attr(com: com) = attr, cs), do: attr(attr, com: com(com, pre: cs))

  defp unfold_binary_field_type({type, size}, pos), do: set_pos(size_qualifier(atom(type), integer(size)), pos)

  defp unfold_binary_field_type(type, pos), do: set_pos(atom(type), pos)

  defp unfold_binary_field_types(ts, pos) do
    for t <- ts do
      unfold_binary_field_type(t, pos)
    end
  end

  defp unfold_function_names(ns, pos) do
    f = fn {atom, arity} ->
        n = arity_qualifier(atom(atom), integer(arity))
        set_pos(n, pos)
    end
    for n <- ns do
      f.(n)
    end
  end

  defp unfold_record_field({:typed_record_field, field, type}) do
    f = unfold_record_field_1(field)
    set_pos(typed_record_field(f, type), get_pos(f))
  end

  defp unfold_record_field(field), do: unfold_record_field_1(field)

  defp unfold_record_field_1({:record_field, pos, name}), do: set_pos(record_field(name), pos)

  defp unfold_record_field_1({:record_field, pos, name, value}), do: set_pos(record_field(name, value), pos)

  defp unfold_record_fields(fs) do
    for f <- fs do
      unfold_record_field(f)
    end
  end

  defp unfold_try_clause({:clause, pos, [{:tuple, _, [{:atom, _, :throw}, v, _]}], guard, body}), do: {:clause, pos, [v], guard, body}

  defp unfold_try_clause({:clause, pos, [{:tuple, _, [c, v, _]}], guard, body}), do: {:clause, pos, [class_qualifier(c, v)], guard, body}

  defp unfold_try_clauses(cs) do
    for c <- cs do
      unfold_try_clause(c)
    end
  end

  defp unfold_variable_names(vs, pos) do
    for v <- vs do
      set_pos(variable(v), pos)
    end
  end

  @spec unwrap(syntaxTree()) :: (tree() | erl_parse())
  defp unwrap(wrapper(tree: node)), do: node

  defp unwrap(node), do: node

  @spec wrap(erl_parse()) :: wrapper()
  defp wrap(node), do: wrapper(type: type(node), attr: attr(pos: get_pos(node)), tree: node)
end
