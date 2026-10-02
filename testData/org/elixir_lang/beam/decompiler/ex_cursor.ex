# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :ex_cursor do

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

  def code_change(_, _, state), do: {:stop, :ignore, state}

  def handle_call(:shutdown, _From, state = state(parent: panel)) do
    :wxPanel.destroy(panel)
    {:stop, :normal, :ok, state}
  end

  def handle_call(msg, _From, state) do
    :demo.format(state(state, :config), 'Got Call ~p\n', [msg])
    {:reply, {:error, :nyi}, state}
  end

  def handle_cast(msg, state) do
    :io.format('Got cast ~p~n', [msg])
    {:noreply, state}
  end

  def handle_event(wx(event: wxCommand(type: :command_radiobox_selected, cmdString: string)), state = state()) do
    :wxWindow.refresh(state(state, :parent))
    cursorId = :proplists.get_value(string, cursors())
    cursor = :wxCursor.new(cursorId)
    :wxWindow.setCursor(state(state, :win), cursor)
    {:noreply, state(state, [])}
  end

  def handle_event(wx(obj: toggleButton, event: wxCommand(type: :command_togglebutton_clicked, commandInt: int)), state = state()) do
    case int do
      1 ->
        :wx_misc.beginBusyCursor()
        :wxToggleButton.setLabel(toggleButton, 'End busy cursor')
      0 ->
        :wx_misc.endBusyCursor()
        :wxToggleButton.setLabel(toggleButton, 'Begin busy cursor')
    end
    {:noreply, state}
  end

  def handle_event(ev = wx(), state = state()) do
    :demo.format(state(state, :config), 'Got Event ~p\n', [ev])
    {:noreply, state}
  end

  def handle_info(msg, state) do
    :demo.format(state(state, :config), 'Got Info ~p\n', [msg])
    {:noreply, state}
  end

  def init(config) do
    :wx.batch(fn () ->
        do_init(config)
    end)
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  def start(config), do: :wx_object.start_link(:ex_cursor, config, [])

  def terminate(_Reason, _State) do
    case :wx_misc.isBusy() do
      true ->
        :wx_misc.endBusyCursor()
      false ->
        :ignore
    end
  end

  # Private Functions

  defp unquote(:"-do_init/1-fun-1-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-do_init/1-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-init/1-fun-0-")(arg1) do
    # body not decompiled
  end

  defp cursors(), do: [{'Arrow', 1}, {'Right arrow', 2}, {'Blank', 26}, {'Bullseye', 3}, {'Char', 4}, {'Cross', 5}, {'Hand', 6}, {'I-beam', 7}, {'Left button', 8}, {'Magnifier', 9}, {'Middle button', 10}, {'No entry', 11}, {'Paint brush', 12}, {'Pencil', 13}, {'Point left', 14}, {'Point right', 15}, {'Question arrow', 16}, {'Right button', 17}, {'Size NE-SW', 18}, {'Size N-S', 19}, {'Size NW-SE', 20}, {'Size W-E', 21}, {'Sizing', 22}, {'Spraycan', 23}, {'Wait', 24}, {'Watch', 25}, {'Arrow wait', :wxe_util.get_const(:wxCURSOR_ARROWWAIT)}]

  defp do_init(config) do
    parent = :proplists.get_value(:parent, config)
    panel = :wxScrolledWindow.new(parent, [])
    mainSizer = :wxBoxSizer.new(8)
    miscSizer = :wxStaticBoxSizer.new(8, panel, [{:label, 'Misc'}])
    staticBoxSizer = :wxStaticBoxSizer.new(4, panel, [{:label, 'Test the cursor here'}])
    cursorSizer = :wxBoxSizer.new(4)
    cursorLabels = for {cursor, _} <- cursors() do
      cursor
    end
    stockCursors = :wxRadioBox.new(panel, -1, 'Stock cursors', {-1, -1}, {-1, -1}, cursorLabels, [{:majorDim, 2}, {:style, 4}])
    fun = fn item, int ->
        cursorId = :proplists.get_value(item, cursors())
        cursor = :wxCursor.new(cursorId)
        case :wxCursor.ok(cursor) do
          true ->
            :ok
          false ->
            :wxRadioBox.enable(stockCursors, int, [{:enable, false}])
        end
        int + 1
    end
    :wx.foldl(fun, 0, cursorLabels)
    win = :wxWindow.new(panel, -1, [{:size, {300, 300}}])
    toggleButton = :wxToggleButton.new(panel, -1, 'Begin busy cursor', [])
    :wxSizer.add(cursorSizer, stockCursors)
    :wxSizer.add(staticBoxSizer, win)
    :wxSizer.add(cursorSizer, staticBoxSizer)
    :wxSizer.add(miscSizer, toggleButton)
    :wxSizer.add(mainSizer, cursorSizer)
    :wxSizer.add(mainSizer, miscSizer)
    :wxToggleButton.connect(toggleButton, :command_togglebutton_clicked, [])
    :wxRadioBox.connect(stockCursors, :command_radiobox_selected, [])
    :wxScrolledWindow.setScrollRate(panel, 5, 5)
    :wxPanel.setSizer(panel, mainSizer)
    {panel, state(parent: panel, config: config, win: win)}
  end
end
