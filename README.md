# AriaAppTemplate

Minimal Android project template used by [Aria](https://github.com/woosik2510/aria) to scaffold new apps remotely.

## What Aria does with this

When you tap **+ → Android app** in Aria, it:

1. Copies this folder to `PROJECTS_BASE\YourAppName`
2. Replaces all `AriaAppTemplate` / `ariaapptemplate` occurrences with your app name
3. Renames the source package directory from `com/aria/ariaapptemplate` to `com/aria/<yourappname>`
4. Runs `git init`

## Using this template manually

```bash
cp -r AriaAppTemplate MyNewApp
cd MyNewApp
# Replace AriaAppTemplate with your app name in all files
# Rename app/src/*/java/com/aria/ariaapptemplate/ to com/aria/yourappname/
git init
```

## Requirements

- Android Studio Hedgehog+ (JDK 17, AGP 8.x)
- `compileSdk = release(36)` — requires AGP 8.7+
