"""Private per-connection RTP metrics; no packets, accounts or credentials.

Keep the latest-report compatibility path, but never use it as the only evidence:
each connection owns a separate report, atomically replaced between its phases.
All files are private and report I/O errors fail the QA instead of implying PASS.
"""
import json
import os
import tempfile
from pathlib import Path


class ConnectionMetrics:
    def __init__(self):
        self.path = None
        self.directory = None

    @staticmethod
    def _atomic_write(path, content):
        fd, temporary = tempfile.mkstemp(prefix='.rtp-metrics-', dir=path.parent)
        temporary = Path(temporary)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as output:
                output.write(content)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)

    def write(self, directory, summary):
        directory = Path(directory)
        # The suite supplies a fresh private runtime; refuse obvious path aliases.
        if directory.is_symlink() or not directory.is_dir():
            raise ValueError('Metrics directory must be an existing non-symlink directory')
        resolved = directory.resolve()
        if self.directory is not None and resolved != self.directory:
            raise ValueError('A metrics connection cannot change runtime directories')
        content = json.dumps(summary, indent=2, allow_nan=False) + '\n'
        if self.path is None:
            fd, name = tempfile.mkstemp(prefix='rtp-wire-connection-', suffix='.json', dir=resolved)
            os.close(fd)
            self.path = Path(name)
            self.directory = resolved
        self._atomic_write(self.path, content)
        # Old consumers still see the last phase, without deleting older connections.
        self._atomic_write(resolved / 'rtp-wire-metrics.json', content)
        return self.path
