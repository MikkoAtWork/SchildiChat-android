#!/bin/bash

workplace=$(pwd)
#outputpath=$workplace/out

echo "$(date): Workplace path: $workplace"

./gradlew assembleDebug

# Copy artifacts
echo "$(date): Copy artifacts"
mkdir -p $outputpath
cp /workspace/platforms/android/app/build/outputs/apk/debug/*.apk $outputpath/



