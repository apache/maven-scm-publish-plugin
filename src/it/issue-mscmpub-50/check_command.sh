#!/bin/bash

# Function to check command existence
check_command() {
    command -v "$1" >/dev/null 2>&1 || { echo >&2 "Error: $1 is not installed."; exit 1; }
}

# Check for svn
check_command svn

# Check for svnadmin
check_command svnadmin

# Check for CreateSymbolicLink (For Unix-like systems, it might be `ln`)
# Adjust this according to your environment
if [[ "$OSTYPE" == "linux-gnu"* ]] || [[ "$OSTYPE" == "darwin"* ]]; then
    check_command ln
else
    # Assuming Windows environment
    powershell -Command "Get-Command CreateSymbolicLink" >/dev/null 2>&1 || { echo >&2 "Error: CreateSymbolicLink is not available."; exit 1; }
fi

echo "All required commands are available."
