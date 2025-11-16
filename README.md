# StudioReserve Backend

This repository contains the skeleton of the StudioReserve Ktor backend. It is
organized into feature-focused packages (config, auth, studios, rooms, bookings,
equipment, payments, admin) and exposes helper functions that register each
route module with the application.

## Building and running

Binary files such as the Gradle wrapper JAR cannot be tracked in this
environment, so the project ships only the Gradle build scripts. The
`.gitignore` excludes the wrapper directory and scripts to avoid adding
unsupported binaries by accident. Install Gradle 8.5+ locally and run
commands such as:

```bash
gradle build
```

or

```bash
gradle run
```

from the project root.
