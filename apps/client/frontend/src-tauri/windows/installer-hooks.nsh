; The currentUser installer accepts the user's selected install directory.
!define CQG_COMPATIBILITY_SCRIPT "${__FILEDIR__}\installer-compatibility.ps1"
; The installer must finish installing the independent Windows service before
; it registers a successful desktop installation. The helper preserves the
; launching Windows profile across its one UAC elevation.
!macro NSIS_HOOK_POSTINSTALL
  DetailPrint "正在安装必需的统计服务，并配置 Windows 自动启动和故障恢复……"
  ClearErrors
  ExecWait '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "$INSTDIR\meter\installer-meter.ps1" -Operation Install' $0
  ${If} ${Errors}
  ${OrIf} $0 != 0
    MessageBox MB_OK|MB_ICONSTOP "统计服务未能安装或启动，安装已停止。请以当前 Windows 用户批准管理员权限后重新运行安装程序。已有统计记录会保留。"
    SetErrorLevel 2
    Abort
  ${EndIf}
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ; The updater uses /UPDATE and must preserve disabled startup entries as well.
  ${If} $UpdateMode != 1
    ; Complete the user's close/cancel decision before removing registration.
    !insertmacro CheckIfAppIsRunning "${MAINBINARYNAME}.exe" "${PRODUCTNAME}"
    ClearErrors
    ExecWait '"$INSTDIR\${MAINBINARYNAME}.exe" --autostart-uninstall' $0
    ${If} ${Errors}
    ${OrIf} $0 != 0
      MessageBox MB_OK|MB_ICONSTOP "无法清理本软件的启动项或旧版计划任务。请使用安装时的 Windows 用户重试卸载；旧版管理员任务需以该用户的管理员权限清理。"
      SetErrorLevel 2
      Abort
    ${EndIf}
    ClearErrors
    ExecWait '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "$INSTDIR\meter\installer-meter.ps1" -Operation Uninstall' $0
    ${If} ${Errors}
    ${OrIf} $0 != 0
      MessageBox MB_OK|MB_ICONSTOP "无法移除统计服务，卸载已停止。请使用安装时的 Windows 用户批准管理员权限后重试。"
      SetErrorLevel 2
      Abort
    ${EndIf}
  ${EndIf}
!macroend
