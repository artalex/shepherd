# Privacy Policy for Shepherd

Effective date: 13 November 2026

По-русски: [PRIVACY.ru.md](PRIVACY.ru.md)

Shepherd (called «Пастух» on devices set to Russian) is an Android app by Alexey Artamonov ("the developer"). It is a client for herdr: it connects over SSH to machines that you add yourself and shows the coding agents running there.

## In short

- The developer collects nothing. The app has no account, no server of the developer's, no analytics, no crash reporting of its own and no ads.
- What you enter stays on your device. What you send goes only to the servers you have added, over SSH.

## What the app keeps on your device

- **Servers you add**: the name, host, port, user name, path to herdr and herdr session, the server's SSH host key that you accepted, and the folders the app has seen on that server.
- **The phone's SSH key**: it is made on the device. The private key is stored encrypted with an AES-256 key of the Android Keystore (in StrongBox where the device has it) and is never sent anywhere. The public key is shown so that you can add it to your servers.
- **Settings**: the notification settings, the time and result of the last background check, the last status of each agent that the notifications saw, and how the files window lists files.
- **Snippets**: the prompt texts you save.
- **File lists**: the paths, sizes and times of the files the app found on your agents' screens or that you sent, for each server. Files you download are saved to the app's folder in the device's Download folder, where you and other apps see them like any download.
- **A Telegram bot, if you connect one**: the bot's token, its name, and the id and title of your chat with it.

The app shows the screens of your agents and terminals as it receives them from your server. It does not keep screen contents, or the prompts and keys you type, after they are shown or sent.

## What leaves your device, and where it goes

- **To your servers only.** Prompts, key presses, answers to agents, files you send, and text or files you share to Shepherd from other apps go over SSH to the server you chose. The app reads the agents' screens, file lists and files from the same server. SSH encrypts everything in transit, and the server's host key is pinned after you confirm its fingerprint.
- **Nothing to the developer or to third parties.** The app contains no third-party SDKs that send data. Its only network connections are the SSH connections to your servers. Google Play may show the developer the crash and usage statistics that Android collects if you allowed that in your device's settings; the app adds nothing to them.
- **Telegram, only if you connect your own bot.** The phone does not connect to Telegram. Your server does, with curl: to find your chat with the bot while you set it up, and later to deliver an agent's answer when you ask for it in Telegram. For that the app puts the bot's token into a file on that server (`~/.config/herdr-android/telegram-bot-token`, readable only by your user there). What your bot sends is handled by Telegram under Telegram's own privacy policy.
- **The agents are not part of the app.** Claude Code, Codex and other agents run on your servers under their providers' terms. The app passes your input to them through your server and sends nothing to their providers itself.

## Permissions

- Internet and network state: to connect to your servers and to reconnect when the network comes back.
- Local network (Android 17 and later): to reach a server in your home network.
- Notifications: to tell you that an agent needs input or has finished.
- Foreground services, wake lock, start after boot: to finish file transfers, to keep watching your agents for a few minutes after you leave the app, and to keep the periodic check scheduled.

The app does not use your location, contacts, camera, microphone, advertising ID or other device identifiers.

## Security

Connections use SSH with public-key authentication and a pinned host key. The private key is encrypted with a key that stays in the Android Keystore. Everything the app stores is in its private storage. Android backups and device-to-device transfer are switched off for the app, so none of it is copied to the cloud or to another device.

## Keeping and deleting data

The app keeps its data until you remove it:

- delete a server, a snippet or the file lists in the app, or disconnect the Telegram bot (this also removes the token file from the servers connected at that moment);
- uninstall the app or clear its data in Android's settings: everything the app stored is deleted, the SSH key included. Downloaded files stay in the Download folder until you delete them.

Data on your own servers is yours to manage. Files sent from the phone are in `herdr-android` in the server's downloads folder. The phone's public key is in `~/.ssh/authorized_keys`: remove its line to take away the phone's access.

The developer holds none of your data, so there is nothing for the developer to delete or to hand over.

## Children

The app is a tool for software developers and is not directed at children.

## Changes

If this policy changes, the new version is published at the same address with a new effective date.

## Contact

Questions about privacy: artalexey@gmail.com
