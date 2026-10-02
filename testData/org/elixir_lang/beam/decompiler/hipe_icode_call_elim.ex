# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :hipe_icode_call_elim do

  # Private Types

  @typep cfg :: cfg()

  @typep cfg_data :: {:dict.dict(), [cfg_lbl()], non_neg_integer()}

  @typep cfg_info :: cfg_info()

  @typep cfg_lbl :: non_neg_integer()

  @typep ct_alignment :: (4 | 8 | 16 | 32)

  @typep hipe_constlbl :: non_neg_integer()

  @typep hipe_consttab :: {:dict.dict(), [hipe_constlbl()], hipe_constlbl()}

  @typep icode :: icode()

  @typep icode_argument :: (icode_const() | icode_variable())

  @typep icode_call_type :: (:local | :primop | :remote)

  @typep icode_comment_text :: (atom() | charlist())

  @typep icode_exit_class :: (:error | :exit | :rethrow | :throw)

  @typep icode_funcall :: (mfa() | icode_primop())

  @typep icode_fvar :: icode_variable(kind :: :fvar)

  @typep icode_if_op :: (:> | :< | :>= | :"=<" | :"=:=" | :"=/=" | :== | :"/=" | :fixnum_eq | :fixnum_neq | :fixnum_lt | :fixnum_le | :fixnum_ge | :fixnum_gt | :op_exact_eqeq_2 | :suspend_msg_timeout)

  @typep icode_info :: [{:arg_types, [:erl_types.erl_type()]}]

  @typep icode_instr :: (icode_begin_handler() | icode_begin_try() | icode_call() | icode_comment() | icode_end_try() | icode_enter() | icode_fail() | icode_goto() | icode_if() | icode_label() | icode_move() | icode_phi() | icode_return() | icode_switch_tuple_arity() | icode_switch_val() | icode_type())

  @typep icode_instrs :: [icode_instr()]

  @typep icode_lbl :: non_neg_integer()

  @typep icode_primop :: (atom() | tuple())

  @typep icode_reg :: icode_variable(kind :: (:reg | :reg_gcsafe))

  @typep icode_switch_case :: {icode_const(), icode_lbl()}

  @typep icode_term_arg :: (icode_var() | icode_const())

  @typep icode_type_test :: (:atom | :bignum | :binary | :bitstr | :boolean | :cons | :fixnum | :float | :function | :function2 | :integer | :list | :map | nil | :number | :pid | :port | :reference | :tuple | {:atom, atom()} | {:integer, integer()} | {:record, atom(), non_neg_integer()} | {:tuple, non_neg_integer()})

  @typep icode_var :: icode_variable(kind :: :var)

  @typep simple_const :: (atom() | [] | integer() | float())

  @typep structured_const :: ([] | tuple())

  @typep variable_annotation :: {atom(), any(), (any() -> charlist())}

  # Functions

  @spec cfg(cfg()) :: cfg()
  def cfg(icodeSSA) do
    :lists.foldl(fn lbl, cFG1 ->
        bB1 = :hipe_icode_cfg.bb(cFG1, lbl)
        code1 = :hipe_bb.code(bB1)
        code2 = :lists.map(&elim_insn/1, code1)
        bB2 = :hipe_bb.code_update(bB1, code2)
        :hipe_icode_cfg.bb_add(cFG1, lbl, bB2)
    end, icodeSSA, :hipe_icode_cfg.labels(icodeSSA))
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  # Private Functions

  defp unquote(:"-cfg/1-fun-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-elim_insn/1-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-fun.elim_insn/1-")(arg1) do
    # body not decompiled
  end

  @spec can_be_eliminated(mfa(), [:erl_types.erl_type()]) :: boolean()
  defp can_be_eliminated({:maps, :is_key, 2}, [_K, m]), do: :erl_types.t_is_map(m)

  defp can_be_eliminated(_, _), do: false

  @spec elim_insn(icode_instr()) :: icode_instr()
  defp elim_insn(insn = icode_call(fun: {_, _, _} = mFA, args: args, type: :remote, dstlist: [dst = icode_variable(annotation: {:type_anno, retType, _})], continuation: [], fail_label: [])) do
    opaques = :universe
    case :erl_types.t_is_singleton(retType, opaques) do
      true ->
        argTypes = for arg <- args do
          case arg do
          icode_variable(annotation: {:type_anno, type, _}) ->
            type
          icode_const() ->
            :erl_types.t_from_term(:hipe_icode.const_value(arg))
        end
        end
        case can_be_eliminated(mFA, argTypes) do
          true ->
            const = :hipe_icode.mk_const(:erl_types.t_singleton_to_term(retType, opaques))
            icode_move(dst: dst, src: const)
          false ->
            insn
        end
      false ->
        insn
    end
  end

  defp elim_insn(insn), do: insn
end
