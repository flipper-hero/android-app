# JDK 21 and a license-accepted Android SDK for this project.
# nix-shell
{ pkgs ? import <nixpkgs> { config.allowUnfree = true; }, withEmulator ? false }:
let
  androidEnv = pkgs.callPackage (pkgs.path + "/pkgs/development/mobile/androidenv") {
    inherit pkgs;
    licenseAccepted = true;
  };
  androidComposition = androidEnv.composeAndroidPackages {
    cmdLineToolsVersion = "19.0";
    platformVersions = [ "36" ];
    buildToolsVersions = [ "35.0.0" ];
    includeEmulator = withEmulator;
    includeSystemImages = withEmulator;
    systemImageTypes = [ "google_apis" ];
    abiVersions = [ (if pkgs.stdenv.hostPlatform.isAarch64 then "arm64-v8a" else "x86_64") ];
    includeNDK = false;
    includeCmake = false;
  };
  jdk = pkgs.jdk21;
in
pkgs.mkShell {
  packages = [
    jdk
    pkgs.gradle
    androidComposition.androidsdk
    androidComposition.platform-tools
  ];
  JAVA_HOME = "${jdk.home}";
  ANDROID_HOME = "${androidComposition.androidsdk}/libexec/android-sdk";
  ANDROID_SDK_ROOT = "${androidComposition.androidsdk}/libexec/android-sdk";
}
