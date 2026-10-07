# ROM Art Prep

**ROM Art Prep is a standalone Android artwork and metadata preparation tool for ROM collections.**

It was created to solve a simple problem: sometimes you want artwork for your ROMs without installing an entirely different frontend just to get a scraping feature.

ROM Art Prep scans your ROM locations, identifies game systems, searches available artwork sources, downloads matching artwork, and can generate and maintain `gamelist.xml` files for compatible frontends.

It is designed to work with existing ROM collections rather than replace your emulator or frontend.

## What It Does

- 📁 Scan and manage multiple saved ROM locations, including internal storage and SD cards
- 🎮 Identify supported game systems from filenames, extensions, and folder names
- 🖼️ Search for and download game artwork
- ⚙️ Choose which supported systems ROM Art Prep should search for artwork
- 🚫 Ignore specific filenames so unwanted files are excluded from future scans
- 🔎 Support alternate system names and RetroArch/Libretro folder naming
- ✋ Provide manual artwork selection when automatic matching cannot identify a game
- ⚡ Process ROMs one at a time with fast jobs completed immediately and slower searches handled through a queue
- 📋 Generate and maintain `gamelist.xml` files
- 💾 Remember artwork matches while avoiding incorrect matches between different ROM files
- 🗂️ Keep artwork associated with the ROM's original storage location
- 🧹 Clean up `gamelist.xml` entries when ROMs are deleted
- 🗑️ Optionally remove orphaned artwork from deleted ROMs
- 🎯 Filter the Artwork gallery by platform
- 📱 Continue scanning safely when the Android app is placed in the background

## Why ROM Art Prep?

Many Android emulation frontends already include artwork scraping, but sometimes you don't want to change your frontend or install another launcher just to obtain artwork.

ROM Art Prep is intentionally separate.

You can keep using the frontend and emulators you already like, while using ROM Art Prep as a dedicated artwork preparation tool.

## System Detection

ROM Art Prep can recognize systems using several types of information, including:

- System folder names
- Common alternate system names
- File extensions
- RetroArch/Libretro folder names

For example, folders such as:

- `Nintendo - Super Nintendo Entertainment System`
- `Nintendo - Game Boy Advance`
- `Sony - PlayStation`
- `Sony - PlayStation 2`
- `Sega - Mega Drive - Genesis`

can be recognized without requiring the user to manually select the system.

The app intentionally avoids overly broad manufacturer-only names such as `Nintendo`, `Sega`, or `Commodore` because those names can refer to multiple different systems.

## System Selection

ROM Art Prep can automatically detect the system for each file, while also allowing users to choose which supported systems should be included in artwork searches.

All supported systems are enabled by default.

Systems can be turned off in Settings when the user does not want ROM Art Prep to search for artwork for those systems.

Disabled systems are skipped before artwork lookup and network processing.

## Ignored Files

ROM Art Prep allows users to exclude specific filenames from future scans.

When a file is ignored, its exact filename is saved to the user's Ignored Files list. Matching is case-insensitive, but the filename must otherwise match exactly.

Ignored files are excluded before system detection and do not appear in scan results or consume artwork lookup, checksum, network, or retry processing.

Ignored filenames can be reviewed and removed from:

**Settings → Ignored Files**

For convenience, ROM Art Prep also provides an optional shortcut for adding common 3DS system files:

- `boot9.bin`
- `boot11.bin`
- `seeddb.bin`
- `shared_font.bin`

These files are never ignored automatically. The user must explicitly choose to add them.

## Multiple ROM Locations

ROM Art Prep supports multiple saved ROM locations, allowing collections to be split across internal storage, SD cards, or other accessible Android storage locations.

Each saved location maintains its own storage permission and is scanned independently.

Artwork remains associated with the ROM's original location. ROM Art Prep does not move ROMs or copy artwork between locations.

For example:

```text
Internal Storage
└── ROMS
    ├── NES
    ├── SNES
    ├── GB
    └── ...

SD Card
└── 3DS
    ├── Kid Icarus Uprising.cci
    └── The Legend of Zelda - A Link Between Worlds.cci
## Artwork Sources

ROM Art Prep currently uses:

- Hasheous
- Libretro thumbnails
- TheGamesDB
- ScreenScraper

Artwork availability depends on the source and the particular game.

TheGamesDB and ScreenScraper require the appropriate credentials when enabled.

If automatic matching cannot identify a game, the app can provide a manual artwork search so the user can choose an appropriate result.

## 3DS File Support

ROM Art Prep recognizes the following common 3DS game formats:

- `.3ds`
- `.cia`
- `.cci`

Common 3DS system files can be excluded using the Ignored Files settings.

## Fast Scanning

ROM Art Prep is designed to avoid allowing one difficult ROM search to hold up an entire collection.

The scanner processes one ROM at a time and uses a queue for searches that take longer than expected.

In general:

**Fast match → process immediately**

**Slow search → defer and retry**

**Successful match → download artwork**

**No usable automatic match → ask the user to choose**

This allows the rest of a collection to finish without waiting indefinitely on one difficult title.

## Gamelist Management

ROM Art Prep can generate and maintain `gamelist.xml` files for compatible ROM folders.

Existing game entries are preserved so frontend information such as favorites and play counts is not unnecessarily discarded.

When enabled, ROM Art Prep checks for deleted ROMs and removes their corresponding entries from the appropriate `gamelist.xml`.

Cleanup is performed separately for each ROM location and is designed to avoid making changes when a storage location is temporarily unavailable or unreadable.
## Orphaned Artwork Cleanup

ROM Art Prep can optionally remove artwork that no longer corresponds to a ROM in the same folder.

This feature is **disabled by default**.

When enabled, orphaned artwork cleanup runs during library scans and removes supported artwork files that no longer have a corresponding ROM.

The setting is available under:

**Settings → Orphaned Artwork**

ROM files themselves are never deleted by this feature.

## Supported Frontends

ROM Art Prep is not tied to a specific frontend.

The artwork and `gamelist.xml` files it creates can be used by compatible frontends that follow the corresponding folder and metadata conventions.

It can therefore be used alongside an existing frontend rather than requiring you to replace it.

## Android

ROM Art Prep was developed and tested on Android, including the Anbernic RG DS.

The application uses Android's storage permissions and folder selection system so that the user chooses which ROM collections the application is allowed to access.

ROM Art Prep is intended for Android phones, tablets, and compatible handheld gaming devices.

## Installation

ROM Art Prep is distributed as an Android APK through the **Releases** section of this repository.

Download the latest APK from the latest GitHub release and install it on your Android device.

ROM Art Prep can be installed as an update over an existing compatible installation. Existing app settings and folder permissions should normally be retained when updating.
## Current Status

**Version 1.6.7 · Build 29**

ROM Art Prep is a public Android release and is actively developed and tested on the Anbernic RG DS.

The current release includes:

- Multiple saved ROM locations
- Artwork tracking across separate storage locations
- 3DS `.cci` detection
- Deleted-ROM `gamelist.xml` cleanup
- Optional orphaned-artwork cleanup
- Platform filtering in the Artwork gallery

The project currently passes:

- **409 automated tests**
- **0 lint errors**
- **Release build successfully**

Version 1.6.4 was physically verified on an Anbernic RG DS with ROMs stored across two separate locations. Artwork was correctly detected and displayed from both internal storage and an SD card.

## Diagnostics & Privacy

ROM Art Prep includes optional diagnostic information to help investigate difficult scans.

Diagnostic reports are designed to avoid including ROM contents, artwork, passwords, API keys, authentication tokens, or Android system logs.

Filenames can be excluded from reports, and games can instead be represented anonymously as entries such as `Game 1`.

## Screenshots
## Screenshots

![ROM Art Prep artwork gallery](artwork.png)

![ROM Art Prep main screen](main-screen.png)

![ROM Art Prep library scan](library-scan.png)




## AI-Assisted Development

AI-assisted development tools were used during the creation and troubleshooting of ROM Art Prep.

The project was developed through iterative testing, investigation, debugging, and verification on actual Android hardware.

## License

See the repository for licensing information.

## Feedback

If you find a bug, have a suggestion, or encounter a ROM/system that does not behave as expected, please open an issue with as much useful information as possible.

Screenshots and diagnostic information can be especially helpful when investigating problems.

---

**ROM Art Prep**

A simple tool for getting your ROM artwork ready without changing the way you play your games. 🎮




## Copyright

Copyright © 2026 by Developer. All rights reserved.

ROM Art Prep is provided publicly for viewing, testing, and discussion. No license is granted to reproduce, redistribute, modify, or commercially distribute the software or derivative works without permission from the copyright holder.
