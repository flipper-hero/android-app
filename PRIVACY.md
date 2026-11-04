# FlipperHero for Android privacy policy

FlipperHero is published by anycast.io UG (haftungsbeschränkt). Contact: franz@bett.ag.

FlipperHero has no app account or advertising. AI requests go directly to your chosen provider.
Its Bluetooth device, file browser and remote controls work without an AI provider key.

## Information stored on your phone

Provider API keys are stored in Android Keystore-backed encrypted preferences. Agent tools cannot
read or change those keys. Settings, the last connected device address, cached device inventories,
and an audit log of agent actions are stored in the app's private storage. Device inventories and
audit logs can contain device names, file names, paths, operation summaries and errors. They are
not encrypted by the app. Android backups and device transfers of app data are disabled.

Chat messages and attached photos are kept in memory for the current conversation. The camera
uses a temporary private cache file, which is removed when capture returns. The app does not
write a persistent chat archive. Automatic context compaction changes what is sent to the
model without deleting the in-memory transcript.

## AI providers

AI chat is optional. You choose a supported provider and supply your own API key. When you use
chat, your messages, attached photos, tool definitions and relevant device information are sent
directly to that provider. File contents and device screen captures can also be sent when the
agent reads them to carry out your request. The selected AI service may forward requests to its
model provider. Their privacy policies, retention rules and usage charges apply separately.

Choosing a model catalog sends an authenticated request to the selected provider. “Test and
save” sends a short test prompt; it does not include your conversation or device data. Keys,
models and endpoint settings are kept separately for each provider.

FlipperHero supports OpenRouter, 2342.ai, Blackbit, OrcaRouter, Z.ai, Kimi, Qwen Cloud and MiniMax.
Provider documentation is linked in the app. Do not include information you are not allowed to
share with the provider you select.

## Optional usage analytics

Usage analytics are off by default. If you enable “Share usage statistics” in Settings,
the app sends screen names, basic usage events (app open, completed or failed agent request,
and submitted content report), app version and edition to our Rybbit service at
`https://stats.2342.dev`. A random session identifier exists only in app memory and changes
when the process restarts or you change the setting. It is not an advertising or device ID.
The service also receives your IP address and an app user agent containing the Android version;
Rybbit may derive approximate location and device statistics from that request information.

Analytics never include chat messages, photos, provider keys, model IDs, Flipper names,
Bluetooth addresses, file paths, file contents, tool arguments or raw error messages. You can
disable analytics at any time. Debug builds do not transmit analytics. This integration uses
the Rybbit HTTP interface and does not load a third-party analytics SDK.

## Content reports

“Report reply” lets you send an AI reply for private review without leaving the app. The
editable excerpt, optional comment, selected category, provider, model and app version are sent
over HTTPS to `https://2342.ai/v1/content-reports`. Review the excerpt and remove sensitive
information before sending it. Other chat messages, photos and your stored provider key are
not attached. Reports do not require a 2342.ai account.

Report text and comments are encrypted in backend storage. Only authenticated administrators
can read reports or update their review status. Reports expire after 90 days and are removed
by the backend cleanup job. We use reports to review offensive or harmful content and improve
safeguards. Contact franz@bett.ag about privacy requests concerning a report.

## Request data deletion

FlipperHero has no app account to delete. To request deletion of a content report, email
[franz@bett.ag](mailto:franz@bett.ag) with “FlipperHero data deletion” in the subject and the
approximate submission date and enough of the reported excerpt to identify it. Do not send
API keys or other secrets. We may ask for clarification before deleting the matching report.
Anonymous analytics cannot always be associated with an individual; tell us the approximate
time and platform if your request concerns analytics. Requests concerning AI conversations
stored by your chosen provider must also be made to that provider under its privacy policy.

## Device features and permissions

- Bluetooth permission discovers and connects to your Flipper Zero. Scan results are not used
  to derive your location or sent to the publisher.
- Camera permission is requested when you choose to attach a photo to chat. An attached photo
  is sent to the selected AI provider only when you send the message.
- Microphone permission is requested when you choose voice input. Recognition uses the speech
  recognition service configured on your Android device, which may process audio remotely under
  its own privacy policy. FlipperHero does not store audio recordings.
- Read-aloud uses the Android text-to-speech service, which may use an online voice under its
  provider's privacy policy.
- Notification permission shows connected-device and active-operation controls. A foreground
  service maintains the Bluetooth connection during device operations.
- Internet access retrieves AI responses, model catalogs, firmware release information and
  requested resources from the official Flipper app catalog and GitHub. These services receive
  the network information normally sent with an HTTPS request, including your IP address.

## Retention and control

You can remove a provider key in Settings and clear the in-memory conversation with “New chat.”
Disconnecting disarms engagement mode in the full edition. Clearing Android app storage or
uninstalling removes locally stored settings, keys, inventories and audit logs. Copies already
sent to a third-party service are subject to that service's retention and deletion process.

The full edition allows an explicit export of an engagement report through Android's share
sheet. You choose its destination. This feature is not included in the Play edition.

The app is not directed at children. If the app's data practices change, this policy will be
updated alongside the release.
