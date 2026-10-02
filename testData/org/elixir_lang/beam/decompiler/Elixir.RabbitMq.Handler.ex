# Source code recreated from a .beam file by IntelliJ Elixir
defmodule RabbitMq.Handler do

  # Functions

  def __info__(arg1) do
    # body not decompiled
  end

  def handle(%{"action" => action, "from" => to} = msg_params) do
    case(call_controller_action(%{"send_file" => &RabbitMq.Controllers.SendFileToBankController.do_send/1}[action], msg_params)) do
      {:ok, result} ->
        {:ok, result, answer_to(to, msg_params)}
      {:error, reason} ->
        {:error, reason, answer_to(to, msg_params)}
    end
  end

  def handle(args) do
    (
      case(Logger.__should_log__(:info, RabbitMq.Handler)) do
        nil ->
          :ok
        level ->
          Logger.__do_log__(level, <<"RabbitMq.Handler: Неправильный формат сообщения, "::binary(), Kernel.inspect(args)::binary()>>, %{application: :bp_bank_proxy, file: 'lib/rabbit_mq/handler.ex', mfa: {RabbitMq.Handler, :handle, 1}, line: 21})
      end
      {:error, :wrong_msg_format}
    )
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  # Private Functions

  defp answer_to(to, msg_params) do
    case(msg_params["params"]["callback_required"] == true) do
      false ->
        nil
      true ->
        to
    end
  end

  defp call_controller_action(action_function, action_params) do
    case(:erlang.is_function(action_function)) do
      true ->
        action_function.(action_params)
      false ->
        {:error, :controller_action_not_found}
    end
  end
end
