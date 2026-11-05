# Contributing to ZERAVRIX Phone

Thank you for your interest in contributing to ZERAVRIX Phone. We welcome community contributions, bug fixes, performance enhancements, and feature proposals.

---

## Guidelines

1. Issues and Feature Requests:
   - Before opening a new issue, search existing issues to check if the topic has already been addressed.
   - For bug reports, provide complete device information, Android version, steps to reproduce, and screenshots or logs where applicable.
   - For new feature proposals, describe the user benefit, expected workflow, and implementation strategy.

2. Code Standards:
   - Follow standard Kotlin style guidelines and official Android architecture recommendations.
   - Keep commits focused and clearly described.
   - Maintain documentation integrity and avoid adding unnecessary third-party dependencies to preserve runtime efficiency and lean APK size.

3. Testing and Verification:
   - Verify that all unit tests pass before submitting pull requests:
     ./gradlew testDebugUnitTest
   - Ensure the project compiles cleanly without errors:
     ./gradlew assembleDebug

---

## Community and Links

- Official Website: https://zeravrix.pages.dev/
- GitHub Issues: https://github.com/zeravrix/ZERAVRIX-Phone/issues
- Releases: https://github.com/zeravrix/ZERAVRIX-Phone/releases
