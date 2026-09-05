#!/usr/bin/env bash
# Consistent offline snapshot. Root only; never publishes player data.
set -euo pipefail
umask 077
[[ $(id -u) == 0 ]] || { echo 'Run as root'; exit 2; }
runtime=$(realpath "${1:?Usage: backup.sh RUNTIME BACKUP_DIR warland.service}")
backups=$(realpath -m "${2:?Backup directory required}")
service=${3:?Service required}
[[ "$service" =~ ^warland(-staging)?\.service$ ]] || { echo 'Refusing unexpected service'; exit 2; }
[[ "$runtime" != / && -f "$runtime/fabric-server-launch.jar" && -f "$runtime/server.properties" ]] || { echo 'Not a Fabric runtime'; exit 2; }
unit_directory=$(systemctl show --property=WorkingDirectory --value "$service")
[[ -n "$unit_directory" && "$(realpath "$unit_directory")" == "$runtime" ]] || { echo 'Service/runtime mismatch; nothing stopped'; exit 2; }
[[ "$backups" != "$runtime" && "$backups" != "$runtime/"* ]] || { echo 'Backups must be outside runtime'; exit 2; }
mkdir -p "$backups"
chmod 700 "$backups"
exec 9>/var/lock/warland-backup.lock
flock -n 9 || { echo 'Backup already running'; exit 3; }
was_running=false
if systemctl is-active --quiet "$service"; then was_running=true; fi
restore_service() { if $was_running; then systemctl start "$service" || echo 'WARNING: service restart failed' >&2; fi; }
trap restore_service EXIT
systemctl stop "$service"
if systemctl is-active --quiet "$service"; then echo 'Refusing inconsistent snapshot'; exit 4; fi
stamp=$(date -u +%Y%m%dT%H%M%SZ)
archive="$backups/warland-$stamp.tar.gz"
[[ ! -e "$archive" && ! -e "$archive.partial" ]] || { echo 'Snapshot name collision'; exit 4; }
tar --exclude='./console.pipe' --exclude='./logs' --exclude='./console.log' --exclude='./crash-reports' --exclude='*.pid' -czf "$archive.partial" -C "$runtime" .
tar -tzf "$archive.partial" >/dev/null
mv "$archive.partial" "$archive"
sha256sum "$archive" > "$archive.sha256"
printf 'Verified archive: %s\n' "$archive"
# No automatic deletion: operator must explicitly configure retention.
