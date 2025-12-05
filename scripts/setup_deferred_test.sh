#!/bin/bash

# Helper script to set up deferred deep link testing
# This script helps create the TEST_LINK and configure the test environment

set -e

echo "Deeplinkly Deferred Deep Link Test Setup"
echo "=========================================="
echo ""

# Check if API key is provided
if [ -z "$1" ]; then
    echo "Usage: $0 <API_KEY> [TEST_LINK_URL]"
    echo ""
    echo "This script will:"
    echo "1. Set up the API key in the example app"
    echo "2. Optionally create a TEST_LINK if URL is provided"
    echo ""
    exit 1
fi

API_KEY=$1
TEST_LINK_URL=$2

echo "Setting up API key in example app..."
echo ""

# Update AndroidManifest.xml with API key
MANIFEST_FILE="example/android/app/src/main/AndroidManifest.xml"

if [ -f "$MANIFEST_FILE" ]; then
    # Replace placeholder with actual API key
    if [[ "$OSTYPE" == "darwin"* ]]; then
        # macOS
        sed -i '' "s/YOUR_API_KEY_HERE/$API_KEY/g" "$MANIFEST_FILE"
    else
        # Linux
        sed -i "s/YOUR_API_KEY_HERE/$API_KEY/g" "$MANIFEST_FILE"
    fi
    echo "✓ API key set in AndroidManifest.xml"
else
    echo "✗ Error: AndroidManifest.xml not found at $MANIFEST_FILE"
    exit 1
fi

echo ""
echo "Setup complete!"
echo ""
echo "Next steps:"
echo "1. Create a TEST_LINK using your Deeplinkly dashboard or API"
echo "2. The link should have identifier 'TEST_LINK'"
echo "3. Note the link URL"
echo ""

if [ -n "$TEST_LINK_URL" ]; then
    echo "Test Link URL: $TEST_LINK_URL"
    echo ""
    echo "To test deferred deep linking:"
    echo "1. Click the link above (before installing the app)"
    echo "2. Install the app on device"
    echo "3. Launch the app - deferred deep link data should appear"
    echo ""
    echo "To run on Firebase Test Lab:"
    echo "  export TEST_LINK=\"$TEST_LINK_URL\""
    echo "  export FIREBASE_PROJECT_ID=\"your-project-id\""
    echo "  ./scripts/run_device_farm_tests.sh"
else
    echo "To create a TEST_LINK:"
    echo "  - Use your Deeplinkly dashboard"
    echo "  - Or use the API to create a link with identifier 'TEST_LINK'"
    echo ""
    echo "Once you have the link URL, set it as:"
    echo "  export TEST_LINK=\"your-link-url\""
fi

echo ""

