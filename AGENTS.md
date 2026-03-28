# Agent Instructions

## User preferences
local user communication preferences are in ~/.claude/CLAUDE.md

## Environment

This devcontainer runs as user `vscode` with **no root or sudo access**.
The kernel enforces `no-new-privileges` — privilege escalation is blocked at the OS level.
`sudo`, `su`, `newgrp`, and any setuid binary will fail immediately.

## Constraints

- **Do NOT write to**: `/opt`, `/usr`, `/etc`, `/var`, `/root`, or any system path
- **Do NOT attempt**: `sudo`, `su`, `chmod +s`, or any privilege escalation
- **Do NOT install**: system packages via `apt` or `dpkg` — they require root
- **Writable paths**: `$HOME` (`/home/vscode`), `/tmp`, `/workspaces`
- Install pip packages with `--user`, npm packages with `--prefix ~/.local`

## Network

Outbound traffic is firewall-controlled (iptables/DOCKER-USER on the host).
RFC1918 ranges are blocked: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16.
Public internet is reachable. DNS resolves via the configured resolver only.
