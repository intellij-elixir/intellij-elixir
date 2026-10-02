# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :dbg_wx_trace_win do

  # Private Types

  @typep event :: (wxActivate() | wxAuiManager() | wxAuiNotebook() | wxCalendar() | wxChildFocus() | wxClipboardText() | wxClose() | wxColourPicker() | wxCommand() | wxContextMenu() | wxDate() | wxDisplayChanged() | wxDropFiles() | wxErase() | wxFileDirPicker() | wxFocus() | wxFontPicker() | wxGrid() | wxHelp() | wxHtmlLink() | wxIconize() | wxIdle() | wxInitDialog() | wxJoystick() | wxKey() | wxList() | wxMaximize() | wxMenu() | wxMouse() | wxMouseCaptureChanged() | wxMouseCaptureLost() | wxMove() | wxNavigationKey() | wxNotebook() | wxPaint() | wxPaletteChanged() | wxQueryNewPalette() | wxSash() | wxScroll() | wxScrollWin() | wxSetCursor() | wxShow() | wxSize() | wxSpin() | wxSplitter() | wxStyledText() | wxSysColourChanged() | wxTaskBarIcon() | wxTree() | wxUpdateUI() | wxWindowCreate() | wxWindowDestroy())

  @typep wx :: wx()

  @typep wxActivate :: wxActivate()

  @typep wxActivateEventType :: (:activate | :activate_app | :hibernate)

  @typep wxAuiManager :: wxAuiManager()

  @typep wxAuiManagerEventType :: (:aui_pane_button | :aui_pane_close | :aui_pane_maximize | :aui_pane_restore | :aui_pane_activated | :aui_render | :aui_find_manager)

  @typep wxAuiNotebook :: wxAuiNotebook()

  @typep wxAuiNotebookEventType :: (:command_auinotebook_page_close | :command_auinotebook_page_changed | :command_auinotebook_page_changing | :command_auinotebook_button | :command_auinotebook_begin_drag | :command_auinotebook_end_drag | :command_auinotebook_drag_motion | :command_auinotebook_allow_dnd | :command_auinotebook_tab_middle_down | :command_auinotebook_tab_middle_up | :command_auinotebook_tab_right_down | :command_auinotebook_tab_right_up | :command_auinotebook_page_closed | :command_auinotebook_drag_done | :command_auinotebook_bg_dclick)

  @typep wxCalendar :: wxCalendar()

  @typep wxCalendarEventType :: (:calendar_sel_changed | :calendar_day_changed | :calendar_month_changed | :calendar_year_changed | :calendar_doubleclicked | :calendar_weekday_clicked)

  @typep wxChildFocus :: wxChildFocus()

  @typep wxChildFocusEventType :: :child_focus

  @typep wxClipboardText :: wxClipboardText()

  @typep wxClipboardTextEventType :: (:command_text_copy | :command_text_cut | :command_text_paste)

  @typep wxClose :: wxClose()

  @typep wxCloseEventType :: (:close_window | :end_session | :query_end_session)

  @typep wxColourPicker :: wxColourPicker()

  @typep wxColourPickerEventType :: :command_colourpicker_changed

  @typep wxCommand :: wxCommand()

  @typep wxCommandEventType :: (:command_button_clicked | :command_checkbox_clicked | :command_choice_selected | :command_listbox_selected | :command_listbox_doubleclicked | :command_text_updated | :command_text_enter | :command_menu_selected | :command_slider_updated | :command_radiobox_selected | :command_radiobutton_selected | :command_scrollbar_updated | :command_vlbox_selected | :command_combobox_selected | :command_tool_rclicked | :command_tool_enter | :command_checklistbox_toggled | :command_togglebutton_clicked | :command_left_click | :command_left_dclick | :command_right_click | :command_set_focus | :command_kill_focus | :command_enter)

  @typep wxContextMenu :: wxContextMenu()

  @typep wxContextMenuEventType :: :context_menu

  @typep wxDate :: wxDate()

  @typep wxDateEventType :: :date_changed

  @typep wxDisplayChanged :: wxDisplayChanged()

  @typep wxDisplayChangedEventType :: :display_changed

  @typep wxDropFiles :: wxDropFiles()

  @typep wxDropFilesEventType :: :drop_files

  @typep wxErase :: wxErase()

  @typep wxEraseEventType :: :erase_background

  @typep wxEventType :: (wxActivateEventType() | wxAuiManagerEventType() | wxAuiNotebookEventType() | wxCalendarEventType() | wxChildFocusEventType() | wxClipboardTextEventType() | wxCloseEventType() | wxColourPickerEventType() | wxCommandEventType() | wxContextMenuEventType() | wxDateEventType() | wxDisplayChangedEventType() | wxDropFilesEventType() | wxEraseEventType() | wxFileDirPickerEventType() | wxFocusEventType() | wxFontPickerEventType() | wxGridEventType() | wxHelpEventType() | wxHtmlLinkEventType() | wxIconizeEventType() | wxIdleEventType() | wxInitDialogEventType() | wxJoystickEventType() | wxKeyEventType() | wxListEventType() | wxMaximizeEventType() | wxMenuEventType() | wxMouseCaptureChangedEventType() | wxMouseCaptureLostEventType() | wxMouseEventType() | wxMoveEventType() | wxNavigationKeyEventType() | wxNotebookEventType() | wxPaintEventType() | wxPaletteChangedEventType() | wxQueryNewPaletteEventType() | wxSashEventType() | wxScrollEventType() | wxScrollWinEventType() | wxSetCursorEventType() | wxShowEventType() | wxSizeEventType() | wxSpinEventType() | wxSplitterEventType() | wxStyledTextEventType() | wxSysColourChangedEventType() | wxTaskBarIconEventType() | wxTreeEventType() | wxUpdateUIEventType() | wxWindowCreateEventType() | wxWindowDestroyEventType())

  @typep wxFileDirPicker :: wxFileDirPicker()

  @typep wxFileDirPickerEventType :: (:command_filepicker_changed | :command_dirpicker_changed)

  @typep wxFocus :: wxFocus()

  @typep wxFocusEventType :: (:set_focus | :kill_focus)

  @typep wxFontPicker :: wxFontPicker()

  @typep wxFontPickerEventType :: :command_fontpicker_changed

  @typep wxGrid :: wxGrid()

  @typep wxGridEventType :: (:grid_cell_left_click | :grid_cell_right_click | :grid_cell_left_dclick | :grid_cell_right_dclick | :grid_label_left_click | :grid_label_right_click | :grid_label_left_dclick | :grid_label_right_dclick | :grid_row_size | :grid_col_size | :grid_range_select | :grid_cell_change | :grid_select_cell | :grid_editor_shown | :grid_editor_hidden | :grid_editor_created | :grid_cell_begin_drag)

  @typep wxHelp :: wxHelp()

  @typep wxHelpEventType :: (:help | :detailed_help)

  @typep wxHtmlLink :: wxHtmlLink()

  @typep wxHtmlLinkEventType :: :command_html_link_clicked

  @typep wxIconize :: wxIconize()

  @typep wxIconizeEventType :: :iconize

  @typep wxIdle :: wxIdle()

  @typep wxIdleEventType :: :idle

  @typep wxInitDialog :: wxInitDialog()

  @typep wxInitDialogEventType :: :init_dialog

  @typep wxJoystick :: wxJoystick()

  @typep wxJoystickEventType :: (:joy_button_down | :joy_button_up | :joy_move | :joy_zmove)

  @typep wxKey :: wxKey()

  @typep wxKeyEventType :: (:char | :char_hook | :key_down | :key_up)

  @typep wxList :: wxList()

  @typep wxListEventType :: (:command_list_begin_drag | :command_list_begin_rdrag | :command_list_begin_label_edit | :command_list_end_label_edit | :command_list_delete_item | :command_list_delete_all_items | :command_list_key_down | :command_list_insert_item | :command_list_col_click | :command_list_col_right_click | :command_list_col_begin_drag | :command_list_col_dragging | :command_list_col_end_drag | :command_list_item_selected | :command_list_item_deselected | :command_list_item_right_click | :command_list_item_middle_click | :command_list_item_activated | :command_list_item_focused | :command_list_cache_hint)

  @typep wxMaximize :: wxMaximize()

  @typep wxMaximizeEventType :: :maximize

  @typep wxMenu :: wxMenu()

  @typep wxMenuEventType :: (:menu_open | :menu_close | :menu_highlight)

  @typep wxMouse :: wxMouse()

  @typep wxMouseCaptureChanged :: wxMouseCaptureChanged()

  @typep wxMouseCaptureChangedEventType :: :mouse_capture_changed

  @typep wxMouseCaptureLost :: wxMouseCaptureLost()

  @typep wxMouseCaptureLostEventType :: :mouse_capture_lost

  @typep wxMouseEventType :: (:left_down | :left_up | :middle_down | :middle_up | :right_down | :right_up | :motion | :enter_window | :leave_window | :left_dclick | :middle_dclick | :right_dclick | :mousewheel)

  @typep wxMove :: wxMove()

  @typep wxMoveEventType :: :move

  @typep wxNavigationKey :: wxNavigationKey()

  @typep wxNavigationKeyEventType :: :navigation_key

  @typep wxNotebook :: wxNotebook()

  @typep wxNotebookEventType :: (:command_notebook_page_changed | :command_notebook_page_changing)

  @typep wxPaint :: wxPaint()

  @typep wxPaintEventType :: :paint

  @typep wxPaletteChanged :: wxPaletteChanged()

  @typep wxPaletteChangedEventType :: :palette_changed

  @typep wxQueryNewPalette :: wxQueryNewPalette()

  @typep wxQueryNewPaletteEventType :: :query_new_palette

  @typep wxSash :: wxSash()

  @typep wxSashEventType :: :sash_dragged

  @typep wxScroll :: wxScroll()

  @typep wxScrollEventType :: (:scroll_top | :scroll_bottom | :scroll_lineup | :scroll_linedown | :scroll_pageup | :scroll_pagedown | :scroll_thumbtrack | :scroll_thumbrelease | :scroll_changed)

  @typep wxScrollWin :: wxScrollWin()

  @typep wxScrollWinEventType :: (:scrollwin_top | :scrollwin_bottom | :scrollwin_lineup | :scrollwin_linedown | :scrollwin_pageup | :scrollwin_pagedown | :scrollwin_thumbtrack | :scrollwin_thumbrelease)

  @typep wxSetCursor :: wxSetCursor()

  @typep wxSetCursorEventType :: :set_cursor

  @typep wxShow :: wxShow()

  @typep wxShowEventType :: :show

  @typep wxSize :: wxSize()

  @typep wxSizeEventType :: :size

  @typep wxSpin :: wxSpin()

  @typep wxSpinEventType :: (:command_spinctrl_updated | :spin_up | :spin_down | :spin)

  @typep wxSplitter :: wxSplitter()

  @typep wxSplitterEventType :: (:command_splitter_sash_pos_changed | :command_splitter_sash_pos_changing | :command_splitter_doubleclicked | :command_splitter_unsplit)

  @typep wxStyledText :: wxStyledText()

  @typep wxStyledTextEventType :: (:stc_change | :stc_styleneeded | :stc_charadded | :stc_savepointreached | :stc_savepointleft | :stc_romodifyattempt | :stc_key | :stc_doubleclick | :stc_updateui | :stc_modified | :stc_macrorecord | :stc_marginclick | :stc_needshown | :stc_painted | :stc_userlistselection | :stc_uridropped | :stc_dwellstart | :stc_dwellend | :stc_start_drag | :stc_drag_over | :stc_do_drop | :stc_zoom | :stc_hotspot_click | :stc_hotspot_dclick | :stc_calltip_click | :stc_autocomp_selection)

  @typep wxSysColourChanged :: wxSysColourChanged()

  @typep wxSysColourChangedEventType :: :sys_colour_changed

  @typep wxTaskBarIcon :: wxTaskBarIcon()

  @typep wxTaskBarIconEventType :: (:taskbar_move | :taskbar_left_down | :taskbar_left_up | :taskbar_right_down | :taskbar_right_up | :taskbar_left_dclick | :taskbar_right_dclick)

  @typep wxTree :: wxTree()

  @typep wxTreeEventType :: (:command_tree_begin_drag | :command_tree_begin_rdrag | :command_tree_begin_label_edit | :command_tree_end_label_edit | :command_tree_delete_item | :command_tree_get_info | :command_tree_set_info | :command_tree_item_expanded | :command_tree_item_expanding | :command_tree_item_collapsed | :command_tree_item_collapsing | :command_tree_sel_changed | :command_tree_sel_changing | :command_tree_key_down | :command_tree_item_activated | :command_tree_item_right_click | :command_tree_item_middle_click | :command_tree_end_drag | :command_tree_state_image_click | :command_tree_item_gettooltip | :command_tree_item_menu)

  @typep wxUpdateUI :: wxUpdateUI()

  @typep wxUpdateUIEventType :: :update_ui

  @typep wxWindowCreate :: wxWindowCreate()

  @typep wxWindowCreateEventType :: :create

  @typep wxWindowDestroy :: wxWindowDestroy()

  @typep wxWindowDestroyEventType :: :destroy

  # Functions

  def add_break(winInfo, menu, {{mod, line}, [status | _Options]} = break) do
    case winInfo(winInfo, :editor) do
      {mod, editor} ->
        :dbg_wx_code.add_break_to_code(editor, line, status)
      _ ->
        :ok
    end
    add_break_to_menu(winInfo, menu, break)
  end

  def clear_breaks(winInfo), do: clear_breaks(winInfo, :all)

  def clear_breaks(winInfo, mod) do
    remove = cond do
      mod === :all ->
        winInfo(winInfo, :breaks)
      true ->
        :lists.filter(fn breakInfo(point: {mod2, _L}) ->
            cond do
              mod2 === mod ->
                true
              true ->
                false
            end
        end, winInfo(winInfo, :breaks))
    end
    :lists.foreach(fn breakInfo(point: point) ->
        delete_break(winInfo, point)
    end, remove)
    remain = winInfo(winInfo, :breaks) -- remove
    winInfo(winInfo, breaks: remain)
  end

  def configure(wi0 = winInfo(window: win, m_szr: {panel, sizer}), windows) do
    :wx.batch(fn () ->
        wi = enable_windows(wi0, windows)
        _ = show_windows(wi)
        :wxSizer.layout(sizer)
        :wxWindow.setSizer(panel, sizer)
        _ = :wxSizer.fit(sizer, win)
        :wxSizer.setSizeHints(sizer, win)
        wi
    end)
  end

  def create_win(parent, title, windows, menus) do
    erlangVariableDo = fn () ->
        win = :wxFrame.new(parent, -1, :dbg_wx_win.to_string(title), [{:size, {700, 650}}])
        panel = :wxPanel.new(win, [{:size, {700, 650}}])
        menuBar = :wxMenuBar.new()
        :dbg_wx_win.create_menus(menuBar, menus, win, 1)
        :wxFrame.setMenuBar(win, menuBar)
        sizer = :wxBoxSizer.new(8)
        code = code_area(panel)
        _ = :wxSizer.add(sizer, sub(code, :win), [{:proportion, 1}, {:border, 2}, {:flag, 8192 ||| 128}])
        :wxSizer.setVirtualSizeHints(sizer, sub(code, :win))
        expandWithBorder = [{:border, 3}, {:flag, 8192 ||| 64 ||| 128 ||| 32 ||| 16}]
        search = search_area(panel)
        _ = :wxSizer.add(sizer, sub(search, :win), expandWithBorder)
        bs = button_area(panel)
        _ = :wxSizer.add(sizer, sub(bs, :win), expandWithBorder)
        infoArea = :wxBoxSizer.new(4)
        :wxSizer.setMinSize(infoArea, {100, 200})
        eval = eval_area(panel)
        _ = :wxSizer.add(infoArea, sub(eval, :win), [{:proportion, 1}, {:flag, 8192}])
        bind = bind_area(panel)
        _ = :wxSizer.add(infoArea, sub(bind, :win), [{:proportion, 1}, {:border, 2}, {:flag, 8192 ||| 16}])
        _ = :wxSizer.add(sizer, infoArea, expandWithBorder)
        trace = trace_area(panel)
        _ = :wxSizer.add(sizer, sub(trace, :win), expandWithBorder)
        sB = :wxFrame.createStatusBar(win, [])
        :wxFrame.connect(win, :sash_dragged, [{:id, 425}, {:lastId, 427}])
        :wxFrame.connect(win, :close_window, [{:skip, true}])
        :wxFrame.connect(win, :size, [{:skip, true}])
        :wxWindow.connect(win, :key_up, [{:skip, true}])
        :wxWindow.setFocus(sub(code, :out))
        wi0 = winInfo(window: win, m_szr: {panel, sizer}, e_szr: {true, infoArea}, code: code, sb: sB, sg: search, bs: bs, eval: eval, trace: trace, bind: bind, editor: {:"$top", sub(code, :out)}, editors: [{:"$top", sub(code, :out)}])
        wi = show_windows(enable_windows(wi0, windows))
        :wxWindow.setSizer(panel, sizer)
        _ = :wxSizer.fit(sizer, win)
        :wxSizer.setSizeHints(sizer, win)
        iconFile = :dbg_wx_win.find_icon('erlang_bug.png')
        icon = :wxIcon.new(iconFile, [{:type, 2 + 13}])
        :wxFrame.setIcon(win, icon)
        :wxIcon.destroy(icon)
        :wxFrame.show(win)
        put(:window, win)
        put(:strings, [:str_on])
        wi
    end
    try do
      :wx.batch(erlangVariableDo)
    catch
      {e, r, _} ->
        :io.format('Crashed ~p ~p', [e, r])
        :erlang.error(e)
    end
  end

  def delete_break(winInfo, {mod, line} = point) do
    case winInfo(winInfo, :editor) do
      {mod, editor} ->
        :dbg_wx_code.del_break_from_code(editor, line)
      _ ->
        :ignore
    end
    delete_break_from_menu(winInfo, point)
  end

  def display(winInfo(window: win, sb: sb), arg) do
    str = case arg do
      :idle ->
        'State: uninterpreted'
      {:exit, {mod, line}, reason} ->
        :wxWindow.raise(win)
        :dbg_wx_win.to_string('State: EXITED [~w.erl/~w], Reason:~w', [mod, line, reason])
      {:exit, :null, reason} ->
        :wxWindow.raise(win)
        :dbg_wx_win.to_string('State: EXITED [uninterpreted], Reason:~w', [reason])
      {level, :null, _Line} when is_integer(level) ->
        :dbg_wx_win.to_string('*** Call level #~w (in non-interpreted code)', [level])
      {level, mod, line} when is_integer(level) ->
        :dbg_wx_win.to_string('*** Call level #~w [~w.erl/~w]', [level, mod, line])
      {status, mod, line} ->
        what = case status do
          :wait ->
            :receive
          _ ->
            status
        end
        :dbg_wx_win.to_string('State: ~w [~w.erl/~w]', [what, mod, line])
      {:running, mod} ->
        :dbg_wx_win.to_string('State: running [~w.erl]', [mod])
      {:text, text} ->
        :dbg_wx_win.to_string(text)
    end
    :wxStatusBar.setStatusText(sb, str)
  end

  def enable(menuItems, bool) do
    :wx.foreach(fn menuItem ->
        mI = get(menuItem)
        :wxMenuItem.enable(mI, [{:enable, bool}])
        case is_button(menuItem) do
          {true, buttonId} ->
            parent = get(:window)
            butt = :wxWindow.findWindowById(buttonId, [{:parent, parent}])
            case :wx.is_null(butt) do
              true ->
                :ignore
              false ->
                :wxButton.enable(butt, [{:enable, bool}])
            end
          _ ->
            :ignore
        end
    end, menuItems)
  end

  def eval_output(winInfo(eval: sub(out: log)), text, _Face) do
    :wxTextCtrl.appendText(log, :dbg_wx_win.to_string(text))
    :ok
  end

  def get_window(winInfo), do: winInfo(winInfo, :window)

  def handle_event(_Ev = wx(event: wxClose()), _WinInfo), do: :stopped

  def handle_event(wx(event: wxSize(size: size)), wi0) do
    wi = winInfo(wi0, size: size)
    resize(wi)
    {:win, wi}
  end

  def handle_event(wx(event: wxSash(dragStatus: 1)), _Wi), do: :ignore

  def handle_event(wx(id: 425, event: wxSash(dragRect: {_X, _Y, _W, h})), wi) do
    winInfo(code: code, m_szr: {_, sizer}, e_szr: {enable, infoSzr}, trace: trace) = wi
    case ^enable or sub(trace, :enable) do
      false ->
        :ignore
      true ->
        {_, cMH} = :wxWindow.getMinSize(sub(code, :win))
        case cMH > h do
          true ->
            :wxSashWindow.setMinSize(sub(code, :win), {500, h})
          _ ->
            :ignore
        end
        {_, cH} = :wxWindow.getSize(sub(code, :win))
        change = cH - h
        changeH = fn item ->
            {itemW, itemH} = :wxSizerItem.getMinSize(item)
            :wxSizerItem.setInitSize(item, itemW, :erlang.max(itemH + change, -1))
        end
        cond do
          enable ->
            {iW, iH} = :wxSizer.getMinSize(infoSzr)
            for child <- :wxSizer.getChildren(infoSzr) do
              changeH.(child)
            end
            :wxSizer.setMinSize(infoSzr, {iW, iH + change})
            :ok
          sub(trace, :enable) ->
            {tW, tH} = :wxWindow.getMinSize(sub(trace, :win))
            :wxWindow.setMinSize(sub(trace, :win), {tW, tH + change})
            :ok
        end
        :wxSizer.layout(sizer)
        :ignore
    end
  end

  def handle_event(wx(id: 426, event: wxSash(dragRect: {_X, _Y, w, _H})), wi) do
    winInfo(m_szr: {_, sizer}, e_szr: {enable, infoSzr}, eval: sub(enable: ^enable, win: evalSzr)) = wi
    case ^enable do
      false ->
        :ignore
      true ->
        [eval, bind] = :wxSizer.getChildren(infoSzr)
        {tot, _} = :wxSizer.getSize(infoSzr)
        evalWidth = tot - w
        change = fn szr, width ->
            {_EW, eH} = :wxSizerItem.getMinSize(szr)
            :wxSizerItem.setInitSize(szr, width, eH)
        end
        change.(eval, evalWidth)
        for kid <- :wxSizer.getChildren(evalSzr) do
          change.(kid, evalWidth)
        end
        change.(bind, w)
        :wxSizerItem.setProportion(eval, 0)
        :wxSizer.layout(infoSzr)
        :wxSizer.layout(sizer)
        resize(wi)
        :ignore
    end
  end

  def handle_event(wx(id: 427, event: wxSash(dragRect: {_X, _Y, _W, h})), wi) do
    winInfo(code: code, m_szr: {_, sizer}, e_szr: {enable, infoSzr}, trace: trace) = wi
    {tW, tH} = :wxWindow.getSize(sub(trace, :win))
    change = ^tH - ^h
    case ^enable do
      false ->
        {_, cH} = :wxWindow.getSize(sub(code, :win))
        {_, cMH} = :wxWindow.getMinSize(sub(code, :win))
        case cMH > cH + change do
          true ->
            :wxSashWindow.setMinSize(sub(code, :win), {500, cH + change})
          _ ->
            :ignore
        end
        :wxWindow.setMinSize(sub(trace, :win), {tW, h})
        :wxSizer.layout(sizer)
        :ignore
      true ->
        changeH = fn item ->
            {itemW, itemH} = :wxSizerItem.getMinSize(item)
            :wxSizerItem.setInitSize(item, itemW, :erlang.max(itemH + change, -1))
        end
        {iW, iH} = :wxSizer.getMinSize(infoSzr)
        for child <- :wxSizer.getChildren(infoSzr) do
          changeH.(child)
        end
        wanted = iH + change
        :wxSizer.setMinSize(infoSzr, {iW, wanted})
        {_, rH} = :wxSizer.getMinSize(infoSzr)
        case rH > wanted do
          true ->
            {_, cH} = :wxWindow.getSize(sub(code, :win))
            {_, cMH} = :wxWindow.getMinSize(sub(code, :win))
            cC = cH - rH - wanted
            case cMH > cC do
              true when cC > 50 ->
                :wxWindow.setMinSize(sub(trace, :win), {tW, h})
                :wxSashWindow.setMinSize(sub(code, :win), {500, cC})
              _ when cC < 50 ->
                :ignore
              _ ->
                :wxWindow.setMinSize(sub(trace, :win), {tW, h})
            end
            :ok
          false ->
            :wxWindow.setMinSize(sub(trace, :win), {tW, h})
        end
        :wxSizer.layout(sizer)
        :ignore
    end
  end

  def handle_event(_Ev = wx(event: wxKey(keyCode: key, controlDown: true)), _WinInfo) do
    cond do
      key != 315 and key != 317 and key != 13 ->
        try do
          {:shortcut, list_to_atom([key + ?a - ?A])}
        catch
          {_, _, _} ->
            :ignore
        end
      true ->
        :ignore
    end
  end

  def handle_event(wx(userData: {:dbg_ui_winman, win}, event: wxCommand(type: :command_menu_selected)), _WinInfo) do
    :dbg_wx_winman.raise(win)
    :ignore
  end

  def handle_event(wx(userData: {:break, point, :status}, event: wxCommand(type: :command_menu_selected)), winInfo) do
    {:value, breakInfo} = :lists.keysearch(point, breakInfo(:point), winInfo(winInfo, :breaks))
    breakInfo(break: break(smi: smi)) = breakInfo
    case :wxMenuItem.getText(smi) do
      'Enable' ->
        {:break, point, {:status, :active}}
      'Disable' ->
        {:break, point, {:status, :inactive}}
    end
  end

  def handle_event(wx(userData: data, event: _Cmd = wxCommand(type: :command_menu_selected)), _WinInfo), do: data

  def handle_event(wx(event: wxStyledText(type: :stc_doubleclick)), winInfo = winInfo(editor: {mod, ed})) do
    line = :wxStyledTextCtrl.getCurrentLine(ed)
    point = {mod, line + 1}
    case :lists.keymember(point, breakInfo(:point), winInfo(winInfo, :breaks)) do
      true ->
        {:break, point, :delete}
      false ->
        {:break, point, :add}
    end
  end

  def handle_event(wx(id: 414, event: wxCommand(cmdString: str)), winInfo) do
    try do
      line = list_to_integer(str)
    {:gotoline, line}
    catch
      {_, _, _} ->
        display(winInfo, {:text, 'Not a line number'})
        :ignore
    end
  end

  def handle_event(wx(id: 413, event: wxFocus()), wi), do: {:win, winInfo(wi, find: :undefined)}

  def handle_event(wx(id: 413, event: wxCommand(type: :command_text_enter, cmdString: str)), wi = winInfo(code: code, find: find, sg: sub(in: sa(radio: {nextO, _, caseO})))) when find !== :undefined do
    dir = :erlang.xor(:wxRadioButton.getValue(nextO), :wx_misc.getKeyState(306))
    case = :wxCheckBox.getValue(caseO)
    pos = cond do
      find(find, :found) and dir ->
        :wxStyledTextCtrl.getAnchor(sub(code, :out))
      find(find, :found) ->
        :wxStyledTextCtrl.getCurrentPos(sub(code, :out))
      dir ->
        0
      true ->
        :wxStyledTextCtrl.getLength(sub(code, :out))
    end
    :dbg_wx_code.goto_pos(sub(code, :out), pos)
    case :dbg_wx_code.find(sub(code, :out), str, case, dir) do
      true ->
        display(wi, {:text, ""})
        {:win, winInfo(wi, find: find(find, found: true))}
      false ->
        display(wi, {:text, 'Not found (Hit Enter to wrap search)'})
        {:win, winInfo(wi, find: find(find, found: false))}
    end
  end

  def handle_event(wx(id: 413, event: wxCommand(cmdString: "")), wi = winInfo(code: code)) do
    pos = :dbg_wx_code.current_pos(sub(code, :out))
    :dbg_wx_code.goto_pos(sub(code, :out), pos)
    {:win, winInfo(wi, find: :undefined)}
  end

  def handle_event(wx(id: 413, event: wxCommand(cmdString: str)), wi = winInfo(code: code, find: find, sg: sub(in: sa(radio: {nextO, _, caseO})))) do
    dir = :wxRadioButton.getValue(nextO)
    case = :wxCheckBox.getValue(caseO)
    cont = case find do
      :undefined ->
        pos = :dbg_wx_code.current_pos(sub(code, :out))
        find(start: pos, strlen: length(str))
      find(strlen: old) when old < length(str) ->
        find(find, strlen: length(str))
      _ ->
        :dbg_wx_code.goto_pos(sub(code, :out), find(find, :start))
        find(find, strlen: length(str))
    end
    case :dbg_wx_code.find(sub(code, :out), str, case, dir) do
      true ->
        display(wi, {:text, ""})
        {:win, winInfo(wi, find: find(cont, found: true))}
      false ->
        display(wi, {:text, 'Not found (Hit Enter to wrap search)'})
        {:win, winInfo(wi, find: find(cont, found: false))}
    end
  end

  def handle_event(wx(id: iD, event: wxCommand(type: :command_button_clicked)), _Wi) do
    {button, _} = :lists.keyfind(iD, 2, buttons())
    button
  end

  def handle_event(wx(id: 410, event: wxCommand(type: :command_text_enter)), wi = winInfo(eval: sub(in: tC))) do
    case :wxTextCtrl.getValue(tC) do
      [10] ->
        eval_output(wi, '\n', :normal)
        :ignore
      cmd ->
        eval_output(wi, [?>, cmd, 10], :normal)
        :wxTextCtrl.setValue(tC, "")
        {:user_command, cmd}
    end
  end

  def handle_event(wx(event: wxList(type: :command_list_item_selected, itemIndex: row)), wi) do
    bs = get(:bindings)
    {var, val} = :lists.nth(row + 1, bs)
    str = case get(:strings) do
      [] ->
        :io_lib.format('< ~s = ~ltp~n', [var, val])
      [:str_on] ->
        :io_lib.format('< ~s = ~tp~n', [var, val])
    end
    eval_output(wi, str, :bold)
    :ignore
  end

  def handle_event(wx(event: wxList(type: :command_list_item_activated, itemIndex: row)), _Wi) do
    bs = get(:bindings)
    binding = :lists.nth(row + 1, bs)
    {:edit, binding}
  end

  def handle_event(_GSEvent, _WinInfo), do: :ignore

  def helpwin(type, winInfo = winInfo(sg: sg = sub(in: sa))) do
    wi = case sub(sg, :enable) do
      false ->
        configure(winInfo(winInfo, sg: sub(sg, enable: true)))
      true ->
        winInfo
    end
    case type do
      :gotoline ->
        :wxWindow.setFocus(sa(sa, :goto))
      :search ->
        :wxWindow.setFocus(sa(sa, :search))
    end
    wi
  end

  def init() do
    _ = :dbg_wx_win.init()
    :ok
  end

  def is_enabled(menuItem) do
    mI = get(menuItem)
    :wxMenuItem.isEnabled(mI)
  end

  def is_shown(_WinInfo, _Mod), do: false

  def mark_line(winInfo = winInfo(editor: {_, ed}), line, _How) do
    :dbg_wx_code.mark_line(ed, winInfo(winInfo, :marked_line), line)
    winInfo(winInfo, marked_line: line)
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(p0) do
    # body not decompiled
  end

  def remove_code(winInfo, _Mod), do: winInfo

  def select(menuItem, bool) do
    mI = get(menuItem)
    :wxMenuItem.check(mI, [{:check, bool}])
  end

  def select_line(winInfo, line) do
    {_Mod, ed} = winInfo(winInfo, :editor)
    size = :dbg_wx_code.get_no_lines(ed)
    cond do
      line === 0 ->
        :dbg_wx_code.goto_line(ed, 1)
        winInfo(winInfo, selected_line: 0)
      line < size ->
        :dbg_wx_code.goto_line(ed, line)
        winInfo(winInfo, selected_line: line)
      true ->
        winInfo
    end
  end

  def selected_line(winInfo(editor: {_, ed})), do: :wxStyledTextCtrl.getCurrentLine(ed) + 1

  def show_code(winInfo = winInfo(editor: {_, ed}), mod, contents) do
    :dbg_wx_code.load_code(ed, contents)
    :lists.foreach(fn breakInfo ->
        case breakInfo(breakInfo, :point) do
          {mod2, line} when mod2 === mod ->
            status = breakInfo(breakInfo, :status)
            :dbg_wx_code.add_break_to_code(ed, line, status)
          _Point ->
            :ignore
        end
    end, winInfo(winInfo, :breaks))
    winInfo(winInfo, editor: {mod, ed}, find: :undefined)
  end

  def show_no_code(winInfo = winInfo(editor: {_, ed})) do
    :dbg_wx_code.unload_code(ed)
    winInfo(winInfo, editor: {:"$top", ed})
  end

  def stop(winInfo(window: win)) do
    try do
      :wxFrame.destroy(win)
    catch
      error -> error
    end
    :ok
  end

  def trace_output(winInfo(trace: sub(out: log)), text) do
    :wxTextCtrl.appendText(log, :dbg_wx_win.to_string(text))
    :ok
  end

  def unmark_line(winInfo), do: mark_line(winInfo, 0, false)

  def update_bindings(winInfo(bind: sub(out: bA)), bs) do
    :wxListCtrl.deleteAllItems(bA)
    :wx.foldl(fn {var, val}, row ->
        :wxListCtrl.insertItem(bA, row, "")
        :wxListCtrl.setItem(bA, row, 0, :dbg_wx_win.to_string(var))
        format = case get(:strings) do
          [] ->
            '~0ltP'
          [:str_on] ->
            '~0tP'
        end
        :wxListCtrl.setItem(bA, row, 1, :dbg_wx_win.to_string(format, [val, 20]))
        row + 1
    end, 0, bs)
    put(:bindings, bs)
    :ok
  end

  def update_break(winInfo, {{mod, line}, [status | _Options]} = break) do
    case winInfo(winInfo, :editor) do
      {mod, editor} ->
        :dbg_wx_code.add_break_to_code(editor, line, status)
      _ ->
        :ok
    end
    update_break_in_menu(winInfo, break)
  end

  def update_strings(strings) do
    _ = put(:strings, strings)
    :ok
  end

  # Private Functions

  defp unquote(:"-button_area/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-clear_breaks/2-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-clear_breaks/2-fun-1-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-configure/1-fun-0-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-configure/2-fun-0-")(p0, p1, p2, p3, p4) do
    # body not decompiled
  end

  defp unquote(:"-create_win/4-fun-0-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-enable/2-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-enable_windows/2-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-handle_event/2-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-handle_event/2-fun-2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-handle_event/2-fun-4-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-handle_event/2-lc$^1/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-handle_event/2-lc$^3/1-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-handle_event/2-lc$^5/1-2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-show_code/3-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-update_bindings/2-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp add_break_to_menu(winInfo, menu, {point, [status | _Options] = options}) do
    break = :dbg_wx_win.add_break(winInfo(winInfo, :window), menu, point)
    :dbg_wx_win.update_break(break, options)
    breakInfo = breakInfo(point: point, status: status, break: break)
    winInfo(winInfo, breaks: [breakInfo | winInfo(winInfo, :breaks)])
  end

  defp bind_area(parent) do
    style = {:style, 64 ||| 128 ||| 4194304}
    win = :wxSashWindow.new(parent, [{:id, 426}, style])
    :wxSashWindow.setSashVisible(win, 3, true)
    bA = :wxListCtrl.new(win, [{:style, 32 ||| 8192}])
    lI = :wxListItem.new()
    :wxListItem.setText(lI, 'Name')
    :wxListItem.setAlign(lI, 0)
    :wxListCtrl.insertColumn(bA, 0, lI)
    :wxListItem.setText(lI, 'Value')
    :wxListCtrl.insertColumn(bA, 1, lI)
    :wxListItem.destroy(lI)
    :wxListCtrl.setColumnWidth(bA, 0, 100)
    :wxListCtrl.setColumnWidth(bA, 1, 150)
    :wxListCtrl.connect(bA, :command_list_item_selected)
    :wxListCtrl.connect(bA, :command_list_item_activated)
    sub(name: :"Bindings Area", win: win, out: bA)
  end

  defp button_area(parent) do
    sz = :wxBoxSizer.new(4)
    :wx.foreach(fn {name, button} ->
        b = :wxButton.new(parent, button, [{:label, :dbg_wx_win.to_string(name)}])
        id = :wxWindow.getId(b)
        _ = :wxSizer.add(sz, b, [])
        :wxButton.connect(b, :command_button_clicked, [{:id, id}])
    end, buttons())
    sub(name: :"Button Area", win: sz)
  end

  defp buttons(), do: [{:Step, 401}, {:Next, 402}, {:Continue, 403}, {:Finish, 404}, {:Where, 405}, {:Up, 406}, {:Down, 407}]

  defp code_area(win) do
    codeWin = :wxSashWindow.new(win, [{:id, 425}, {:size, {700, 400}}, {:style, 64 ||| 128}])
    code = :dbg_wx_code.code_area(codeWin)
    :wxSashWindow.setSashVisible(codeWin, 2, true)
    :wxWindow.setMinSize(codeWin, {600, 400})
    sub(name: :"Code Area", enable: true, win: codeWin, out: code)
  end

  defp configure(wi = winInfo(window: win, m_szr: {panel, sizer})) do
    :wx.batch(fn () ->
        _ = show_windows(wi)
        :wxSizer.layout(sizer)
        :wxWindow.setSizer(panel, sizer)
        _ = :wxSizer.fit(sizer, win)
        :wxSizer.setSizeHints(sizer, win)
        wi
    end)
  end

  defp delete_break_from_menu(winInfo, point) do
    {:value, breakInfo} = :lists.keysearch(point, breakInfo(:point), winInfo(winInfo, :breaks))
    :dbg_wx_win.delete_break(breakInfo(breakInfo, :break))
    winInfo(winInfo, breaks: :lists.keydelete(point, breakInfo(:point), winInfo(winInfo, :breaks)))
  end

  defp enable_windows(wi = winInfo(e_szr: {_, infoArea}, bs: bs0, sg: sG0, eval: eval0, trace: trace0, bind: bind0), windows) do
    subs = for window <- [sG0, bs0, eval0, trace0, bind0] do
      sub(window, enable: :lists.member(sub(window, :name), windows))
    end
    [sG, bs, eval, trace, bind] = subs
    eSzr = sub(eval, :enable) or sub(bind, :enable)
    winInfo(wi, e_szr: {eSzr, infoArea}, sg: sG, bs: bs, eval: eval, trace: trace, bind: bind)
  end

  defp eval_area(parent) do
    vSz = :wxBoxSizer.new(8)
    hSz = :wxBoxSizer.new(4)
    _ = :wxSizer.add(hSz, :wxStaticText.new(parent, -1, 'Evaluator:'), [{:flag, 2048}])
    tC = :wxTextCtrl.new(parent, 410, [{:style, 1024}])
    _ = :wxSizer.add(hSz, tC, [{:proportion, 1}, {:flag, 8192}])
    _ = :wxSizer.add(vSz, hSz, [{:flag, 8192}])
    tL = :wxTextCtrl.new(parent, 411, [{:style, 1073741824 ||| 32 ||| 16}])
    _ = :wxSizer.add(vSz, tL, [{:proportion, 5}, {:flag, 8192}])
    :wxTextCtrl.connect(tC, :command_text_enter)
    sub(name: :"Evaluator Area", win: vSz, in: tC, out: tL)
  end

  defp is_button(name) do
    case :lists.keyfind(name, 1, buttons()) do
      {name, button} ->
        {true, button}
      false ->
        false
    end
  end

  defp resize(winInfo(bind: bind)) do
    cond do
      sub(bind, :enable) === false ->
        :ok
      sub(bind, :enable) ->
        {eW, _} = :wxWindow.getClientSize(sub(bind, :out))
        b0W = :wxListCtrl.getColumnWidth(sub(bind, :out), 0)
        :wxListCtrl.setColumnWidth(sub(bind, :out), 1, eW - b0W)
        :ok
    end
  end

  defp search_area(parent) do
    hSz = :wxBoxSizer.new(4)
    _ = :wxSizer.add(hSz, :wxStaticText.new(parent, -1, 'Find:'), [{:flag, 2048}])
    tC1 = :wxTextCtrl.new(parent, 413, [{:style, 1024}])
    _ = :wxSizer.add(hSz, tC1, [{:proportion, 3}, {:flag, 8192}])
    nbtn = :wxRadioButton.new(parent, -1, 'Next')
    :wxRadioButton.setValue(nbtn, true)
    _ = :wxSizer.add(hSz, nbtn, [{:flag, 2048}])
    pbtn = :wxRadioButton.new(parent, -1, 'Previous')
    _ = :wxSizer.add(hSz, pbtn, [{:flag, 2048}])
    cbtn = :wxCheckBox.new(parent, -1, 'Match Case')
    _ = :wxSizer.add(hSz, cbtn, [{:flag, 2048}])
    _ = :wxSizer.add(hSz, 15, 15, [{:proportion, 1}, {:flag, 8192}])
    _ = :wxSizer.add(hSz, :wxStaticText.new(parent, -1, 'Goto Line:'), [{:flag, 2048}])
    tC2 = :wxTextCtrl.new(parent, 414, [{:style, 1024}])
    _ = :wxSizer.add(hSz, tC2, [{:proportion, 0}, {:flag, 8192}])
    :wxTextCtrl.connect(tC1, :command_text_updated)
    :wxTextCtrl.connect(tC1, :command_text_enter)
    :wxTextCtrl.connect(tC1, :kill_focus)
    :wxTextCtrl.connect(tC2, :command_text_enter)
    :wxWindow.connect(parent, :command_button_clicked)
    sub(name: :"Search Area", win: hSz, in: sa(search: tC1, goto: tC2, radio: {nbtn, pbtn, cbtn}))
  end

  defp show_windows(wi = winInfo(m_szr: {_, sizer}, e_szr: {_, infoArea}, bs: bs, sg: sG, eval: eval, trace: trace, bind: bind)) do
    case sub(sG, :enable) do
      false ->
        :wxSizer.hide(sizer, sub(sG, :win))
      _ ->
        :wxSizer.show(sizer, sub(sG, :win))
    end
    case sub(bs, :enable) do
      false ->
        :wxSizer.hide(sizer, sub(bs, :win))
      _ ->
        :wxSizer.show(sizer, sub(bs, :win))
    end
    cond do
      not sub(eval, :enable) and not sub(bind, :enable) ->
        :wxSizer.hide(sizer, infoArea)
      not sub(eval, :enable) ->
        :wxSizer.show(sizer, infoArea)
        :wxSizer.hide(infoArea, sub(eval, :win))
        :wxSizer.show(infoArea, sub(bind, :win))
      not sub(bind, :enable) ->
        [evalSI | _] = :wxSizer.getChildren(infoArea)
        :wxSizerItem.setProportion(evalSI, 1)
        :wxSizer.show(sizer, infoArea)
        :wxSizer.hide(infoArea, sub(bind, :win))
        :wxSizer.show(infoArea, sub(eval, :win))
        true
      true ->
        :wxSizer.show(sizer, infoArea)
        :wxSizer.show(infoArea, sub(eval, :win))
        :wxSizer.show(infoArea, sub(bind, :win))
    end
    case sub(trace, :enable) do
      false ->
        :wxSizer.hide(sizer, sub(trace, :win))
      _ ->
        :wxSizer.show(sizer, sub(trace, :win))
    end
    wi
  end

  defp trace_area(parent) do
    style = {:style, 64 ||| 128 ||| 4194304}
    win = :wxSashWindow.new(parent, [{:id, 427}, {:size, {700, 100}}, style])
    :wxSashWindow.setSashVisible(win, 0, true)
    :wxWindow.setMinSize(win, {500, 100})
    tC = :wxTextCtrl.new(win, -1, [{:style, 32 ||| 16}])
    sub(name: :"Trace Area", win: win, out: tC)
  end

  defp update_break_in_menu(winInfo, {point, [status | _Options] = options}) do
    {:value, breakInfo} = :lists.keysearch(point, breakInfo(:point), winInfo(winInfo, :breaks))
    :dbg_wx_win.update_break(breakInfo(breakInfo, :break), options)
    breakInfo2 = breakInfo(breakInfo, status: status)
    winInfo(winInfo, breaks: :lists.keyreplace(point, breakInfo(:point), winInfo(winInfo, :breaks), breakInfo2))
  end
end
