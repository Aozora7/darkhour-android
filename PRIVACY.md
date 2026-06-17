# Privacy Policy

Last updated: June 18, 2026

Dark Hour Android app (the "application") is a free and open-source Android application for inspecting
sleep, circadian timing, and schedule alignment from Health Connect sleep data.

This policy applies to the Android application published under the package name
`one.aozora.darkhour`.

Source code: https://github.com/Aozora7/darkhour-android

Privacy contact: aozora@aozora.one

## Summary

- No account is required.
- No internet permission is requested.
- No ads are shown.
- No analytics, telemetry, crash reporting, or tracking SDKs are included.
- No sleep data is uploaded to a server.
- No sleep data is sold or shared with third parties.
- Health Connect sleep data is read only after the user grants the relevant
  Android permissions.
- Health Connect sleep data is read into memory on launch or refresh and is not
  saved by the application.

## Data The Application Accesses

If the user grants permission, the application reads sleep sessions and sleep
stage intervals from Health Connect. This includes sleep timing and, when
available, stage information such as awake, light, deep, and REM sleep.

The application only accesses the Health Connect data needed for its sleep,
circadian, and schedule-alignment features.

## How Data Is Used

The application uses this data only to display the actogram, show sleep details,
and calculate sleep, circadian, and periodogram statistics.

By default, the application reads the recent Health Connect sleep range (30 days). If the
user chooses the all-history range, the application requests the additional
Health Connect history permission so older sleep records can be shown and
analyzed.

The application does not use sleep data for advertising, profiling, credit
decisions, insurance decisions, employment decisions, or any purpose unrelated to
showing the user's sleep and circadian information back to the user.

## Data Sharing

The application does not sell, rent, transfer, or share sleep data with third
parties.

The application does not include advertising SDKs, analytics SDKs, telemetry
SDKs, crash reporting SDKs, or tracking SDKs.

The application does not request the Android `INTERNET` permission.

## Local Storage

The application stores preferences and schedule entries locally so settings are
restored the next time it opens. This local data may include:

- analysis settings, such as whether naps are included;
- actogram display settings;
- date and time format settings;
- the selected Health Connect import range;
- user-created schedule entries, including labels, times, dates or days of week,
  colors, and enabled state.

These preferences are stored in private application storage. They may leave the
device as part of standard Android app backup or device transfer features if the
user has enabled those operating-system features.

Health Connect remains the source of truth for sleep data. The application reads
sleep data from Health Connect, displays and analyzes it locally, and does not
save imported sleep records.

## Data Retention And Deletion

Health Connect sleep data is retained only in memory while the application is
running. It is cleared when the application process ends, when Health Connect
permission is missing, or when Health Connect is refreshed with no available
records.

The user can revoke permissions at any time from Android Settings or Health
Connect settings. After permissions are revoked, the application can no longer
access the revoked Health Connect data.

Local preferences and schedule entries remain on the device until the user
changes them, clears application storage, uninstalls the application, or removes
restored backup data through Android system settings or backup controls.

## Security

The application relies on Android and Health Connect permission controls to
protect access to sleep data. Health Connect access is available only after the
user grants the relevant permissions.

Because the application works locally and does not upload sleep data to a
server, there is no cloud account or cloud sleep-data store operated by the
developer.

## Children

The application is not directed to children and is not designed for
child-directed use.

## Changes To This Policy

This privacy policy may be updated when the application changes how it accesses,
uses, stores, or shares data. The "Last updated" date above will be changed when
this policy is updated.

## Contact

For privacy questions, deletion requests, or security reports, use the project
issue tracker:

https://github.com/Aozora7/darkhour-android/issues
