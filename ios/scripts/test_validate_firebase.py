"""Run with python3 ios/scripts/test_validate_firebase.py; no credentials required."""
import os
from pathlib import Path
import plistlib
import subprocess
import tempfile

script = Path(__file__).with_name("validate_firebase.sh")
environment = dict(
    os.environ,
    CONFIGURATION="Release",
    PLATFORM_NAME="iphoneos",
    MARKETING_VERSION="0.6.0",
    CURRENT_PROJECT_VERSION="17",
    PRODUCT_BUNDLE_IDENTIFIER="com.mvvtracker.app",
)
config = {"PROJECT_ID": "your-firebase-project-id", "BUNDLE_ID": "com.mvvtracker.app"}
cases = [
    ("production", {}, {}, True),
    ("wrong Firebase project", {"PROJECT_ID": "another-project"}, {}, False),
    ("wrong Firebase bundle", {"BUNDLE_ID": "com.other.app"}, {}, False),
    ("wrong app bundle", {}, {"PRODUCT_BUNDLE_IDENTIFIER": "com.other.app"}, False),
    ("wrong release build", {}, {"CURRENT_PROJECT_VERSION": "1"}, False),
    ("release placeholder", {"PROJECT_ID": "demo-t2t"}, {}, False),
    ("debug simulator placeholder", {"PROJECT_ID": "demo-t2t"},
     {"CONFIGURATION": "Debug", "PLATFORM_NAME": "iphonesimulator"}, True),
    ("debug device placeholder", {"PROJECT_ID": "demo-t2t"}, {"CONFIGURATION": "Debug"}, False),
]

with tempfile.TemporaryDirectory() as directory:
    plist = Path(directory) / "GoogleService-Info.plist"
    for name, changes, env, expected in cases:
        plist.write_bytes(plistlib.dumps(config | changes))
        result = subprocess.run(
            ["/bin/sh", str(script), str(plist)], env=environment | env,
            capture_output=True, text=True,
        )
        if (result.returncode == 0) != expected:
            raise AssertionError(f"{name}: {result.stdout} {result.stderr}")
        print(f"PASS: {name}")
