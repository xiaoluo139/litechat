; 轻聊助手 · Windows 安装包脚本（NSIS 3）
;
; 这个文件是 UTF-8（无 BOM）的编辑副本；build_windows.ps1 会在调用 makensis
; 之前把它重新写成 UTF-8 with BOM，因为 NSIS 在非 Unicode 源码下按系统代码页
; 读取，中文会变乱码。
;
; 安装到 %LOCALAPPDATA%\LiteChat（当前用户），不需要管理员权限。

Unicode true

!include "MUI2.nsh"

!define APP_NAME "轻聊助手"
!define APP_EXE "LiteChat.exe"
!define APP_ID "LiteChat"
!define APP_VERSION "1.25"
!define PUBLISHER "LiteChat"

Name "${APP_NAME}"
BrandingText "${APP_NAME} ${APP_VERSION}"
OutFile "LiteChat-Setup-Windows-v${APP_VERSION}.exe"
InstallDir "$LOCALAPPDATA\${APP_ID}"
RequestExecutionLevel user
SetCompressor /SOLID lzma

!define MUI_ICON "..\assets\litechat.ico"
!define MUI_UNICON "..\assets\litechat.ico"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "$INSTDIR\${APP_EXE}"
!define MUI_FINISHPAGE_RUN_TEXT "立即启动 ${APP_NAME}"
!define MUI_FINISHPAGE_TEXT "${APP_NAME} 已安装完成。$\r$\n$\r$\n第一次使用：先点右上角「设置」填一个第三方大模型接口（地址、密钥、模型），再点「框选聊天区域」框住聊天窗口，之后按「生成 3 条回复」即可。$\r$\n$\r$\n本程序不会自动发送任何消息。"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "English"

; Never unpack into a relative path.
;
; This used to remember the previous install directory in the registry
; (InstallDirRegKey). That memory is gone: a single corrupted value there made
; every later install unpack into that directory - including "wherever the
; installer happened to be launched from", which put the whole payload in the
; project folder. Now $INSTDIR is always %LOCALAPPDATA%\LiteChat unless the user
; deliberately picks another folder on the directory page.
;
; The guard stays as a second line of defence: an absolute Windows path always
; has ":" as its second character, anything else is reset to the default.
Function EnsureInstallDir
  StrCpy $R0 "$INSTDIR" 1 1
  StrCmp $R0 ":" done
    StrCpy $INSTDIR "$LOCALAPPDATA\${APP_ID}"
  done:
FunctionEnd

Function .onInit
  Call EnsureInstallDir
FunctionEnd

Section "Install"
  Call EnsureInstallDir
  SetOutPath "$INSTDIR"

  ; 安装前先关掉正在运行的旧版本，否则文件被占用
  nsExec::ExecToLog 'taskkill /IM "${APP_EXE}" /F'
  Pop $0
  ; The process needs a moment to actually exit and release its .exe, otherwise
  ; the file copy / delete below races it and quietly fails.
  Sleep 800

  File /r "..\dist\LiteChat\*.*"

  CreateShortcut "$DESKTOP\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}"
  CreateDirectory "$SMPROGRAMS\${APP_NAME}"
  CreateShortcut "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}"
  CreateShortcut "$SMPROGRAMS\${APP_NAME}\卸载 ${APP_NAME}.lnk" "$INSTDIR\Uninstall.exe"

  WriteUninstaller "$INSTDIR\Uninstall.exe"

  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_ID}" \
    "DisplayName" "${APP_NAME}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_ID}" \
    "DisplayVersion" "${APP_VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_ID}" \
    "Publisher" "${PUBLISHER}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_ID}" \
    "DisplayIcon" "$INSTDIR\${APP_EXE}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_ID}" \
    "UninstallString" "$INSTDIR\Uninstall.exe"
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_ID}" \
    "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_ID}" \
    "NoRepair" 1
SectionEnd

Section "Uninstall"
  nsExec::ExecToLog 'taskkill /IM "${APP_EXE}" /F'
  Pop $0
  Sleep 800

  Delete "$DESKTOP\${APP_NAME}.lnk"
  Delete "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk"
  Delete "$SMPROGRAMS\${APP_NAME}\卸载 ${APP_NAME}.lnk"
  RMDir "$SMPROGRAMS\${APP_NAME}"

  RMDir /r "$INSTDIR"

  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_ID}"
  DeleteRegKey HKCU "Software\${APP_ID}"

  ; 配置和密钥在 %APPDATA%\LiteChat，卸载时不删——用户可能想留着重装后继续用。
  MessageBox MB_YESNO|MB_ICONQUESTION \
    "是否一并删除配置和 API 密钥？$\r$\n$\r$\n位置：$APPDATA\${APP_ID}" \
    /SD IDNO IDNO keep_config IDYES drop_config
  drop_config:
    RMDir /r "$APPDATA\${APP_ID}"
  keep_config:
SectionEnd
