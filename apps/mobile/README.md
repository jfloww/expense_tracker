# Mobile client — later

Reserve this directory for a React Native + Expo app. Connect it to the same
authenticated Spring Boot ledger API after the web import/review/report flow works.

Reuse generated API contracts and behavior, while choosing mobile-specific
navigation, secure token storage, and file selection. No Expo project or mobile
dependencies are installed yet.
The initial browser session/CSRF design does not select a mobile token protocol;
define that flow during the mobile phase. See
[the backend transition](../../docs/backend-transition.md).
