# Store listing

These are the Modrinth project fields. `description.md` is the page body and `icon.png` is the 400 × 400 project icon, built from the mod's own Levitation Module and Maglev Guideway textures.

| Field | Value |
| --- | --- |
| Name | Create: Maglev |
| Slug | `create-maglev` |
| Summary | Electric, bullet, and maglev trains for Create, powered by trackside substations charged with Create: New Age electricity. |
| License | MIT |
| Categories | technology, transportation |
| Environment | Required on both client and server |
| Source | <https://github.com/betizzel/create-maglev> |
| Issues | <https://github.com/betizzel/create-maglev/issues> |
| Wiki | <https://github.com/betizzel/create-maglev/wiki> |

## Publishing a release

`./build.sh publishMods` uploads the remapped release JAR to Modrinth, with the changelog taken from this version's section of the main README. It needs a Modrinth personal access token in `MODRINTH_TOKEN` with the create, read, and write versions scopes; keep it outside the repository, for example in a private file you `source` first (`source ~/.config/create-maglev/publish.env && ./build.sh publishMods`). Without the token it only performs a dry run and writes what it would upload under `build/publishMods/`. The Modrinth project ID lives in `gradle.properties`.
