; The Windows installer.
;
; jpackage built this until now, and could not be told to launch the app when
; it finished: it generates the MSI from a fixed WiX template with no such
; page in it and no switch to add one. Inno Setup makes that one line, in
; [Run] at the bottom, and gives the wizard Hebrew at the same time - which
; for an app that is Hebrew throughout was the stranger thing to be missing.
;
; jpackage still builds the application image: the folder with Rhythm.exe, the
; cut down Java runtime and the jars. This only wraps that folder, which is
; the division of labour each tool is good at.
;
; AppVersion, SourceDir and OutDir are passed in by the build so that a
; version lives in exactly one place.

#define AppName "Rhythm"
#define AppExe "Rhythm.exe"

[Setup]
; Never change this. It is what tells Windows that a new installer replaces
; this one rather than standing beside it in Add/Remove Programs. It is the
; same value the MSI used as its upgrade code, so an existing install is
; recognised rather than duplicated.
AppId={{8F3B1C42-5D6E-4A7F-9B2C-1E0D7A4F6C33}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Rhythm
VersionInfoVersion={#AppVersion}

; Per user, under Local AppData, which is what lets the whole thing install
; without a single administrator prompt. A music player has no business
; asking for the machine.
PrivilegesRequired=lowest
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes

OutputDir={#OutDir}
OutputBaseFilename=RhythmSetup-{#AppVersion}
SetupIconFile=..\icons\rhythm.ico
UninstallDisplayIcon={app}\{#AppExe}

Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern

; Upgrading over a running copy.
;
; Rhythm.exe and the jars under it are held open while the app is running, and
; an installer that cannot replace a locked file either fails or defers the
; whole thing to a reboot. Either way the person in front of it concludes that
; the old version has to be uninstalled first. This offers to close it instead,
; and does not reopen it afterwards - the last page already has a tick box for
; that, and starting it twice is worse than not starting it.
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "hebrew"; MessagesFile: "compiler:Languages\Hebrew.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
; Upgrading into the folder an older version is already in.
;
; The application image is a tree of jars and a cut down runtime, and some of
; those names carry versions. Copying a new image over an old one replaces
; what the two have in common and leaves everything else where it was - so a
; jar the new version dropped stays on the classpath and breaks it, for a
; reason nothing on screen will point at. These two directories are rebuilt
; from scratch by every build, so they are cleared rather than merged.
;
; Nothing of the user's is in here. The library, the ratings and the settings
; live in %LOCALAPPDATA%\Rhythm, which this installer never touches - not on
; upgrade, and not on uninstall either.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
; The whole application image, runtime included. recursesubdirs because the
; runtime is a tree, not a file.
Source: "{#SourceDir}\*"; DestDir: "{app}"; \
    Flags: ignoreversion recursesubdirs createallsubdirs
; Ship license notices alongside the JARs that the installer redistributes.
Source: "THIRD_PARTY_NOTICES.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "licenses\LGPL-2.1.txt"; DestDir: "{app}\licenses"; Flags: ignoreversion
Source: "licenses\APACHE-2.0.txt"; DestDir: "{app}\licenses"; Flags: ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\{cm:UninstallProgram,{#AppName}}"; Filename: "{uninstallexe}"
; The desktop shortcut is made in [Code] below rather than here. A desktop
; inside OneDrive is often closed to installers - Windows Security's
; controlled folder access, or OneDrive itself - and an [Icons] entry that
; cannot be written stops the install on "IPersistFile::Save failed ...
; Access is denied" for the sake of a shortcut. The one in the Start menu is
; enough to find the app by; the desktop one is made when it can be.

[UninstallDelete]
Type: files; Name: "{autodesktop}\{#AppName}.lnk"

[Run]
; The thing this whole change was for: a ticked box on the last page that
; opens the app. nowait so the installer closes rather than waiting for
; someone to quit the player, skipifsilent so an unattended install stays
; unattended.
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; \
    Flags: nowait postinstall skipifsilent

[Code]
{ Rhythm has to be closed before its files can be replaced.

  CloseApplications above asks Windows' Restart Manager to do it, and for a
  Java program that does not always work: the launcher and the runtime's DLLs
  stay loaded, and the copy then fails on "MoveFile failed; code 5. Access is
  denied" for runtime\bin\ucrtbase.dll - which reads as a broken installer.
  So the running copy is closed here first: asked to close its window, and
  ended if it has not gone a few seconds later. Only a Rhythm.exe running from
  the folder being installed into; another program of the same name is left
  alone. The library, ratings and settings are in %LOCALAPPDATA%\Rhythm and
  are written as they change, so nothing is lost by closing it. }
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Dir, Script, PowerShell: String;
  ResultCode: Integer;
begin
  Result := '';
  Dir := ExpandConstant('{app}');
  { A single quote in the path - a user name like O'Brien - is doubled for
    PowerShell's single-quoted string. }
  StringChangeEx(Dir, '''', '''''', True);
  Script :=
    '$d = ''' + Dir + '''; ' +
    'Get-Process -Name Rhythm -ErrorAction SilentlyContinue | ' +
    'Where-Object { $_.Path -and $_.Path.StartsWith($d, [System.StringComparison]::OrdinalIgnoreCase) } | ' +
    'ForEach-Object { [void]$_.CloseMainWindow(); ' +
    'if (-not $_.WaitForExit(5000)) { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue } }';
  { The 64-bit PowerShell where there is one: the installer itself is a
    32-bit program, and a 32-bit PowerShell cannot read the path of the
    64-bit Rhythm.exe, so it would never find the copy to close. }
  if IsWin64 then
    PowerShell := ExpandConstant('{sysnative}\WindowsPowerShell\v1.0\powershell.exe')
  else
    PowerShell := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
  Exec(PowerShell,
    '-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "' + Script + '"',
    '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  { A moment for Windows to let go of the files the process had open. }
  Sleep(1000);
end;

{ The desktop shortcut, when the desktop can be written to; see [Icons]. }
procedure CurStepChanged(CurStep: TSetupStep);
begin
  if (CurStep = ssPostInstall) and WizardIsTaskSelected('desktopicon') then
  begin
    try
      CreateShellLink(ExpandConstant('{autodesktop}\{#AppName}.lnk'), '{#AppName}',
        ExpandConstant('{app}\{#AppExe}'), '', ExpandConstant('{app}'), '', 0, SW_SHOWNORMAL);
    except
      Log('Desktop shortcut not created: ' + GetExceptionMessage);
    end;
  end;
end;
