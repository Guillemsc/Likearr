# Likearr

Likearr moves liked Explo tracks into the main Jellyfin music library. It copies and verifies the file, waits until Jellyfin indexes the copy, likes the new item, then removes the old like and original file. It skips matching tracks already indexed elsewhere in the main music library. Pending move records persist between runs and expire after 30 days; expiring a record does not delete music.

## Java application

The application uses Java 21 and Maven. The default container runs the scheduler at **01:00** every day in the `Europe/Amsterdam` time zone, retrying pending moves for up to 45 minutes. The application also supports `--dry-run` and `--apply` for one-off checks.

Build and test with `mvn clean verify`. The Dockerfile runs the same tests during its Maven build and builds a Java runtime image. `compose.yaml` builds the image and uses the existing NAS UID/GID and host networking. This deployment uses:

- `/volume1/docker/media-automation/explo/settings/.env` for the existing Explo Jellyfin URL, username and API key.
- `/volume1/docker/media-automation/explo/settings/promote-favorites-pending.json` for persistent pending records.
- `/volume1/jellyfin/Music` for files. Jellyfin's `/media/Music` path is mapped to this NAS path.
- `http://127.0.0.1:8096` for Jellyfin on the NAS.

The Jellyfin URL, settings directory and music path can be overridden with `LIKEARR_JELLYFIN_URL`, `LIKEARR_SETTINGS` and `LIKEARR_MUSIC`. The path mapping assumes Jellyfin exposes the Music library at `/media/Music`.

This repository does not contain credentials, music, or live state. The currently running NAS container still uses the previous Python implementation; updating this repository alone does not deploy or start the Java application. Do not run both implementations at the same time: they share pending state and music files and do not coordinate with each other.

The mover requests a Jellyfin library refresh after creating a copy. Explo's `--clean-downloads` setting may remove an original while its move is pending; review that setting before relying on the mover. Tracks with old pending records from versions that did not store the original Jellyfin item ID are left for manual review.
