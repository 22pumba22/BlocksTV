# F-Droid preparation

Store descriptions, icon, TV banner, screenshots and version-code changelogs
are provided in `fastlane/metadata/android/en-US` and `it-IT`.
No Fastlane installation is required. These files do not alter the application.

Build using a FLOSS JDK (17 or newer, compatible with Gradle 9), Android SDK 35:

```sh
sh gradlew assembleRelease
```

The unsigned release APK is produced in `app/build/outputs/apk/release/`.
The release configuration has no upstream signing key. F-Droid can build and
sign the app. The debug APK published on GitHub is not a reproducible-build
reference binary and must not be configured as `Binaries` for this recipe.

A metadata-upload commit must be recorded before submitting a build recipe.
The existing `v0.9` tag precedes the store metadata; do not silently move it.
The initial packager recipe may pin a full commit containing these files,
with versionName 0.9 and versionCode 9 unchanged. Final acceptance of this
pre-release and its initial build revision is up to F-Droid maintainers.

For future stable releases, increase versionCode and versionName, write the
matching version-code changelog in both locales and publish a `vX.Y` tag.
Use suffixes such as `vX.Y-beta1` for future test releases so they can be
excluded by the proposed tag filter. A GitHub pre-release label alone does
not change what a Git tag checker sees.

The app requires a remote or gamepad; it does not implement touch controls.
No runtime advertising/tracking libraries, network permissions, native code,
proprietary SDKs, private Maven repositories or API keys are declared in the
project reviewed for this preparation. Kotlin/Android build plugins and the
Kotlin runtime still need the usual F-Droid dependency review.

The launcher icon PNG renders the existing Android vector icon; the TV banner
and screenshots copy the published project assets. Artifact origin and rights
must be documented before requesting inclusion.

References:
- https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/
- https://f-droid.org/en/docs/All_About_Descriptions_Graphics_and_Screenshots/
- https://f-droid.org/en/docs/Inclusion_Policy/
