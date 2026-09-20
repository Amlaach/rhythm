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

[Languages]
Name: "hebrew"; MessagesFile: "compiler:Languages\Hebrew.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
; The whole application image, runtime included. recursesubdirs because the
; runtime is a tree, not a file.
Source: "{#SourceDir}\*"; DestDir: "{app}"; \
    Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\{cm:UninstallProgram,{#AppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
; The thing this whole change was for: a ticked box on the last page that
; opens the app. nowait so the installer closes rather than waiting for
; someone to quit the player, skipifsilent so an unattended install stays
; unattended.
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; \
    Flags: nowait postinstall skipifsilent
