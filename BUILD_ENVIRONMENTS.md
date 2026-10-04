# Build Environments

SpeedVPN does not read a .env file at runtime. CI injects public endpoint configuration through Gradle environment variables.

## Debug
- BUILD_ENV=debug
- Override with SPEEDVPN_API_BASE, SPEEDVPN_AUTH_URL, SPEEDVPN_AUTH_KEY.
- Optional SPEEDVPN_SENTRY_DSN enables crash reporting.

## Production
- BUILD_ENV=production
- CI should provide production values explicitly through protected repository/environment secrets.
- Do not commit private signing keys or backend service-role credentials.

The repository currently contains fallback public client endpoints so a local developer can build without configuring environment variables. A production CI job should override them explicitly.
