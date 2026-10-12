; The currentUser installer accepts the user's selected install directory.
!define CQG_COMPATIBILITY_SCRIPT "${__FILEDIR__}\installer-compatibility.ps1"
; Registration happens only after a normal client startup gains permission.
!macro NSIS_HOOK_POSTINSTALL
  DetailPrint "首次以当前 Windows 用户的管理员权限启动后默认登记普通启动项；可在客户端设置或任务管理器中关闭。"
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
  ${EndIf}
!macroend
