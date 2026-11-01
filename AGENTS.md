# Working on TV Air Dashboard

- This is an independent source fork; keep application ID `org.tvair.dashboard` distinct from upstream.
- Never commit real geographic defaults, selected reporting regions, account countries, addresses, emails,
  purifier serials, credentials, ADB keys, private setup state, signing keys, backups or build output.
- The published weather coordinates and region selection must stay empty. Configure your own private build locally.
- Do not collect location automatically or add telemetry. Document all network destinations and permissions.
- Indoor readings remain separate from regional outdoor observations. Do not invent indoor AQI or identify regional
  data as measurements at a specific home. Preserve sensor freshness/invalid-data checks.
- Keep source notices and asset licences. No prebuilt APKs or private builds in this repository.
- For launcher changes run `testReleaseUnitTest lintRelease assembleRelease` in `launcher/` with JDK 17/SDK 34.
- For setup changes run `python -m unittest discover -s dyson-setup -p 'test_*.py' -v`.
- A test on a configured implementation is not a test of another package or firmware. State those limits.
