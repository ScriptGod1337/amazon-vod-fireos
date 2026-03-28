# Development Tooling

This folder contains build automation, agent instructions, and analysis artifacts used to develop the app. None of these files are needed to build or run the APK.

## Contents

| File / Folder | Purpose |
|---|---|
| `CLAUDE.md` | AI agent instructions — phased build plan, API guidance, security rules |
| `progress.md` | Phase-by-phase build log with decisions and bug fixes |
| `analysis/` | API reverse-engineering output (`api-map.md`, `decisions.md`, `progress-api.md`) |
| `logs/` | Agent session logs |
| `setup.sh` | One-time environment setup: Java 17, Android SDK, Kodi plugin clone |
| `start-agents.sh` | Launches Claude Code agents for automated build phases |
| `register_device.py` | One-time Amazon device registration (OAuth + MFA, saves `.device-token`) |
| `.devcontainer/` | VS Code Dev Container configuration |

## Agent workflow

```
setup.sh              # install Java, SDK, clone Kodi plugin
register_device.py    # interactive: email + password + MFA -> .device-token
start-agents.sh       # Phase 1 (Opus): analyze Kodi plugin
                      # Phases 2-6 (Sonnet): scaffold, port, build, deploy, debug
```

## To debug ADB on the host
ADB must be forwarded and listen in the host

On the host - to listen on any IP
```bash
ANDROID_SDK_ROOT=/home/$USER/Android/Sdk # example of SDK root
$ANDROID_SDK_ROOT/platform-tools/adb -a nodaemon server start # listen on all IPs
```

On the container
```bash
export ANDROID_ADB_SERVER_ADDRESS=<HOST IP> 
# example
export ANDROID_ADB_SERVER_ADDRESS=192.168.0.100
```
