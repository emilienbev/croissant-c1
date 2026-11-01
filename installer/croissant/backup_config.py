"""The tunables of backup and restore, in one place."""
from pathlib import Path

# Where backups are kept: the folder croissant-server uses too (its config.py), so both ways of
# backing up share one list. Home-relative whether the CLI runs from a checkout or installed.
BACKUPS = Path.home() / '.croissant' / 'backups'
FOLDER_MODE = 0o700                  # a backup holds the provider keys in clear and the photos
FILE_MODE = 0o600

MANIFEST_FORMAT = 1                  # the manifest.json this CLI reads
MANIFEST_MAX_BYTES = 1024 * 1024     # as much of it as is read, for a listing or a check

ARCHIVE_TIMEOUT_S = 600              # `am broadcast` waits while the app builds the zip
TRANSFER_TIMEOUT_S = 1800            # adb pull and push of a zip, which photos make large
OUTCOME_WAIT_S = 180                 # for restore-outcome.json, after the app restarts to apply a restore
OUTCOME_POLL_S = 2
