%% Compiled with OTP 29.0.6: `erlc +debug_info reserved_variable.erl`.
-module(reserved_variable).

-export([reserved/4]).

reserved(True, False, Nil, Or) -> {True, False, Nil, Or}.
