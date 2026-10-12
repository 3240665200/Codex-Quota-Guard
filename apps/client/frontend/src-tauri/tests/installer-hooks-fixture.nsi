; Compile-only fixture: the produced installer is never executed.
Unicode true
RequestExecutionLevel admin
!include LogicLib.nsh
!define MAINBINARYNAME "codex-quota-guard"
!define PRODUCTNAME "Codex Quota Guard"
!macro CheckIfAppIsRunning BINARY PRODUCT
!macroend
!include "${HOOKFILE}"
Var UpdateMode
Name "Autostart hook syntax test"
OutFile "${OUTPUTFILE}"
Section Install
  !insertmacro NSIS_HOOK_POSTINSTALL
  WriteUninstaller "$INSTDIR\uninstall.exe"
SectionEnd
Section Uninstall
  !insertmacro NSIS_HOOK_PREUNINSTALL
SectionEnd
