# Cereal script template

## Getting Started

### Prerequisites
* Install Java SE Development Kit 11: https://www.oracle.com/java/technologies/downloads/#java11

### Installation
1. Create a new GitHub repository and use this repository as template.
2. Clone your project
```sh
git clone https://github.com/Your-Org/Script-Repo-Name.git
```
3. Open the project in your preferred IDE. We recommend using IntelliJ IDEA because of easier troubleshooting when you need any help.

## Configuring template
* You'll need to update the script name in several files:
  * `settings.gradle.kts` update rootProject.name.
  * `.idea/.name` update the file content.
* Rename the package `com.cereal.script.sample` to something you like.
* Update package_name, name and script in `src/main/resources/manifest.json`.

## Usage
* The repository contains a SampleScript class which is the main entrance for Cereal to start your script. Remove any boilerplate code from that class that you don't need and rename the script to something more descriptive. Do the same for SampleConfiguration.
* Implement the functionality of your script.
* Verify that your script is working properly by running the included test (see TestSampleScript.kt)

### Available gradle commands
* Run all tests
```sh
./gradlew test
```

### Creating a release using GitHub Actions

The easiest way to build the script binary is by creating a git tag. This will trigger GitHub Actions to build a jar, update the version_code,
obfuscate it, and automatically create a GitHub Release with the jar attached as an artifact. You can find the release
on the [GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases) page of
your repository.

To create a release:
1. Commit and push your changes
2. Create and push a tag: `git tag v1.0.0 && git push origin v1.0.0`
3. The workflow will automatically build and create a GitHub Release using the tag as the version, with `release-<version>.jar` attached

### CI/CD
A GitHub actions configuration is included in this repository. It contains the following actions:

* On each push to master tests will run.
* When a tag is created a script release JAR is generated and a GitHub Release is automatically created with the JAR attached.

## Additional Resources
For more detailed information on script development and publishing to the Cereal Marketplace:
* [Cereal Developer Documentation](https://docs.cereal-automation.com/)
* [Publishing Scripts Guide](https://docs.cereal-automation.com/developers/publishing/) - Learn how to publish your script to the Cereal Marketplace

