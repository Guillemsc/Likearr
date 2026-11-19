[![Test](https://github.com/Guillemsc/Likearr/actions/workflows/test.yml/badge.svg)](https://github.com/Guillemsc/Likearr/actions/workflows/test.yml)

# Likearr

Likearr moves liked Explo tracks into the main Jellyfin music library. It copies and verifies the file, waits until Jellyfin indexes the copy, likes the new item, then removes the old like and original file. It skips matching tracks already indexed elsewhere in the main music library. Pending move records persist between runs and expire after 30 days; expiring a record does not delete music.

## Java application

The application uses Java 21 and Maven. The default container runs the scheduler at **01:00** every day in the `Europe/Amsterdam` time zone, retrying pending moves for up to 45 minutes. The application also supports `--dry-run` and `--apply` for one-off checks.

Build and test with `mvn clean verify`. The Dockerfile builds a Java runtime image. `compose.yaml` builds it locally; `deploy/compose.container-manager.yaml` pulls the published image from GHCR.

All connection details and paths are configured with environment variables in `.env`; copy `.env.example` and set values for your Jellyfin server and filesystem. `LIKEARR_JELLYFIN_API_KEY` and `LIKEARR_JELLYFIN_USERNAME` select the account whose likes are processed. `LIKEARR_JELLYFIN_URL` is the server address. `LIKEARR_MUSIC_HOST` and `LIKEARR_STATE_HOST` are host paths mounted as `LIKEARR_MUSIC_ROOT` and `LIKEARR_STATE_ROOT` inside the container. `LIKEARR_EXPLO_ROOT` identifies the source folder, and `LIKEARR_JELLYFIN_MUSIC_ROOT` is the path prefix reported by Jellyfin. Likearr automatically creates `/state/promote-favorites-pending.json` when it first records a pending move; the host state directory remains mounted for persistence. `TZ` controls the daily schedule timezone. The default `compose.yaml` uses `env_file: .env`; keep that file private and never commit it.

This repository does not contain credentials, music, or live state. The currently running NAS container still uses the previous Python implementation; updating this repository alone does not deploy or start the Java application. Do not run both implementations at the same time: they share pending state and music files and do not coordinate with each other.

The mover requests a Jellyfin library refresh after creating a copy. Explo's `--clean-downloads` setting may remove an original while its move is pending; review that setting before relying on the mover. Tracks with old pending records from versions that did not store the original Jellyfin item ID are left for manual review.

## GitHub Actions and Container Manager

`Test` runs Maven verification on pushes to `main`, pull requests and manual runs. `Publish image` is manual (`Actions` → workflow → `Run workflow`); it runs tests, builds the container and publishes both a commit tag and `latest` to GHCR. It does not connect to the NAS.

For the first installation, make the Likearr package public in GitHub Packages so Container Manager can pull it without registry credentials. The Container Manager Compose file uses `ghcr.io/guillemsc/likearr:latest`. Create a project folder on the NAS, put that Compose file there, and create a `.env` beside it using `.env.example` as the template. Set the host music and state paths, Jellyfin URL, API key and username. Keep `.env` private.

In Container Manager, create a Project from that folder and start it. Container Manager reads the Compose image reference and pulls it from GHCR. For updates, run `Publish image`, then use the project’s Action menu to pull the updated image and recreate the project. Use a commit tag instead of `latest` when you want a fixed version; changing tags also makes rollback straightforward.

Before starting Java the first time, stop the existing Python `explo-favorites` project in Container Manager. Both processes handle the same liked tracks and pending state, so only one should run.
