# FlipperHero for Android privacy policy

FlipperHero is published by anycast.io UG (haftungsbeschränkt). Contact: franz@bett.ag.

FlipperHero has no app account, advertising, analytics SDK, or publisher-operated chat backend.
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
