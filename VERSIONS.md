# Window Layouts version checkpoints

Version tags identify source snapshots that can be downloaded or checked out independently. The development labels are intentional; a tag is a rollback point, not a new claim of stable-release status. Historical experiments retain their original behavior.

The current implementation remains on `main`. Missing historical checkpoints were recovered on archive branches without rewriting existing published history. Recovered code was compiled against ImageJ 1.54p targeting Java 8; this does not repeat its original physical-input acceptance tests.

| Version tag | Commit | Checkpoint |
|---|---|---|
| [v1.0.0](https://github.com/AIHector/Fiji-Window-Layouts/tree/v1.0.0) | [cd2d58d](https://github.com/AIHector/Fiji-Window-Layouts/commit/cd2d58db2d725effdb1764807186c5afcfd2ef01) | previously published version |
| [v1.0.1](https://github.com/AIHector/Fiji-Window-Layouts/tree/v1.0.1) | [702d5b4](https://github.com/AIHector/Fiji-Window-Layouts/commit/702d5b410b1721111965f6f8a11a0302fd45d3ab) | previously published version |
| [v1.0.2](https://github.com/AIHector/Fiji-Window-Layouts/tree/v1.0.2) | [6639ebf](https://github.com/AIHector/Fiji-Window-Layouts/commit/6639ebf8f53704922db26364df6d1c3a56ab7af9) | previously published version |
| [v1.0.3](https://github.com/AIHector/Fiji-Window-Layouts/tree/v1.0.3) | [f96b7e0](https://github.com/AIHector/Fiji-Window-Layouts/commit/f96b7e01f1d1840cab0b2eb69c3bd81fb004b825) | previously published version |
| [v1.0.4](https://github.com/AIHector/Fiji-Window-Layouts/tree/v1.0.4) | [42110d5](https://github.com/AIHector/Fiji-Window-Layouts/commit/42110d5034468679fcbe45c5df21e7d21b709f79) | previously published version |
| [v1.0.5](https://github.com/AIHector/Fiji-Window-Layouts/tree/v1.0.5) | [55c5492](https://github.com/AIHector/Fiji-Window-Layouts/commit/55c549243494e0ee8bf871e568b22ebd2cc139d9) | previously published version |
| [v1.0.6](https://github.com/AIHector/Fiji-Window-Layouts/tree/v1.0.6) | [e3c2dea](https://github.com/AIHector/Fiji-Window-Layouts/commit/e3c2dea8d3d07176995d47c6ed35f1fc6a7c87a1) | historical; superseded zoom revision |
| [v1.0.7-dev1](https://github.com/AIHector/Fiji-Window-Layouts/tree/v1.0.7-dev1) | [dbce96d](https://github.com/AIHector/Fiji-Window-Layouts/commit/dbce96dbce07df2ad18cf5f2784f9b187fb82d69) | rejected zoom experiment; not recommended |
| [v1.0.8-dev1](https://github.com/AIHector/Fiji-Window-Layouts/tree/v1.0.8-dev1) | [b097ccf](https://github.com/AIHector/Fiji-Window-Layouts/commit/b097ccfee19f3aaffab97230a878fa552f55ab1f) | accepted zoom candidate; superseded |
| [v1.0.8](https://github.com/AIHector/Fiji-Window-Layouts/tree/v1.0.8) | [4d9b68a](https://github.com/AIHector/Fiji-Window-Layouts/commit/4d9b68ad2213ee76915e4467e5d8f4d8129623a3) | accepted stable zoom revision |
| [v1.0.9-dev1](https://github.com/AIHector/Fiji-Window-Layouts/tree/v1.0.9-dev1) | [e16d890](https://github.com/AIHector/Fiji-Window-Layouts/commit/e16d8900549973ba5a6f8834f43acec2b484c308) | historical native-tool correction; superseded |
| [v1.0.10-dev1](https://github.com/AIHector/Fiji-Window-Layouts/tree/v1.0.10-dev1) | [2f0c432](https://github.com/AIHector/Fiji-Window-Layouts/commit/2f0c432d9e7e99d561cd45883c0de4febfbf645d) | native Histogram source-pointer inspection; superseded |
| [v1.0.11-dev1](https://github.com/AIHector/Fiji-Window-Layouts/tree/v1.0.11-dev1) | [4a66f5c](https://github.com/AIHector/Fiji-Window-Layouts/commit/4a66f5c3846201acad90fc6b737f691c2c96518d) | source-bound Histogram tool |
| [v1.0.12-dev1](https://github.com/AIHector/Fiji-Window-Layouts/tree/v1.0.12-dev1) | [01004ed](https://github.com/AIHector/Fiji-Window-Layouts/commit/01004edd93b899a4fb9900f56096d7caa46140e4) | current installed version; selected-image Histogram Tool |

## Returning to a version

1. Follow a tag above, then use **Code > Download ZIP**, or check out that tag in a separate local clone.
2. Build from that version’s source using its included instructions and a compatible Fiji installation. Tags preserve source, guides, startup macros and available tests; they do not automatically attach an installable jar. Historical build scripts can install directly, so save your work and quit Fiji before running a script that copies files into Fiji.
3. Preserve the currently installed plugin jar and startup macro, then replace them with the chosen build and reopen Fiji. Keep your image files and preferences. Companion plugins have independent version tags.

For a recently installed development version, an exact local jar/settings backup can also be restored with Fiji closed. Switching a Git tag alone does not change an installed Fiji.

Version 1.0.7-dev1 is deliberately retained as a rejected zoom experiment. Version 1.0.11-dev1 introduced separate non-image Histogram tool windows; 1.0.12-dev1 added the reusable Histogram Tool that follows image selection. The accepted smooth-zoom foundation is 1.0.8.
