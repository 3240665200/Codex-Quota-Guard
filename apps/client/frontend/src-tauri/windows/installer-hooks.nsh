; The perMachine installer already requested legitimate administrator elevation.
; These fixed modes exit before Tauri, the startup guard, Java, and meter setup.
!macro NSIS_HOOK_POSTINSTALL
  DetailPrint "默认启用 Windows 登录时启动；可在客户端设置中关闭。"
  ClearErrors
  ExecWait '"$INSTDIR\${MAINBINARYNAME}.exe" --autostart-install' $0
  ${If} ${Errors}
  ${OrIf} $0 != 0
    MessageBox MB_OK|MB_ICONEXCLAMATION "开机自启动设置未完成。请将客户端安装到受保护的 Program Files 目录，并使用当前 Windows 登录用户的管理员权限安装。可稍后在客户端设置中重试。"
  ${EndIf}
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ; The updater uses /UPDATE and must preserve the owned disabled task as well.
  ${If} $UpdateMode != 1
    ; Complete the user's close/cancel decision before removing registration.
    !insertmacro CheckIfAppIsRunning "${MAINBINARYNAME}.exe" "${PRODUCTNAME}"
    ClearErrors
    ExecWait '"$INSTDIR\${MAINBINARYNAME}.exe" --autostart-uninstall' $0
    ${If} ${Errors}
    ${OrIf} $0 != 0
      MessageBox MB_OK|MB_ICONSTOP "无法清理开机自启动任务。请使用安装时的 Windows 用户，以管理员权限重试卸载。"
      SetErrorLevel 2
      Abort
    ${EndIf}
  ${EndIf}
!macroend
