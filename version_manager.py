#!/usr/bin/env python3
"""
Version Manager for TKJ_OnE Android App
Handles version code and version name management for both Debug and Release builds.
"""

import json
import os
import sys
import re
from pathlib import Path
from datetime import datetime

VERSION_FILE = "version_info.json"
BUILD_GRADLE_PATH = "app/build.gradle.kts"
STARTING_VERSION_CODE = 2
STARTING_VERSION_NAME = "1.0.2"

def get_version_info():
    """Load version info from file or create default."""
    if os.path.exists(VERSION_FILE):
        with open(VERSION_FILE, 'r') as f:
            return json.load(f)
    else:
        # Default starting version
        return {
            "version_code": STARTING_VERSION_CODE,
            "version_name_base": STARTING_VERSION_NAME,
            "last_updated": datetime.now().isoformat()
        }

def save_version_info(version_info):
    """Save version info to file."""
    version_info["last_updated"] = datetime.now().isoformat()
    with open(VERSION_FILE, 'w') as f:
        json.dump(version_info, f, indent=2)

def parse_version_name(version_name):
    """Parse version name into major, minor, patch components."""
    match = re.match(r'(\d+)\.(\d+)\.(\d+)', version_name)
    if match:
        return int(match.group(1)), int(match.group(2)), int(match.group(3))
    return None, None, None

def increment_version(version_name, bump_type='patch'):
    """Increment version based on bump type."""
    major, minor, patch = parse_version_name(version_name)
    if major is None:
        return STARTING_VERSION_NAME
    
    if bump_type == 'major':
        major += 1
        minor = 0
        patch = 0
    elif bump_type == 'minor':
        minor += 1
        patch = 0
    else:  # patch
        patch += 1
    
    return f"{major}.{minor}.{patch}"

def update_build_gradle(version_code, version_name):
    """Update build.gradle.kts with new version info."""
    build_gradle_path = Path(BUILD_GRADLE_PATH)
    if not build_gradle_path.exists():
        print(f"Warning: {BUILD_GRADLE_PATH} not found")
        return False
    
    content = build_gradle_path.read_text()
    
    # Update versionCode
    content = re.sub(
        r'versionCode\s*=\s*\d+',
        f'versionCode = {version_code}',
        content
    )
    
    # Update versionName (base version without suffix)
    content = re.sub(
        r'versionName\s*=\s*"[^"]+"',
        f'versionName = "{version_name}"',
        content
    )
    
    build_gradle_path.write_text(content)
    return True

def get_next_version_code():
    """Get the next version code."""
    version_info = get_version_info()
    next_code = version_info.get("version_code", STARTING_VERSION_CODE)
    version_info["version_code"] = next_code + 1
    save_version_info(version_info)
    return next_code

def get_current_version_name():
    """Get current base version name."""
    version_info = get_version_info()
    return version_info.get("version_name_base", STARTING_VERSION_NAME)

def main():
    if len(sys.argv) < 2:
        print("Usage:")
        print("  python version_manager.py get-version-code     - Get next version code")
        print("  python version_manager.py get-version-name     - Get current version name")
        print("  python version_manager.py set-release [type]   - Set version for release (stable/nightly/stay-version)")
        print("  python version_manager.py set-debug            - Set version for debug build")
        print("  python version_manager.py bump [major|minor|patch] - Bump version")
        print("  python version_manager.py reset                - Reset version info")
        sys.exit(1)
    
    command = sys.argv[1]
    
    if command == "get-version-code":
        version_info = get_version_info()
        print(version_info.get("version_code", STARTING_VERSION_CODE))
    
    elif command == "get-version-name":
        version_info = get_version_info()
        print(version_info.get("version_name_base", STARTING_VERSION_NAME))
    
    elif command == "set-release":
        release_type = sys.argv[2] if len(sys.argv) > 2 else "stable"
        version_info = get_version_info()
        version_code = version_info.get("version_code", STARTING_VERSION_CODE)
        version_name_base = version_info.get("version_name_base", STARTING_VERSION_NAME)
        
        # Format: X.X.X (Release)
        version_name = f"{version_name_base} (Release)"
        
        print(f"VERSION_CODE={version_code}")
        print(f"VERSION_NAME={version_name}")
        print(f"VERSION_NAME_BASE={version_name_base}")
        print(f"RELEASE_TYPE={release_type}")
        print(f"APK_SUFFIX={release_type}")
        
        # Increment version code for next build
        version_info["version_code"] = version_code + 1
        save_version_info(version_info)
    
    elif command == "set-debug":
        version_info = get_version_info()
        version_code = version_info.get("version_code", STARTING_VERSION_CODE)
        version_name_base = version_info.get("version_name_base", STARTING_VERSION_NAME)
        
        # Format: X.X.X (Debug)
        version_name = f"{version_name_base} (Debug)"
        
        print(f"VERSION_CODE={version_code}")
        print(f"VERSION_NAME={version_name}")
        print(f"VERSION_NAME_BASE={version_name_base}")
        print(f"RELEASE_TYPE=nightly")
        print(f"APK_SUFFIX=nightly")
        
        # Increment version code for next build
        version_info["version_code"] = version_code + 1
        save_version_info(version_info)
    
    elif command == "bump":
        bump_type = sys.argv[2] if len(sys.argv) > 2 else "patch"
        version_info = get_version_info()
        current_version = version_info.get("version_name_base", STARTING_VERSION_NAME)
        new_version = increment_version(current_version, bump_type)
        version_info["version_name_base"] = new_version
        save_version_info(version_info)
        print(f"Bumped version from {current_version} to {new_version}")
    
    elif command == "reset":
        version_info = {
            "version_code": STARTING_VERSION_CODE,
            "version_name_base": STARTING_VERSION_NAME,
            "last_updated": datetime.now().isoformat()
        }
        save_version_info(version_info)
        print(f"Version info reset to {STARTING_VERSION_NAME} (code: {STARTING_VERSION_CODE})")
    
    else:
        print(f"Unknown command: {command}")
        sys.exit(1)

if __name__ == "__main__":
    main()
