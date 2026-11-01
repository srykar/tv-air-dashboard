# Private local HushJet setup

This optional Python utility obtains a HushJet's local MQTT credential through Dyson's HTTPS account API. The implementation uses a community-documented protocol and may need updates if Dyson changes that API. It is not an official Dyson integration.

No account country, purifier address or account information is built in. It currently requires exactly one supported HushJet (product 897) on the account. Other/multiple devices need explicit selection support before use.

1. Install the dependency in your own Python virtual environment: `pip install -r dyson-setup/requirements.txt`.
2. Choose a private state directory **outside this checkout** and identify your purifier's private IPv4 address from your router or device app.
3. Run the helper with your own values:

   ```sh
   python dyson-setup/setup.py --purifier-host <purifier-private-ip> --state-dir <private-state-dir>
   ```

4. Open the private `localhost` URL printed by that process. Enter your account country and email, then the emailed code and password. Do not put those values, the URL, screenshots of the form or its temporary files in an issue or chat.
5. Keep the helper running while provisioning. Install your source-built `org.tvair.dashboard` app. Temporarily authorize your computer for ADB, and use the already-connected ADB target:

   ```sh
   python dyson-setup/provision.py --connection <private-state-dir>/connection.json --device <authorized-adb-target>
   ```

6. Provisioning streams only the five local-device fields to the shell-protected provider over stdin. It does not put secrets in command arguments or shared TV files. On success it removes the temporary connection file. Stop the sign-in helper with Ctrl+C; it clears its in-memory account state and removes the three temporary files it created.
7. Revoke debugging authorizations and switch all debugging off. Verify the debugging port is closed, restart normally, and check Home and the purifier values. The computer and sign-in helper are no longer needed.

The form is served only on loopback, requires a per-session capability and CSRF token, validates Host and Origin, does not log submitted values and disables browser caching. Password and login code are held only for the request; the cloud token is not saved. Only the local MQTT device credential is written temporarily and transferred to app-private storage. Android backup is disabled.

Keep the purifier's private address stable with a router reservation. If it changes, the app cannot find it; provision an updated connection locally. Clearing app data or reinstalling under a different package requires setup again. To forget a connection, clear this app's storage in Android settings; this also clears its launcher preferences. Never expose the MQTT port or ADB to the internet.
