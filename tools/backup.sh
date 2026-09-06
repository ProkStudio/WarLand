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
# A failed query is not evidence that the service is safely stopped.
read_state() { systemctl show --property=ActiveState --value "$service"; }
initial_state=$(read_state) || { echo 'Cannot determine service state; nothing stopped' >&2; exit 4; }
case "$initial_state" in
    active) was_running=true ;;
    inactive) was_running=false ;;
    *) echo 'Refusing unexpected service state; nothing stopped' >&2; exit 4 ;;
esac
snapshot_verified=false
finish() {
    result=$?
    trap - EXIT
    # Preserve the primary backup failure, but never hide a recovery failure.
    if $was_running; then
        if systemctl start "$service" && restored_state=$(read_state) && [[ "$restored_state" == active ]]; then
            :
        else
            echo 'WARNING: service recovery not verified; inspect service before retrying' >&2
            if [[ "$result" == 0 ]]; then result=5; fi
        fi
    fi
    if [[ "$result" == 0 ]] && $snapshot_verified; then
        printf 'Verified archive: %s\n' "$archive"
    fi
    exit "$result"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
systemctl stop "$service"
stopped_state=$(read_state) || { echo 'Cannot verify stopped service; refusing snapshot' >&2; exit 4; }
[[ "$stopped_state" == inactive ]] || { echo 'Refusing inconsistent snapshot' >&2; exit 4; }
stamp=$(date -u +%Y%m%dT%H%M%SZ)
archive="$backups/warland-$stamp.tar.gz"
[[ ! -e "$archive" && ! -e "$archive.partial" ]] || { echo 'Snapshot name collision'; exit 4; }
tar --exclude='./console.pipe' --exclude='./logs' --exclude='./console.log' --exclude='./crash-reports' --exclude='*.pid' -czf "$archive.partial" -C "$runtime" .
tar -tzf "$archive.partial" >/dev/null
mv "$archive.partial" "$archive"
sha256sum "$archive" > "$archive.sha256"
snapshot_verified=true
# EXIT reports success only after restoring the original service state.
# No automatic deletion: operator must explicitly configure retention.
