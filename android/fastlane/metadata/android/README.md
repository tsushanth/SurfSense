# Store listing mirror (read this before editing)

The files in this folder (`<locale>/title.txt`, `short_description.txt`, `full_description.txt`) are a
**mirror of the live Google Play listing**, copied byte for byte from the Play Developer API on
2026-11-12. They are not the source of truth. The live listing is edited through the Play API / Play
Console (and the listing experiment harness), so this copy can fall behind.

The Fastfile lanes do not upload listing text, images or screenshots (`skip_upload_metadata`,
`skip_upload_images` and `skip_upload_screenshots` are true). Do not flip those flags, and do not run
`fastlane supply` directly: fastlane uploads metadata by default, which would overwrite the live
listing with whatever is in these files.

To change the live listing, change it on Play (or through the harness), then refresh these files from
a fresh read of the live listing so the mirror stays equal to it.

Locales live on Play on 2026-11-12: en-US.

These locales exist only in this repo and are **not live** on Play: de-DE, es-ES, fr-FR, hi-IN, it-IT,
ja-JP, ko-KR, pt-BR, zh-CN. They are kept as drafts and were not updated when the live en-US text was
rewritten, so they should be re-checked against the live en-US text before anyone publishes them. A
metadata upload would add them to the live listing.
