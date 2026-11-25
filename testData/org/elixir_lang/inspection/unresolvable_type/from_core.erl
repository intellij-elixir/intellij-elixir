%% Compiled from Core Erlang with debug_info: erlc +to_core from_core.erl, then erlc +debug_info +deterministic -o ebin from_core.core
-module(from_core).
-export_type([t/0]).
-type t() :: integer().
