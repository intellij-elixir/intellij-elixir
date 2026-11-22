%% Compiled with OTP 29.0.6: `erlc +debug_info erlang_signature.erl`.
-module(erlang_signature).

-export([
    spec_names/2,
    spec_over_clauses/2,
    annotated/1,
    rendered/2,
    anonymous_spec/1,
    untyped_spec/1,
    bounded/1,
    two_clause_spec/1,
    remote/1,
    spec_after/1,
    args/2,
    trimmed/2,
    underscore/2,
    pattern/2,
    multi_clause/2,
    doc_line/2,
    calls_private/1,
    callback_named/1,
    spec_then_callback/1
]).

-spec spec_then_callback(Own) -> Own.
-callback callback_named(Args :: term()) -> ok.
-callback spec_then_callback(Callback :: term()) -> ok.

-spec spec_names(Key, Value) -> {Key, Value}.
spec_names(x, {Y}) -> {x, Y}.

-spec spec_over_clauses(Key, List) -> {Key, List}.
spec_over_clauses(k, []) -> ok;
spec_over_clauses(_, [_ | _]) -> ok.

-spec annotated(Q :: atom()) -> ok.
annotated(_) -> ok.

-spec rendered(In, Q1) -> {In, Q1}.
rendered({X}, Y) -> {X, Y}.

-spec anonymous_spec(_) -> ok.
anonymous_spec(_Value) -> ok.

-spec untyped_spec(atom()) -> atom().
untyped_spec(Value) -> Value.

-spec bounded(Tuple) -> term() when Tuple :: tuple().
bounded({X}) -> X.

-spec two_clause_spec(Atom :: atom()) -> atom(); (Int :: integer()) -> integer().
two_clause_spec(X) -> X.

-spec erlang_signature:remote(Tuple) -> Tuple.
remote({X}) -> X.

spec_after({X}) -> X.
-spec spec_after(Tuple) -> Tuple.

args(First, Second) -> {First, Second}.

trimmed(_Acc, Value) -> Value.

underscore(_, Value) -> Value.

pattern({X}, Y) -> {X, Y}.

multi_clause(First, a) -> First;
multi_clause(_, Second) -> Second.

-spec callback_named({atom(), atom()}) -> {atom(), atom()}.
callback_named({A, B}) -> {A, B}.

spec_then_callback({X}) -> X.

-doc "doc_line(Left, Right)\n\nNames its parameters on its first line.".
doc_line(A, B) -> {A, B}.

calls_private(X) -> {private_spec({X}), private_trimmed(X)}.

-spec private_spec(Name) -> Name.
private_spec({N}) -> N.

private_trimmed(_Acc) -> ok.
