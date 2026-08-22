// webkit_shim.h
//
// Redirects every WebKitGTK / JavaScriptCore call site to the runtime-resolved
// function pointers in g_wk (see webkit_loader.h). Include this AFTER the real
// <webkit2/webkit2.h> / <JavaScriptCore/JavaScript.h> / <gtk/gtk.h> headers and
// after webkit_loader.h, inside the GTK-only regions of webview.h and
// webview_embed.cpp.
//
// Each redirect expands `foo(args)` to `(::g_wk.fn_foo)(args)`. The member token
// `foo` is not re-expanded (the macro being expanded is not applied to its own
// name), so this is self-reference-safe. Because no call site references the
// real symbol any more, libwebview.so ends up with no undefined WebKit/JSC
// symbols and therefore no DT_NEEDED on them — it loads under any binding mode.
//
// webkit_loader.cpp must NOT include this header: it assigns the real g_wk
// members and dlsym()s symbols by string name.
#ifndef WEBLITE_WEBKIT_SHIM_H
#define WEBLITE_WEBKIT_SHIM_H

#if defined(WEBVIEW_GTK) || defined(__linux__)

#define webkit_download_cancel (::g_wk.fn_webkit_download_cancel)
#define webkit_download_get_received_data_length (::g_wk.fn_webkit_download_get_received_data_length)
#define webkit_download_get_request (::g_wk.fn_webkit_download_get_request)
#define webkit_download_get_response (::g_wk.fn_webkit_download_get_response)
#define webkit_download_get_web_view (::g_wk.fn_webkit_download_get_web_view)
#define webkit_download_set_allow_overwrite (::g_wk.fn_webkit_download_set_allow_overwrite)
#define webkit_download_set_destination (::g_wk.fn_webkit_download_set_destination)
#define webkit_get_major_version (::g_wk.fn_webkit_get_major_version)
#define webkit_get_minor_version (::g_wk.fn_webkit_get_minor_version)
#define webkit_uri_response_get_content_length (::g_wk.fn_webkit_uri_response_get_content_length)
#define webkit_uri_response_get_mime_type (::g_wk.fn_webkit_uri_response_get_mime_type)
#define webkit_uri_response_get_uri (::g_wk.fn_webkit_uri_response_get_uri)
#define webkit_web_view_get_context (::g_wk.fn_webkit_web_view_get_context)
#define webkit_file_chooser_request_cancel (::g_wk.fn_webkit_file_chooser_request_cancel)
#define webkit_file_chooser_request_get_mime_types (::g_wk.fn_webkit_file_chooser_request_get_mime_types)
#define webkit_file_chooser_request_get_select_multiple (::g_wk.fn_webkit_file_chooser_request_get_select_multiple)
#define webkit_file_chooser_request_select_files (::g_wk.fn_webkit_file_chooser_request_select_files)
#define webkit_navigation_action_get_request (::g_wk.fn_webkit_navigation_action_get_request)
#define webkit_navigation_action_is_user_gesture (::g_wk.fn_webkit_navigation_action_is_user_gesture)
#define webkit_script_dialog_confirm_set_confirmed (::g_wk.fn_webkit_script_dialog_confirm_set_confirmed)
#define webkit_script_dialog_get_dialog_type (::g_wk.fn_webkit_script_dialog_get_dialog_type)
#define webkit_script_dialog_get_message (::g_wk.fn_webkit_script_dialog_get_message)
#define webkit_script_dialog_prompt_get_default_text (::g_wk.fn_webkit_script_dialog_prompt_get_default_text)
#define webkit_script_dialog_prompt_set_text (::g_wk.fn_webkit_script_dialog_prompt_set_text)
#define webkit_settings_get_enable_developer_extras (::g_wk.fn_webkit_settings_get_enable_developer_extras)
#define webkit_settings_set_enable_developer_extras (::g_wk.fn_webkit_settings_set_enable_developer_extras)
#define webkit_settings_set_enable_write_console_messages_to_stdout (::g_wk.fn_webkit_settings_set_enable_write_console_messages_to_stdout)
#define webkit_settings_set_hardware_acceleration_policy (::g_wk.fn_webkit_settings_set_hardware_acceleration_policy)
#define webkit_uri_request_get_uri (::g_wk.fn_webkit_uri_request_get_uri)
#define webkit_user_content_manager_add_script (::g_wk.fn_webkit_user_content_manager_add_script)
#define webkit_user_content_manager_register_script_message_handler (::g_wk.fn_webkit_user_content_manager_register_script_message_handler)
#define webkit_user_script_new (::g_wk.fn_webkit_user_script_new)
#define webkit_web_inspector_show (::g_wk.fn_webkit_web_inspector_show)
#define webkit_web_view_execute_editing_command (::g_wk.fn_webkit_web_view_execute_editing_command)
#define webkit_web_view_get_inspector (::g_wk.fn_webkit_web_view_get_inspector)
#define webkit_web_view_get_settings (::g_wk.fn_webkit_web_view_get_settings)
#define webkit_web_view_get_uri (::g_wk.fn_webkit_web_view_get_uri)
#define webkit_web_view_get_user_content_manager (::g_wk.fn_webkit_web_view_get_user_content_manager)
#define webkit_web_view_get_window_properties (::g_wk.fn_webkit_web_view_get_window_properties)
#define webkit_web_view_load_uri (::g_wk.fn_webkit_web_view_load_uri)
#define webkit_web_view_new (::g_wk.fn_webkit_web_view_new)
#define webkit_web_view_new_with_related_view (::g_wk.fn_webkit_web_view_new_with_related_view)
#define webkit_web_view_run_javascript (::g_wk.fn_webkit_web_view_run_javascript)
#define webkit_web_view_set_background_color (::g_wk.fn_webkit_web_view_set_background_color)
#define webkit_web_view_set_input_method_context (::g_wk.fn_webkit_web_view_set_input_method_context)
#define webkit_window_properties_get_geometry (::g_wk.fn_webkit_window_properties_get_geometry)

// JS-result readers — gate identically to webkit_loader.h / webview.h.
#if WEBKIT_MAJOR_VERSION >= 2 && WEBKIT_MINOR_VERSION >= 22
#define webkit_javascript_result_get_js_value (::g_wk.fn_webkit_javascript_result_get_js_value)
#define jsc_value_to_string (::g_wk.fn_jsc_value_to_string)
#else
#define webkit_javascript_result_get_global_context (::g_wk.fn_webkit_javascript_result_get_global_context)
#define webkit_javascript_result_get_value (::g_wk.fn_webkit_javascript_result_get_value)
#define JSValueToStringCopy (::g_wk.fn_JSValueToStringCopy)
#define JSStringRelease (::g_wk.fn_JSStringRelease)
#define JSStringGetMaximumUTF8CStringSize (::g_wk.fn_JSStringGetMaximumUTF8CStringSize)
#define JSStringGetUTF8CString (::g_wk.fn_JSStringGetUTF8CString)
#endif

// The only GObject cast macro used in the code is WEBKIT_WEB_VIEW, which
// expands to a G_TYPE_CHECK_INSTANCE_CAST that references webkit_web_view_get_type
// — an undefined-symbol reference we do NOT want. Replace it with a plain
// pointer cast (the code always passes WebKitWebView-compatible pointers).
// No WEBKIT_IS_* checks exist, and all other WEBKIT_* tokens are compile-time
// enum/version constants, so nothing else needs neutralizing.
#ifdef WEBKIT_WEB_VIEW
#undef WEBKIT_WEB_VIEW
#endif
#define WEBKIT_WEB_VIEW(obj) ((WebKitWebView *)(void *)(obj))

#endif  // WEBVIEW_GTK || __linux__
#endif  // WEBLITE_WEBKIT_SHIM_H
